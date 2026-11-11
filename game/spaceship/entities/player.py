"""The player ship.

The ship sprite is 30x24 but its actual collision circle is 3px at the core and
is drawn on screen, per bullet-hell convention: the player can always see
exactly what can be hit. Focus mode (hold Shift) halves movement speed and
brightens that dot for threading dense patterns.
"""

import math

import pygame

from .. import config, patterns, theme
from .base import Entity, clamp
from .bullets import PlayerBullet

WEAPON_STATS = {
    "SPREAD": {"interval": 300.0, "per_level": -30.0, "damage": 1, "piercing": False},
    "LASER": {"interval": 200.0, "per_level": -20.0, "damage": 1, "piercing": True},
    "HOMING": {"interval": 380.0, "per_level": -34.0, "damage": 2, "piercing": False},
}


class Player(Entity):
    def __init__(self, x=None, y=None):
        super().__init__(
            x if x is not None else config.WIDTH * 0.5,
            y if y is not None else config.HEIGHT - 90.0,
            radius=config.PLAYER_HITBOX_RADIUS,
        )
        self.width = config.PLAYER_SPRITE_W
        self.height = config.PLAYER_SPRITE_H

        self.max_health = config.PLAYER_MAX_HEALTH
        self.health = config.PLAYER_MAX_HEALTH
        self.shield = True
        self.shield_ready_at = 0.0
        self.invincible_until = 0.0

        self.bombs = config.PLAYER_START_BOMBS
        self.max_bombs = config.PLAYER_MAX_BOMBS

        self.weapon = "SPREAD"
        self.weapon_level = 1
        self.next_shot = 0.0

        self.overdrive = 0.0
        self.overdrive_until = 0.0
        self.graze_count = 0
        self.graze_streak = 0
        self.graze_streak_until = 0.0
        self.graze_radius = config.GRAZE_RADIUS
        self.score_bonus = 0.0

        self.focus = False
        self.dragging = False
        self.shots_fired = 0
        self._t = 0.0
        self._muzzle_until = 0.0

    # -- derived state ---------------------------------------------------

    @property
    def sprite_rect(self):
        return pygame.Rect(int(self.x - self.width * 0.5), int(self.y - self.height * 0.5),
                           self.width, self.height)

    @property
    def overdrive_active(self):
        return self._t < self.overdrive_until

    @property
    def overdrive_ready(self):
        return self.overdrive >= config.OVERDRIVE_MAX and not self.overdrive_active

    def is_invincible(self, t=None):
        return (t if t is not None else self._t) < self.invincible_until

    @property
    def fire_interval(self):
        stats = WEAPON_STATS[self.weapon]
        interval = stats["interval"] + stats["per_level"] * (self.weapon_level - 1)
        if self.overdrive_active:
            interval *= config.OVERDRIVE_FIRE_SCALE
        return max(config.FIRE_RATE_MS_MIN, interval)

    # -- movement --------------------------------------------------------

    def move_towards(self, target_x, target_y, dts):
        """Drag-to-move: ease toward the pointer, slower while focusing."""
        lerp = config.PLAYER_DRAG_LERP * (config.PLAYER_FOCUS_FACTOR if self.focus else 1.0)
        factor = 1.0 - (1.0 - lerp) ** max(0.0, dts * 60.0)
        self.x += (target_x - self.x) * factor
        self.y += (target_y - self.y) * factor
        self._clamp()

    def move_keys(self, keys, dts):
        speed = config.PLAYER_SPEED * (config.PLAYER_FOCUS_FACTOR if self.focus else 1.0)
        dx = dy = 0.0
        if keys[pygame.K_LEFT] or keys[pygame.K_a]:
            dx -= 1.0
        if keys[pygame.K_RIGHT] or keys[pygame.K_d]:
            dx += 1.0
        if keys[pygame.K_UP] or keys[pygame.K_w]:
            dy -= 1.0
        if keys[pygame.K_DOWN] or keys[pygame.K_s]:
            dy += 1.0
        if dx or dy:
            length = math.hypot(dx, dy) or 1.0
            self.x += dx / length * speed * dts
            self.y += dy / length * speed * dts
        self._clamp()

    def _clamp(self):
        half_w, half_h = self.width * 0.5, self.height * 0.5
        self.x = clamp(self.x, half_w, config.WIDTH - half_w)
        self.y = clamp(self.y, config.HEIGHT * 0.25 + half_h, config.HEIGHT - half_h - 8)

    # -- per-frame -------------------------------------------------------

    def update(self, ctx):
        clock = ctx.clock
        self._t = clock.t

        if self.shield_ready_at and clock.t >= self.shield_ready_at and not self.shield:
            self.shield = True
            self.shield_ready_at = 0.0
            ctx.audio.play("shield_recharge")
            ctx.fx.popup(self.x, self.y - 34, "SHIELD", theme.color("CYAN"), 13)

        if self.graze_streak and clock.t > self.graze_streak_until:
            self.graze_streak = 0

        if self.overdrive_active is False and self.overdrive_until and clock.t >= self.overdrive_until:
            self.overdrive_until = 0.0
            ctx.audio.play("overdrive_end")

        # Engine trail.
        if clock.dt > 0:
            ctx.fx.burst(self.x, self.y + self.height * 0.42,
                         theme.color(config.COLOR_PLAYER), 1,
                         direction=math.pi / 2, speed=(30.0, 90.0),
                         life=(120.0, 260.0), size=(1, 2), spread=0.8)

        self.try_fire(ctx)

    def try_fire(self, ctx):
        if not self.alive or ctx.clock.t < self.next_shot:
            return
        self.next_shot = ctx.clock.t + self.fire_interval
        stats = WEAPON_STATS[self.weapon]
        muzzle_y = self.y - self.height * 0.5
        speed = config.BULLET_SPEED
        damage = stats["damage"]
        piercing = stats["piercing"] or self.overdrive_active

        if self.weapon == "SPREAD":
            for offset in patterns.spread_offsets(self.weapon_level):
                vx, vy = patterns.from_angle(-math.pi / 2 + offset, speed)
                ctx.world.add_player_bullet(
                    PlayerBullet(self.x, muzzle_y, vx, vy, damage, piercing, "SPREAD"))
        elif self.weapon == "LASER":
            columns = 1 + (self.weapon_level >= 3) + (self.weapon_level >= 5)
            spacing = 9.0
            start = -(columns - 1) * 0.5
            for i in range(columns):
                ctx.world.add_player_bullet(
                    PlayerBullet(self.x + (start + i) * spacing, muzzle_y, 0.0, -speed * 1.25,
                                 damage, True, "LASER"))
        else:  # HOMING
            count = 1 + self.weapon_level // 2
            for vx, vy in patterns.fan(-math.pi / 2, count, 0.5, speed * 0.8):
                ctx.world.add_player_bullet(
                    PlayerBullet(self.x, muzzle_y, vx, vy, damage, piercing, "HOMING"))

        self.shots_fired += 1
        self._muzzle_until = ctx.clock.t + 55.0
        ctx.audio.play({"SPREAD": "shoot_spread", "LASER": "shoot_laser",
                        "HOMING": "shoot_homing"}[self.weapon], 0.6)

    # -- events ----------------------------------------------------------

    def add_graze(self, ctx):
        """A bullet passed close to the core without hitting it."""
        self.graze_count += 1
        self.graze_streak += 1
        self.graze_streak_until = ctx.clock.t + 1400.0
        self.overdrive = min(config.OVERDRIVE_MAX, self.overdrive + config.GRAZE_OVERDRIVE)
        ctx.audio.graze_tick(self.graze_streak // 3)
        ctx.fx.burst(self.x, self.y, theme.color("CYAN"), 2,
                     speed=(40.0, 120.0), life=(120.0, 260.0), size=(1, 2))

    def add_kill_charge(self):
        self.overdrive = min(config.OVERDRIVE_MAX, self.overdrive + config.KILL_OVERDRIVE)

    def activate_overdrive(self, ctx):
        if not self.overdrive_ready:
            return False
        self.overdrive = 0.0
        self.overdrive_until = ctx.clock.t + config.OVERDRIVE_DURATION_MS
        ctx.audio.play("overdrive_start")
        ctx.fx.flash(theme.color("CYAN"), 160, 70)
        ctx.fx.shockwave(self.x, self.y, 10, 150, 420, theme.color("CYAN"), 3)
        ctx.fx.popup(self.x, self.y - 46, "OVERDRIVE", theme.color("CYAN"), 18)
        return True

    def use_bomb(self, ctx):
        if self.bombs <= 0 or not self.alive:
            return False
        self.bombs -= 1
        self.invincible_until = max(self.invincible_until,
                                    ctx.clock.t + config.BOMB_INVINCIBILITY_MS)
        return True

    def take_hit(self, ctx, from_dir=None):
        """Returns True if damage actually landed."""
        if self.is_invincible(ctx.clock.t) or not self.alive:
            return False
        self.invincible_until = ctx.clock.t + config.INVINCIBILITY_MS
        self.overdrive = max(0.0, self.overdrive * 0.5)
        self.graze_streak = 0

        if self.shield:
            # The shield eats one hit and comes back if you stay clean.
            self.shield = False
            self.shield_ready_at = ctx.clock.t + config.SHIELD_RECHARGE_MS
            ctx.audio.play("shield_break")
            ctx.fx.shockwave(self.x, self.y, 8, 62, 300, theme.color("CYAN"), 2)
            ctx.fx.shake(config.SHAKE_ON_HIT * 0.6, from_dir)
            ctx.fx.hitstop(config.HITSTOP_PLAYER_HIT_MS * 0.6)
            return True

        self.health -= 1
        ctx.audio.play("player_hit")
        ctx.fx.shake(config.SHAKE_ON_HIT, from_dir)
        ctx.fx.hitstop(config.HITSTOP_PLAYER_HIT_MS)
        ctx.fx.flash(theme.color("RED"), 140, 90)
        ctx.fx.burst(self.x, self.y, theme.color(config.COLOR_PLAYER), 22)
        if self.health <= 0:
            self.alive = False
        return True

    def heal(self, amount=1):
        self.health = min(self.max_health, self.health + amount)

    def give_weapon(self, kind, ctx):
        """Same type levels up; a different type swaps at a one-level cost."""
        if kind == self.weapon:
            if self.weapon_level < config.WEAPON_MAX_LEVEL:
                self.weapon_level += 1
                ctx.fx.popup(self.x, self.y - 40, f"{kind} L{self.weapon_level}",
                             theme.color("CYAN"), 14)
            else:
                ctx.fx.popup(self.x, self.y - 40, "MAX", theme.color("YELLOW"), 14)
            ctx.audio.play("pickup_weapon")
        else:
            self.weapon = kind
            self.weapon_level = max(1, self.weapon_level - 1)
            ctx.audio.play("weapon_swap")
            ctx.fx.popup(self.x, self.y - 40, kind, theme.color("CYAN"), 15)

    def give_bomb(self, ctx):
        if self.bombs < self.max_bombs:
            self.bombs += 1
            ctx.fx.popup(self.x, self.y - 40, "+BOMB", theme.color("ORANGE"), 14)
        ctx.audio.play("pickup_bomb")

    # -- draw ------------------------------------------------------------

    def draw(self, surface):
        if not self.alive:
            return
        t = self._t
        flicker = self.is_invincible(t) and int(t // 80) % 2 == 0

        if self.overdrive_active:
            radius = 26 + int(3 * math.sin(t / 90.0))
            pygame.draw.circle(surface, theme.shade(theme.color("CYAN"), 0.5),
                               (int(self.x), int(self.y)), radius, 2)

        if not flicker:
            color = theme.color(config.COLOR_PLAYER)
            half_w, half_h = self.width * 0.5, self.height * 0.5
            top = (self.x, self.y - half_h)
            left = (self.x - half_w, self.y + half_h)
            right = (self.x + half_w, self.y + half_h)
            notch = (self.x, self.y + half_h - 6)
            pygame.draw.polygon(surface, color, [top, left, notch, right])

            if t < self._muzzle_until:
                pygame.draw.rect(surface, theme.color("WHITE"),
                                 (int(self.x - 3), int(self.y - half_h - 6), 6, 7))

            flame = 5 + int(3 * math.sin(t / 40.0))
            pygame.draw.polygon(surface, theme.color(config.COLOR_PLAYER_BULLET), [
                (self.x - 4, self.y + half_h - 2),
                (self.x + 4, self.y + half_h - 2),
                (self.x, self.y + half_h + flame),
            ])

        if self.shield:
            pygame.draw.circle(surface, theme.shade(theme.color("CYAN"), 0.75),
                               (int(self.x), int(self.y)), int(self.width * 0.75), 1)

        # The real hitbox, always visible; bright while focusing.
        core = theme.color("WHITE") if self.focus else theme.shade(theme.color("WHITE"), 0.55)
        if self.focus:
            pygame.draw.circle(surface, theme.color("RED"),
                               (int(self.x), int(self.y)), int(self.graze_radius), 1)
        pygame.draw.circle(surface, core, (int(self.x), int(self.y)),
                           int(self.radius) + (1 if self.focus else 0))
