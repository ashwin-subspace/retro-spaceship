"""Projectiles.

Enemy bullets collide against the player's tiny 3px core using circle maths,
not rects -- that precision is exactly what makes a dense screen feel fair
rather than cheap. Player bullets get the opposite treatment: an inflated
hitbox, so near-misses count as hits.
"""

import math

import pygame

from .. import config, patterns, platform_api, theme
from .base import Entity


class PlayerBullet(Entity):
    __slots__ = ("damage", "piercing", "kind", "hits", "trail_y")

    def __init__(self, x, y, vx, vy, damage=1, piercing=False, kind="SPREAD"):
        super().__init__(x, y, vx, vy, radius=4.0)
        self.damage = damage
        self.piercing = piercing
        self.kind = kind
        self.hits = 0
        self.trail_y = y

    @property
    def rect(self):
        if self.kind == "LASER":
            return pygame.Rect(int(self.x - 3), int(self.y - 12), 6, 24)
        r = self.radius + config.PLAYER_BULLET_INFLATE
        return pygame.Rect(int(self.x - r), int(self.y - r), int(r * 2), int(r * 2))

    def update(self, ctx):
        dts = ctx.clock.dts
        self.trail_y = self.y
        if self.kind == "HOMING":
            target = ctx.world.nearest_enemy(self.x, self.y)
            if target is not None:
                speed = math.hypot(self.vx, self.vy) or config.BULLET_SPEED
                self.vx, self.vy = patterns.homing_turn(
                    self.vx, self.vy, target.x - self.x, target.y - self.y,
                    speed, 4.5 * dts)
        self.integrate(dts)
        if self.offscreen(30.0):
            self.kill()

    def on_hit(self):
        """Returns True if the bullet should be removed."""
        self.hits += 1
        if not self.piercing:
            return True
        return self.hits >= 4

    def draw(self, surface):
        color = theme.color(config.COLOR_PLAYER_BULLET)
        if self.kind == "LASER":
            pygame.draw.rect(surface, color, (int(self.x - 2), int(self.y - 13), 4, 26))
            pygame.draw.rect(surface, theme.color("WHITE"),
                             (int(self.x - 1), int(self.y - 13), 2, 26))
        elif self.kind == "HOMING":
            pygame.draw.circle(surface, color, (int(self.x), int(self.y)), 4)
            pygame.draw.circle(surface, theme.color("WHITE"), (int(self.x), int(self.y)), 2)
        else:
            pygame.draw.rect(surface, color, (int(self.x - 2), int(self.y - 7), 4, 14))


