"""The live game world: entity ownership, collisions and scoring.

Collision uses ``Rect.collidelist`` for player-shot-vs-enemy (measured ~7x
faster than nested Python loops at realistic counts) and exact circle maths for
enemy-bullet-vs-player, where precision is what makes a dense screen fair.
"""

import math

import pygame

from . import config, rng, theme
from .entities import pickups as pickups_mod
from .entities.base import distance
from .entities.player import Player


class Ctx:
    """Per-frame services handed to every entity.

    Entities take this instead of importing world/fx/audio, which is what keeps
    the module layering acyclic.
    """

    __slots__ = ("clock", "fx", "audio", "world", "player", "rng")

    def __init__(self, clock, fx, audio, world, player=None):
        self.clock = clock
        self.fx = fx
        self.audio = audio
        self.world = world
        self.player = player
        self.rng = rng


class RunStats:
    def __init__(self):
        self.kills = 0
        self.kills_by_type = {}
        self.shots_fired = 0
        self.shots_hit = 0
        self.hits_taken = 0
        self.bombs_used = 0
        self.best_combo = 0
        self.graze_total = 0
        self.max_graze_streak = 0
        self.bosses_killed = 0
        self.perfect_waves = 0
        self.time_ms = 0.0
        self.stage = 1
        self.wave = 1

    @property
    def accuracy(self):
        if self.shots_fired <= 0:
            return 0.0
        return min(1.0, self.shots_hit / self.shots_fired)

    def rank(self, score):
        """Arcade-style letter grade from a weighted composite."""
        points = 0.0
        points += min(1.0, score / 30000.0) * 42.0
        points += self.accuracy * 20.0
        points += min(1.0, self.best_combo / 40.0) * 14.0
        points += min(1.0, self.max_graze_streak / 40.0) * 10.0
        points += min(1.0, self.bosses_killed / 3.0) * 8.0
        points += max(0.0, 1.0 - self.hits_taken / 12.0) * 6.0
        for threshold, letter in ((85, "S"), (70, "A"), (55, "B"), (38, "C"), (20, "D")):
            if points >= threshold:
                return letter
        return "E"


