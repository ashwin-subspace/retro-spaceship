"""Application shell: window, service wiring, state machine and the main loop.

States: START, SETTINGS, SCORES, PLAYING, PAUSED, DYING, UPGRADE, RESULTS.

The game clock only advances in PLAYING/DYING, so pausing needs no timestamp
compensation anywhere. Menu animation uses wall-clock time instead, which is
why menus keep moving even while a hitstop has gameplay frozen.
"""

import math
import sys

import pygame

from . import audio as audio_mod
from . import config, director as director_mod, fx as fx_mod, hud, platform_api, save, theme, upgrades, viewport as viewport_mod, world as world_mod
from .clock import GameClock
from .entities.stars import StarField
from .ui import menus, screens
from .ui.touch import TouchPad
from .ui import text as text_mod
from .ui.menus import Menu, MenuItem


class Game:
    def __init__(self, headless=False):
        pygame.init()
        pygame.display.set_caption(config.TITLE)

        self.data = save.load()
        theme.apply_theme(self.data["theme"])
        # An explicit choice wins; otherwise follow the platform default.
        if self.data.get("quality"):
            platform_api.set_quality(self.data["quality"])

        self.audio, self.audio_note = audio_mod.create(
            muted=self.data["muted"],
            sfx_volume=self.data["sfx_volume"],
            music_volume=self.data["music_volume"],
        )

        # The play field surface stays exactly 480x640 so no gameplay
        # coordinate ever changes; the window grows downward to make room for
        # the touch gutter, and the browser scales the whole canvas with CSS.
        self.viewport = viewport_mod.create()
        self.screen = pygame.display.set_mode(self.viewport.screen_size)
        self.render_surface = pygame.Surface((config.WIDTH, config.HEIGHT))
        self.touch = TouchPad(self.viewport.gutter_rect)
        self.pygame_clock = pygame.time.Clock()

        self.clock = GameClock()
        self.fx = fx_mod.ScreenFX(self.clock, enable_crt=self.data["crt_enabled"])
        self.stars = StarField()

        self.state = "START"
        self.running = True
        self.world = None
        self.director = None
        self.ctx = None
        self.death_at = 0.0
        self.headless = headless
        self.show_debug = False
        self.frame_ms = 0.0

        self.upgrade_cards = []
        self.upgrade_card_rects = []
        self.upgrade_index = 0
        self.focus_latched = False
        self.drag_anchor = (0.0, 0.0)
        self._touch_seen = False
        self._drag_finger = None
        self._drag_pos = (0.0, 0.0)
        self._prev_director_state = None
        self.result_rank = "E"
        self.result_high_rank = None

        self._build_menus()
        self.audio.play_music("MENU")

    # -- menus -----------------------------------------------------------

    def _build_menus(self):
        start_items = [
            MenuItem("START GAME", "start"),
            MenuItem("HIGH SCORES", "scores"),
            MenuItem("SETTINGS", "settings"),
        ]
        # No QUIT on web -- exiting the loop there just leaves a dead canvas
        # with no way back.
        if not platform_api.IS_WEB:
            start_items.append(MenuItem("QUIT", "quit"))
        self.start_menu = Menu(start_items)
        self.settings_menu = Menu([
            MenuItem(lambda: f"THEME  {self.data['theme'].upper()}",
                     on_left=lambda: self._cycle_theme(-1),
                     on_right=lambda: self._cycle_theme(1)),
            MenuItem(lambda: f"SFX    {menus.volume_bar(self.data['sfx_volume'])}",
                     on_left=lambda: self._adjust_volume("sfx_volume", -10),
                     on_right=lambda: self._adjust_volume("sfx_volume", 10)),
            MenuItem(lambda: f"MUSIC  {menus.volume_bar(self.data['music_volume'])}",
                     on_left=lambda: self._adjust_volume("music_volume", -10),
                     on_right=lambda: self._adjust_volume("music_volume", 10)),
            MenuItem(lambda: f"DETAIL {platform_api.QUALITY}",
                     on_left=self._toggle_quality, on_right=self._toggle_quality),
            MenuItem(lambda: f"CRT    {'ON' if self.data['crt_enabled'] else 'OFF'}",
                     on_left=self._toggle_crt, on_right=self._toggle_crt),
            MenuItem(lambda: f"MUTE   {'ON' if self.data['muted'] else 'OFF'}",
                     on_left=self._toggle_mute, on_right=self._toggle_mute),
            MenuItem("BACK", "back"),
        ])
        self.scores_menu = Menu([MenuItem("BACK", "back")])
        self.pause_menu = Menu([
            MenuItem("RESUME", "resume"),
            MenuItem("SETTINGS", "settings"),
            MenuItem("QUIT TO MENU", "quit_to_menu"),
        ])
        self.results_menu = Menu([
            MenuItem("PLAY AGAIN", "again"),
            MenuItem("MAIN MENU", "menu"),
        ])
        self.settings_return = "START"

    def _cycle_theme(self, direction):
        order = theme.THEME_ORDER
        index = (order.index(self.data["theme"]) + direction) % len(order)
        self.data["theme"] = order[index]
        theme.apply_theme(self.data["theme"])
        save.save(self.data)

    def _adjust_volume(self, key, delta):
        self.data[key] = max(0, min(100, self.data[key] + delta))
        if key == "sfx_volume":
            self.audio.set_sfx_volume(self.data[key])
        else:
            self.audio.set_music_volume(self.data[key])
        save.save(self.data)

    def _toggle_quality(self):
        """Swap the visual budget. Takes effect on the next run's particles."""
        level = "HIGH" if platform_api.QUALITY == "LOW" else "LOW"
        platform_api.set_quality(level)
        self.data["quality"] = level
        save.save(self.data)

    def _toggle_crt(self):
        self.data["crt_enabled"] = not self.data["crt_enabled"]
        self.fx.enable_crt = self.data["crt_enabled"]
        save.save(self.data)

    def _toggle_mute(self):
        self.data["muted"] = not self.data["muted"]
        self.audio.set_muted(self.data["muted"])
        if not self.data["muted"]:
            self.audio.play_music("MENU" if self.state == "START" else "BATTLE_A", 0)
        save.save(self.data)

    # -- lifecycle -------------------------------------------------------

    def start_run(self):
        self.world = world_mod.World()
        self.director = director_mod.Director()
        self.clock.clear_effects()
        self.fx.clear_transients()
        self.ctx = world_mod.Ctx(self.clock, self.fx, self.audio, self.world,
                                 self.world.player)
        self.state = "PLAYING"
        self._prev_director_state = None
        self.focus_latched = False
        self.audio.play_music("BATTLE_A")

    def end_run(self):
        world = self.world
        self.result_rank = world.stats.rank(world.score)
        self.result_high_rank = None
        if save.qualifies(self.data, world.score):
            self.result_high_rank = save.add_high_score(
                self.data, world.score, world.stats.stage, world.stats.wave,
                world.stats.time_ms, world.player.weapon)
            self.audio.play("new_high_score")
        save.record_run(self.data, world.stats)
        save.save(self.data)
        self.results_menu.reset()
        self.state = "RESULTS"
        self.audio.play_music("MENU")

    def to_start(self):
        self.state = "START"
        self.start_menu.reset()
        self.world = None
        self.director = None
        self.audio.play_music("MENU")

    # -- input -----------------------------------------------------------

    def handle_event(self, event):
        if event.type == pygame.QUIT:
            self.running = False
            return

        if event.type in (pygame.FINGERDOWN, pygame.FINGERUP, pygame.FINGERMOTION):
            self._handle_finger(event)
            return
        # SDL synthesises mouse events from touch. Once we are handling real
        # finger events we must ignore those, or every tap fires twice.
        if self._touch_seen and event.type in (pygame.MOUSEBUTTONDOWN,
                                               pygame.MOUSEBUTTONUP,
                                               pygame.MOUSEMOTION):
            return
        if event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
            self._handle_escape()
            return
        if event.type == pygame.KEYDOWN and event.key == pygame.K_F3:
            self.show_debug = not self.show_debug
            return

        if self._route_pointer_to_menu(event):
            return

        handler = getattr(self, f"_events_{self.state.lower()}", None)
        if handler is not None:
            handler(event)

    MENU_FOR_STATE = {
        "START": ("start_menu", "_apply_start_action"),
        "SETTINGS": ("settings_menu", "_apply_settings_action"),
        "SCORES": ("scores_menu", "_apply_scores_action"),
        "PAUSED": ("pause_menu", "_apply_pause_action"),
        "RESULTS": ("results_menu", "_apply_results_action"),
    }

    def _route_pointer_to_menu(self, event):
        """Taps drive menus on touch devices. Returns True if handled."""
        if event.type not in (pygame.MOUSEBUTTONDOWN, pygame.MOUSEBUTTONUP):
            return False
        if getattr(event, "button", 1) != 1:
            return False
        if self.state == "UPGRADE":
            self._upgrade_pointer(event)
            return True
        entry = self.MENU_FOR_STATE.get(self.state)
        if entry is None:
            return False
        menu = getattr(self, entry[0])
        phase = "down" if event.type == pygame.MOUSEBUTTONDOWN else "up"
        action = menu.handle_pointer(event.pos[0], event.pos[1], phase, self.audio)
        if action is not None:
            getattr(self, entry[1])(action)
        return True

    def _upgrade_pointer(self, event):
        """Tap to select a card, tap again to confirm -- upgrades are permanent."""
        if event.type != pygame.MOUSEBUTTONUP:
            return
        for i, rect in enumerate(self.upgrade_card_rects):
            if rect.collidepoint(event.pos):
                if i == self.upgrade_index:
                    self._choose_upgrade()
                else:
                    self.upgrade_index = i
                    self.audio.play("menu_move")
                return

    def _handle_escape(self):
        if self.state == "PLAYING":
            self.state = "PAUSED"
            self.pause_menu.reset()
            self.audio.play("menu_back")
        elif self.state == "PAUSED":
            self.state = "PLAYING"
        elif self.state == "SETTINGS":
            self.state = self.settings_return
            self.audio.play("menu_back")
        elif self.state == "SCORES":
            self.state = "START"
            self.audio.play("menu_back")
        elif self.state == "START":
            if not platform_api.IS_WEB:
                self.running = False

    def _events_start(self, event):
        self._apply_start_action(self.start_menu.handle(event, self.audio))

    def _apply_start_action(self, action):
        if action == "start":
            self.start_run()
        elif action == "settings":
            self.settings_return = "START"
            self.settings_menu.reset()
            self.state = "SETTINGS"
        elif action == "scores":
            self.scores_menu.reset()
            self.state = "SCORES"
        elif action == "quit":
            self.running = False

    def _events_settings(self, event):
        self._apply_settings_action(self.settings_menu.handle(event, self.audio))

    def _apply_settings_action(self, action):
        if action == "back":
            self.state = self.settings_return

    def _events_scores(self, event):
        self._apply_scores_action(self.scores_menu.handle(event, self.audio))

    def _apply_scores_action(self, action):
        if action == "back":
            self.state = "START"

    def _events_paused(self, event):
        self._apply_pause_action(self.pause_menu.handle(event, self.audio))

    def _apply_pause_action(self, action):
        if action == "resume":
            self.state = "PLAYING"
        elif action == "settings":
            self.settings_return = "PAUSED"
            self.settings_menu.reset()
            self.state = "SETTINGS"
        elif action == "quit_to_menu":
            self.to_start()

    def _events_results(self, event):
        self._apply_results_action(self.results_menu.handle(event, self.audio))

    def _apply_results_action(self, action):
        if action == "again":
            self.start_run()
        elif action == "menu":
            self.to_start()

    def _events_upgrade(self, event):
        if event.type != pygame.KEYDOWN:
            return
        if event.key in (pygame.K_UP, pygame.K_w):
            self.upgrade_index = (self.upgrade_index - 1) % len(self.upgrade_cards)
            self.audio.play("menu_move")
        elif event.key in (pygame.K_DOWN, pygame.K_s):
            self.upgrade_index = (self.upgrade_index + 1) % len(self.upgrade_cards)
            self.audio.play("menu_move")
        elif event.key in (pygame.K_RETURN, pygame.K_KP_ENTER, pygame.K_SPACE):
            self._choose_upgrade()

    def _choose_upgrade(self):
        card = self.upgrade_cards[self.upgrade_index]
        card.apply(self.world)
        self.audio.play("upgrade_pick")
        self.fx.popup(config.WIDTH * 0.5, config.HEIGHT * 0.5, card.title,
                      theme.color("CYAN"), 20)
        self.state = "PLAYING"

    def _events_playing(self, event):
        if event.type == pygame.MOUSEBUTTONDOWN:
            if event.button == 1:
                self.pointer_down(*event.pos)
            elif event.button == 3:
                self.world.detonate_bomb(self.ctx)
        elif event.type == pygame.MOUSEBUTTONUP:
            if event.button == 1:
                self.pointer_up(*event.pos)
        elif event.type == pygame.KEYDOWN:
            if event.key == pygame.K_SPACE:
                self.world.player.activate_overdrive(self.ctx)
            elif event.key in (pygame.K_b, pygame.K_x):
                self.world.detonate_bomb(self.ctx)

    def _handle_finger(self, event):
        """Real multi-touch.

        One finger owns the ship; other fingers can hit gutter buttons at the
        same time. Without this you have to let go of the ship to bomb, which
        is precisely the moment you cannot afford to stop dodging.

        FINGER* events report normalised 0..1 coordinates, not pixels.
        """
        self._touch_seen = True
        px, py = self.viewport.from_normalised(event.x, event.y)
        finger = getattr(event, "finger_id", 0)

        if event.type == pygame.FINGERDOWN:
            if self.touch.active and self.viewport.in_gutter(px, py):
                button = self.touch.hit(px, py)
                if button is not None and self.state in ("PLAYING", "PAUSED"):
                    button.flash(pygame.time.get_ticks())
                    self.do_touch_action(button.action)
                return
            if self.state == "PLAYING" and self._drag_finger is None:
                self._drag_finger = finger
                self.pointer_down(px, py)
            else:
                self._menu_pointer(px, py, "down")
        elif event.type == pygame.FINGERMOTION:
            if finger == self._drag_finger:
                self._drag_pos = (px, py)
        elif event.type == pygame.FINGERUP:
            if finger == self._drag_finger:
                self._drag_finger = None
                self.pointer_up(px, py)
            else:
                self._menu_pointer(px, py, "up")

    def _menu_pointer(self, px, py, phase):
        if self.state == "UPGRADE":
            if phase == "up":
                for i, rect in enumerate(self.upgrade_card_rects):
                    if rect.collidepoint(px, py):
                        if i == self.upgrade_index:
                            self._choose_upgrade()
                        else:
                            self.upgrade_index = i
                            self.audio.play("menu_move")
                        return
            return
        entry = self.MENU_FOR_STATE.get(self.state)
        if entry is None:
            return
        menu = getattr(self, entry[0])
        action = menu.handle_pointer(px, py, phase, self.audio)
        if action is not None:
            getattr(self, entry[1])(action)

    # -- pointer routing -------------------------------------------------

    def pointer_down(self, px, py):
        """A press anywhere: gutter button, or grab the ship."""
        if self.touch.active and self.viewport.in_gutter(px, py):
            button = self.touch.hit(px, py)
            if button is not None:
                button.flash(pygame.time.get_ticks())
                self.do_touch_action(button.action)
            return
        game_pos = self.viewport.to_game(px, py)
        if game_pos is None or self.world is None:
            return
        player = self.world.player
        player.dragging = True
        if platform_api.DRAG_RELATIVE:
            # Track finger *motion*, not position: an absolute drag parks a
            # fingertip on top of the 3px hitbox the player needs to watch.
            dx = game_pos[0] - player.x
            dy = game_pos[1] - player.y
            limit = 120.0
            length = math.hypot(dx, dy)
            if length > limit:
                dx, dy = dx / length * limit, dy / length * limit
            self.drag_anchor = (dx, dy)
        else:
            self.drag_anchor = (0.0, 0.0)

    def pointer_up(self, px, py):
        if self.world is not None:
            self.world.player.dragging = False

    def do_touch_action(self, action):
        if action == "bomb":
            self.world.detonate_bomb(self.ctx)
        elif action == "overdrive":
            self.world.player.activate_overdrive(self.ctx)
        elif action == "focus":
            self.focus_latched = not self.focus_latched
            self.audio.play("menu_select")
        elif action == "pause":
            self._handle_escape()

    # -- update ----------------------------------------------------------

    def update(self, real_dt):
        self.audio.update(real_dt)
        boost = 1.0
        if self.state in ("PLAYING", "DYING"):
            self.clock.tick(real_dt)
            self._update_play()
            boost = 1.6 if (self.world and self.world.boss is not None) else 1.0
            self.stars.update(self.clock.dt, boost)
            self.fx.update(self.clock)
        else:
            # Menus keep drifting so the screen never looks dead.
            self.stars.update(real_dt * 0.6)

    def _update_play(self):
        world, player = self.world, self.world.player
        self.ctx.player = player

        if self.state == "PLAYING":
            keys = pygame.key.get_pressed()
            player.focus = bool(keys[pygame.K_LSHIFT] or keys[pygame.K_RSHIFT]
                                or self.focus_latched)
            if player.dragging:
                source = (self._drag_pos if self._drag_finger is not None
                          else pygame.mouse.get_pos())
                pointer = self.viewport.to_game(*source)
                if pointer is not None:
                    player.move_towards(pointer[0] - self.drag_anchor[0],
                                        pointer[1] - self.drag_anchor[1],
                                        self.clock.dts)
            else:
                player.move_keys(keys, self.clock.dts)

        world.update(self.ctx)
        hud.apply_low_health_vignette(world, self.fx)

        if self.state == "PLAYING":
            self.director.update(self.ctx, world)
            if (self._prev_director_state == "BOSS"
                    and self.director.state == "CALM"):
                self._open_upgrade()
            self._prev_director_state = self.director.state

            if not player.alive:
                self.state = "DYING"
                self.death_at = self.clock.t
                self.fx.slowmo(config.SLOWMO_DEATH_SCALE, config.SLOWMO_DEATH_MS)
                self.fx.shockwave(player.x, player.y, 10, 260, 800,
                                  theme.color(config.COLOR_PLAYER), 4)
                self.audio.play("game_over")
        elif self.state == "DYING":
            if self.clock.t - self.death_at > 1500.0:
                self.end_run()

    def _open_upgrade(self):
        self.upgrade_cards = upgrades.draw_three()
        self.upgrade_index = 0
        self.state = "UPGRADE"
        self.audio.play("upgrade_show")

    # -- draw ------------------------------------------------------------

    def draw(self, ui_time):
        surface = self.render_surface
        surface.fill(theme.color("BLACK"))
        self.stars.draw(surface)

        if self.state in ("PLAYING", "PAUSED", "DYING", "UPGRADE", "RESULTS"):
            if self.world is not None:
                self.world.draw(surface)
                self.fx.draw_world(surface)
                self.fx.draw_popups(surface)
                hud.draw(surface, self.world, self.director,
                         self.data.get("high_score", 0), self.clock.t)

        if self.state == "START":
            note = None
            if self.audio_note:
                note = self.audio_note
            elif self.audio.music is not None and not self.audio.music.ready:
                note = "SYNTHESIZING AUDIO..."
            screens.draw_start(surface, self.start_menu, self.data.get("high_score", 0),
                               ui_time, note)
        elif self.state == "SETTINGS":
            surface.fill(theme.color("BLACK"))
            self.stars.draw(surface)
            screens.draw_settings(surface, self.settings_menu)
        elif self.state == "SCORES":
            screens.draw_high_scores(surface, self.data.get("high_scores", []),
                                     self.scores_menu)
        elif self.state == "PAUSED":
            screens.draw_pause(surface, self.pause_menu)
        elif self.state == "UPGRADE":
            self.upgrade_card_rects = screens.draw_upgrade(
                surface, self.upgrade_cards, self.upgrade_index, ui_time)
        elif self.state == "RESULTS":
            screens.draw_results(surface, self.world, self.result_rank,
                                 self.result_high_rank, self.results_menu, ui_time)

        self.fx.draw_overlay(surface)
        if self.show_debug:
            self._draw_debug(surface)

        offset = self.fx.offset
        self.screen.fill(theme.color("BLACK"))
        self.screen.blit(surface, (int(offset[0]), int(offset[1])))
        # The gutter sits outside the shaken play field, so the controls stay
        # rock steady while the world rattles.
        if self.touch.active:
            player = self.world.player if self.world is not None else None
            self.touch.draw(self.screen, player, ui_time)
        pygame.display.flip()

    def _draw_debug(self, surface):
        """F3 overlay: frame cost and entity counts, for spotting slowdowns."""
        lines = [f"frame {self.frame_ms:5.1f}ms  fps {self.pygame_clock.get_fps():4.0f}"]
        if self.world is not None:
            lines.append(f"enemies {len(self.world.enemies):3d}  "
                         f"ebullets {len(self.world.enemy_bullets):3d}  "
                         f"pbullets {len(self.world.player_bullets):3d}")
            lines.append(f"particles {self.fx.particles.count:3d}  "
                         f"pickups {len(self.world.pickups):2d}")
        if self.director is not None:
            snap = self.director.snapshot()
            lines.append(f"stage {snap['stage']} wave {snap['wave']} {snap['flavor']}")
            lines.append(f"intensity {snap['intensity']:.2f} "
                         f"(offset {snap['offset']:+.2f})")
        for i, line in enumerate(lines):
            text_mod.draw_text(surface, line, 11, 8, 84 + i * 13,
                               theme.color("GREEN"), center=False)

    # -- loop ------------------------------------------------------------

    def step(self, real_dt):
        """One frame. The only body shared by the sync and async loops."""
        ui_time = pygame.time.get_ticks()
        frame_start = ui_time

        for event in pygame.event.get():
            self.handle_event(event)

        self.audio.pump(platform_api.AUDIO_BUDGET_MS)
        self.update(real_dt)
        self.draw(ui_time)
        self.frame_ms = pygame.time.get_ticks() - frame_start

    def run(self, max_frames=None):
        """Desktop loop."""
        frames = 0
        while self.running:
            self.step(self.pygame_clock.tick(platform_api.FPS_CAP))
            frames += 1
            if max_frames is not None and frames >= max_frames:
                break

    async def run_async(self, max_frames=None):
        """Web loop (and desktop via main.py).

        The ``await`` hands control back to the browser each frame; without it
        the page would simply freeze. Keeping this loop a near-clone of run()
        is deliberate -- a parity test asserts they behave identically, which is
        what stops the codebase forking into desktop and web variants.
        """
        import asyncio

        frames = 0
        while self.running:
            real_dt = self.pygame_clock.tick(platform_api.FPS_CAP)
            await asyncio.sleep(0)
            self.step(real_dt)
            frames += 1
            if max_frames is not None and frames >= max_frames:
                break


def main(max_frames=None):
    game = Game()
    if game.audio_note:
        print(f"[audio] {game.audio_note}")
    try:
        game.run(max_frames=max_frames)
    finally:
        pygame.quit()
    sys.exit()