class EnemyBullet(Entity):
    """A single enemy projectile.

    ``kind`` drives both visuals and behaviour: ``mine`` bullets sit still and
    detonate into a ring, ``spiral``/``homing`` bullets carry trails so their
    unusual paths stay readable.
    """

    def __init__(self, x, y, vx, vy, color_role=None, kind="orb", radius=None,
                 fuse_ms=0.0, damage=1):
        super().__init__(x, y, vx, vy,
                         radius=max(config.ENEMY_BULLET_MIN_RADIUS, radius or 6.0))
        self.color_role = color_role or config.COLOR_ENEMY_SPRAY
        self.kind = kind
        self.grazed = False
        self.damage = damage
        self.fuse_ms = fuse_ms
        self.age = 0.0
        self.trail = []

    def update(self, ctx):
        dt = ctx.clock.dt
        dts = ctx.clock.dts
        self.age += dt

        if self.kind == "mine":
            # Mines drift to a halt, pulse, then burst into a ring.
            self.vx *= 0.94 ** (dts * 60.0)
            self.vy *= 0.94 ** (dts * 60.0)
            self.integrate(dts)
            if self.age >= self.fuse_ms:
                self.detonate(ctx)
            return

        if self.kind in ("spiral", "homing"):
            self.trail.append((self.x, self.y))
            while len(self.trail) > platform_api.trail_length():
                self.trail.pop(0)
        if self.kind == "homing" and ctx.player is not None and ctx.player.alive:
            speed = math.hypot(self.vx, self.vy) or config.ENEMY_BULLET_SPEED
            self.vx, self.vy = patterns.homing_turn(
                self.vx, self.vy, ctx.player.x - self.x, ctx.player.y - self.y,
                speed, 1.4 * dts)

        self.integrate(dts)
        if self.offscreen(40.0):
            self.kill()

    def detonate(self, ctx):
        """Burst into a readable ring -- the mine's whole purpose."""
        self.kill()
        for vx, vy in patterns.ring(8, config.ENEMY_BULLET_SPEED * 0.85):
            ctx.world.add_enemy_bullet(
                EnemyBullet(self.x, self.y, vx, vy, config.COLOR_ENEMY_SPRAY, "orb"))
        ctx.fx.burst(self.x, self.y, theme.color(config.COLOR_ENEMY_SPRAY), 10)
        ctx.fx.shake(2.0)
        ctx.audio.play("enemy_kill_small")

    def draw(self, surface):
        color = theme.color(self.color_role)
        x, y = int(self.x), int(self.y)
        if self.trail:
            for i, (tx, ty) in enumerate(self.trail):
                fade = (i + 1) / (len(self.trail) + 1)
                pygame.draw.circle(surface, theme.shade(color, fade * 0.5),
                                   (int(tx), int(ty)), max(1, int(self.radius * fade * 0.6)))
        if self.kind == "mine":
            # Pulse faster as the fuse burns down: the telegraph is the timer.
            remaining = max(0.0, 1.0 - (self.age / max(1.0, self.fuse_ms)))
            blink = (self.age // max(60.0, 220.0 * remaining)) % 2 == 0
            pygame.draw.circle(surface, color, (x, y), int(self.radius), 2)
            if blink:
                pygame.draw.circle(surface, theme.color("WHITE"), (x, y), max(2, int(self.radius * 0.5)))
            return
        if self.kind == "dart":
            angle = math.atan2(self.vy, self.vx)
            tip = (x + math.cos(angle) * self.radius * 1.8, y + math.sin(angle) * self.radius * 1.8)
            left = (x + math.cos(angle + 2.4) * self.radius, y + math.sin(angle + 2.4) * self.radius)
            right = (x + math.cos(angle - 2.4) * self.radius, y + math.sin(angle - 2.4) * self.radius)
            pygame.draw.polygon(surface, color, [tip, left, right])
            return
        # Bright core inside a darker halo keeps bullets legible over stars.
        pygame.draw.circle(surface, theme.shade(color, 0.55), (x, y), int(self.radius))
        pygame.draw.circle(surface, color, (x, y), max(2, int(self.radius * 0.62)))
        pygame.draw.circle(surface, theme.color("WHITE"), (x, y), max(1, int(self.radius * 0.28)))


class Laser(Entity):
    """A telegraphed vertical beam: warning line first, then the column."""

    def __init__(self, x, y, width, telegraph_ms, duration_ms, damage=1):
        super().__init__(x, y, 0.0, 0.0, radius=width * 0.5)
        self.width = width
        self.telegraph_ms = telegraph_ms
        self.duration_ms = duration_ms
        self.age = 0.0

    @property
    def firing(self):
        return self.telegraph_ms <= self.age < self.telegraph_ms + self.duration_ms

    @property
    def rect(self):
        if not self.firing:
            return pygame.Rect(0, 0, 0, 0)
        return pygame.Rect(int(self.x - self.width * 0.5), int(self.y),
                           int(self.width), config.HEIGHT)

    def hits_point(self, px, py, pr):
        if not self.firing:
            return False
        half = self.width * 0.5 + pr
        return abs(px - self.x) <= half and py >= self.y

    def update(self, ctx):
        self.age += ctx.clock.dt
        if self.age >= self.telegraph_ms + self.duration_ms:
            self.kill()

    def draw(self, surface):
        color = theme.color(config.COLOR_ENEMY_LASER)
        if self.age < self.telegraph_ms:
            # A 1px warning line for the full charge time. Never fire unannounced.
            pygame.draw.line(surface, theme.color("WHITE"),
                             (int(self.x), int(self.y)), (int(self.x), config.HEIGHT), 1)
            charge = self.age / max(1.0, self.telegraph_ms)
            pygame.draw.circle(surface, color, (int(self.x), int(self.y)),
                               int(4 + charge * 10), 2)
            return
        progress = (self.age - self.telegraph_ms) / max(1.0, self.duration_ms)
        width = int(self.width * (1.0 - abs(progress - 0.5) * 0.4))
        pygame.draw.rect(surface, theme.shade(color, 0.6),
                         (int(self.x - width * 0.5), int(self.y), width, config.HEIGHT))
        pygame.draw.rect(surface, theme.color("WHITE"),
                         (int(self.x - width * 0.18), int(self.y), max(2, int(width * 0.36)),
                          config.HEIGHT))