class World:
    def __init__(self):
        self.player = Player()
        self.enemies = []
        self.player_bullets = []
        self.enemy_bullets = []
        self.pickups = []
        self.lasers = []
        self.boss = None
        self.score = 0
        self.combo = 0
        self.combo_until = 0.0
        self.stats = RunStats()
        self.wave_damage_taken = False
        self.last_hit_t = -99999.0

    # -- spawning --------------------------------------------------------

    def add_enemy(self, enemy):
        self.enemies.append(enemy)

    def add_player_bullet(self, bullet):
        # Counted per bullet, not per volley, so accuracy means what it says.
        self.stats.shots_fired += 1
        self.player_bullets.append(bullet)

    def add_enemy_bullet(self, bullet):
        # Hard cap: past this the screen stops being readable.
        if len(self.enemy_bullets) >= config.MAX_ENEMY_BULLETS:
            return
        self.enemy_bullets.append(bullet)

    def add_pickup(self, pickup):
        self.pickups.append(pickup)

    def add_laser(self, laser):
        self.lasers.append(laser)

    def set_boss(self, boss):
        self.boss = boss

    # -- queries ---------------------------------------------------------

    def nearest_enemy(self, x, y):
        best = None
        best_distance = 1e9
        for enemy in self.enemies:
            d = (enemy.x - x) ** 2 + (enemy.y - y) ** 2
            if d < best_distance:
                best, best_distance = enemy, d
        if self.boss is not None and self.boss.active and best is None:
            return self.boss
        return best

    @property
    def combo_multiplier(self):
        return 1 + self.combo // 5

    @property
    def enemies_pending(self):
        return len(self.enemies)

    # -- scoring ---------------------------------------------------------

    def award(self, amount, ctx, x=None, y=None, popup=True):
        multiplier = self.combo_multiplier
        bonus = 1.0 + (config.OVERDRIVE_SCORE_BONUS if self.player.overdrive_active else 0.0)
        total = int(amount * multiplier * bonus)
        self.score += total
        if popup and x is not None:
            ctx.fx.popup(x, y, f"+{total}", theme.color("YELLOW"), 14)
        return total

    def register_kill(self, enemy, ctx):
        self.combo += 1
        self.combo_until = ctx.clock.t + config.COMBO_TIMEOUT_MS
        self.stats.kills += 1
        self.stats.best_combo = max(self.stats.best_combo, self.combo)
        self.stats.kills_by_type[enemy.key] = self.stats.kills_by_type.get(enemy.key, 0) + 1
        self.player.add_kill_charge()
        self.award(enemy.score_value, ctx, enemy.x, enemy.y)
        drop = pickups_mod.roll_drop(enemy.x, enemy.y,
                                     low_health=self.player.health <= 1)
        if drop is not None:
            self.add_pickup(drop)

    # -- bulk effects ----------------------------------------------------

    def clear_enemy_bullets(self, ctx, to_score=False):
        """Wipe the screen. Bullets become score motes, which is the reward."""
        count = len(self.enemy_bullets)
        if to_score:
            for bullet in self.enemy_bullets[:60]:
                self.add_pickup(pickups_mod.Pickup(
                    bullet.x, bullet.y, "SCORE", vx=rng.uniform(-40, 40),
                    vy=rng.uniform(-60, -10), value=5))
        self.enemy_bullets.clear()
        self.lasers.clear()
        return count

    def detonate_bomb(self, ctx):
        if not self.player.use_bomb(ctx):
            return False
        self.stats.bombs_used += 1
        self.clear_enemy_bullets(ctx, to_score=True)
        ctx.fx.flash(theme.color("WHITE"), 260, 190)
        ctx.fx.shockwave(self.player.x, self.player.y, 20, 460, 700,
                         theme.color(config.COLOR_PICKUP_BOMB), 5)
        ctx.fx.shake(config.SHAKE_ON_BOMB)
        ctx.fx.slowmo(config.SLOWMO_BOMB_SCALE, config.SLOWMO_BOMB_MS)
        ctx.audio.play("bomb")
        for enemy in list(self.enemies):
            if enemy.take_damage(3, ctx):
                self.register_kill(enemy, ctx)
        if self.boss is not None and self.boss.active:
            self.boss.damage_target(self.boss, 18, ctx)
        return True

    # -- frame -----------------------------------------------------------

    def update(self, ctx):
        clock = ctx.clock
        self.stats.time_ms += clock.dt

        if self.combo and clock.t > self.combo_until:
            self.combo = 0

        if self.player.alive:
            self.player.update(ctx)

        for group in (self.enemies, self.player_bullets, self.enemy_bullets,
                      self.pickups, self.lasers):
            for entity in group:
                entity.update(ctx)

        if self.boss is not None:
            self.boss.update(ctx)
            if self.boss.gone:
                self.stats.bosses_killed += 1
                self.award(self.boss.score_value, ctx, self.boss.x, self.boss.y)
                self.boss = None

        self.collide(ctx)
        self._cull()

    def _cull(self):
        self.enemies = [e for e in self.enemies if e.alive]
        self.player_bullets = [b for b in self.player_bullets if b.alive]
        self.enemy_bullets = [b for b in self.enemy_bullets if b.alive]
        self.pickups = [p for p in self.pickups if p.alive]
        self.lasers = [l for l in self.lasers if l.alive]

    # -- collisions ------------------------------------------------------

    def collide(self, ctx):
        self._player_shots_vs_enemies(ctx)
        self._player_shots_vs_boss(ctx)
        if self.player.alive:
            self._enemy_bullets_vs_player(ctx)
            self._lasers_vs_player(ctx)
            self._enemies_vs_player(ctx)
            self._pickups_vs_player(ctx)

    def _player_shots_vs_enemies(self, ctx):
        if not self.enemies or not self.player_bullets:
            return
        enemy_rects = [e.rect.inflate(config.ENEMY_HURTBOX_INFLATE * 2,
                                      config.ENEMY_HURTBOX_INFLATE * 2)
                       for e in self.enemies]
        for bullet in self.player_bullets:
            if not bullet.alive:
                continue
            index = bullet.rect.collidelist(enemy_rects)
            if index < 0:
                continue
            enemy = self.enemies[index]
            if not enemy.alive:
                continue
            self.stats.shots_hit += 1
            direction = math.atan2(bullet.vy, bullet.vx)
            if enemy.take_damage(bullet.damage, ctx, direction):
                self.register_kill(enemy, ctx)
                enemy_rects[index] = pygame.Rect(0, 0, 0, 0)
            if bullet.on_hit():
                bullet.kill()

    def _player_shots_vs_boss(self, ctx):
        boss = self.boss
        if boss is None or not boss.active or not self.player_bullets:
            return
        targets = boss.hit_targets()
        for bullet in self.player_bullets:
            if not bullet.alive:
                continue
            for rect, target in targets:
                if bullet.rect.colliderect(rect):
                    self.stats.shots_hit += 1
                    boss.damage_target(target, bullet.damage, ctx)
                    if bullet.on_hit():
                        bullet.kill()
                    break

    def _enemy_bullets_vs_player(self, ctx):
        player = self.player
        px, py = player.x, player.y
        hit_radius = player.radius
        graze_radius = player.graze_radius
        for bullet in self.enemy_bullets:
            if not bullet.alive:
                continue
            d = distance(px, py, bullet.x, bullet.y)
            if d <= hit_radius + bullet.radius:
                direction = (px - bullet.x, py - bullet.y)
                if player.take_hit(ctx, direction):
                    self.on_player_hit(ctx)
                bullet.kill()
            elif not bullet.grazed and d <= graze_radius + bullet.radius:
                # Close pass: the risk-reward core of the scoring system.
                bullet.grazed = True
                player.add_graze(ctx)
                self.stats.graze_total += 1
                self.stats.max_graze_streak = max(self.stats.max_graze_streak,
                                                  player.graze_streak)
                self.score += config.GRAZE_SCORE

    def _lasers_vs_player(self, ctx):
        for laser in self.lasers:
            if laser.hits_point(self.player.x, self.player.y, self.player.radius):
                if self.player.take_hit(ctx, (0.0, 1.0)):
                    self.on_player_hit(ctx)

    def _enemies_vs_player(self, ctx):
        player_rect = pygame.Rect(int(self.player.x - self.player.radius),
                                  int(self.player.y - self.player.radius),
                                  int(self.player.radius * 2), int(self.player.radius * 2))
        for enemy in self.enemies:
            if not enemy.alive:
                continue
            if enemy.rect.colliderect(player_rect):
                direction = (self.player.x - enemy.x, self.player.y - enemy.y)
                if self.player.take_hit(ctx, direction):
                    self.on_player_hit(ctx)
                if enemy.take_damage(2, ctx):
                    self.register_kill(enemy, ctx)

    def _pickups_vs_player(self, ctx):
        sprite = self.player.sprite_rect.inflate(10, 10)
        for pickup in self.pickups:
            if not pickup.alive:
                continue
            if sprite.colliderect(pickup.rect):
                if pickup.kind == "SCORE":
                    self.score += pickup.value
                else:
                    pickup.apply(self.player, ctx)
                pickup.kill()

    def on_player_hit(self, ctx):
        self.stats.hits_taken += 1
        self.combo = 0
        self.wave_damage_taken = True
        self.last_hit_t = ctx.clock.t

    # -- draw ------------------------------------------------------------

    def draw(self, surface):
        for pickup in self.pickups:
            pickup.draw(surface)
        for enemy in self.enemies:
            enemy.draw(surface)
        if self.boss is not None:
            self.boss.draw(surface)
        if self.player.alive:
            self.player.draw(surface)
        for bullet in self.player_bullets:
            bullet.draw(surface)
        for laser in self.lasers:
            laser.draw(surface)
        # Enemy bullets draw last of the world layers: the player must never
        # lose one behind a sprite.
        for bullet in self.enemy_bullets:
            bullet.draw(surface)
