"""Enemy archetypes, built from composable movement and weapon parts.

One ``Enemy`` class plus a data table beats nine subclasses: adding an enemy is
adding a row, and the director can read cost/unlock values straight off the
table to decide what to spawn.

Every weapon component carries a telegraph. That is an invariant, not a
preference -- an attack that fires without warning reads as unfair.
"""

import math

import pygame

from .. import config, patterns, rng, theme
from .base import Entity
from .bullets import EnemyBullet


# ---------- movement components ----------

class Descend:
    """Straight down at a fixed pace."""

    def __init__(self, speed=1.0, drift=0.0):
        self.speed = speed
        self.drift = drift

    def step(self, enemy, ctx):
        enemy.vy = config.ENEMY_BASE_SPEED * self.speed * enemy.speed_scale
        enemy.vx = self.drift * config.ENEMY_BASE_SPEED * enemy.speed_scale
        enemy.integrate(ctx.clock.dts)


class SineDrift:
    """Weaves across the screen while descending -- readable, rhythmic."""

    def __init__(self, amplitude=90.0, period_ms=1800.0, speed=0.8):
        self.amplitude = amplitude
        self.period_ms = period_ms
        self.speed = speed
        self.origin_x = None

    def step(self, enemy, ctx):
        if self.origin_x is None:
            self.origin_x = enemy.x
        enemy.vy = config.ENEMY_BASE_SPEED * self.speed * enemy.speed_scale
        phase = (ctx.clock.t - enemy.spawn_t) / self.period_ms * math.tau
        enemy.x = self.origin_x + math.sin(phase) * self.amplitude
        enemy.y += enemy.vy * ctx.clock.dts
        enemy.x = max(12.0, min(config.WIDTH - 12.0, enemy.x))


class HoverThenCharge:
    """Descend, hold with a visible tell, then dive at the player's lane."""

    def __init__(self, hold_y=220.0, hold_ms=900.0, charge_speed=3.4):
        self.hold_y = hold_y
        self.hold_ms = hold_ms
        self.charge_speed = charge_speed
        self.state = "approach"
        self.hold_until = 0.0
        self.aim_x = None

    def step(self, enemy, ctx):
        dts = ctx.clock.dts
        if self.state == "approach":
            enemy.y += config.ENEMY_BASE_SPEED * 1.1 * enemy.speed_scale * dts
            if enemy.y >= self.hold_y:
                self.state = "hold"
                self.hold_until = ctx.clock.t + self.hold_ms
                enemy.telegraph = 1.0
        elif self.state == "hold":
            if ctx.player is not None:
                self.aim_x = ctx.player.x
                enemy.x += (self.aim_x - enemy.x) * min(1.0, dts * 2.2)
            enemy.telegraph = max(0.0, (self.hold_until - ctx.clock.t) / self.hold_ms)
            if ctx.clock.t >= self.hold_until:
                self.state = "charge"
                enemy.telegraph = 0.0
        else:
            enemy.y += config.ENEMY_BASE_SPEED * self.charge_speed * enemy.speed_scale * dts


class ParkAndStay:
    """Take up a position and hold it -- turrets that own a piece of the screen."""

    def __init__(self, stop_y=150.0, stay_ms=7000.0):
        self.stop_y = stop_y
        self.stay_ms = stay_ms
        self.parked_at = None

    def step(self, enemy, ctx):
        dts = ctx.clock.dts
        if enemy.y < self.stop_y:
            enemy.y += config.ENEMY_BASE_SPEED * 0.9 * enemy.speed_scale * dts
            return
        if self.parked_at is None:
            self.parked_at = ctx.clock.t
        if ctx.clock.t - self.parked_at > self.stay_ms:
            enemy.y += config.ENEMY_BASE_SPEED * 1.4 * enemy.speed_scale * dts


class Strafe:
    """Slide along the top of the playfield, bouncing at the edges."""

    def __init__(self, stop_y=110.0, speed=1.2):
        self.stop_y = stop_y
        self.speed = speed
        self.direction = 0

    def step(self, enemy, ctx):
        dts = ctx.clock.dts
        if self.direction == 0:
            self.direction = -1 if enemy.x > config.WIDTH * 0.5 else 1
        if enemy.y < self.stop_y:
            enemy.y += config.ENEMY_BASE_SPEED * enemy.speed_scale * dts
            return
        enemy.x += self.direction * config.ENEMY_BASE_SPEED * self.speed * enemy.speed_scale * dts
        if enemy.x < 30.0:
            enemy.x, self.direction = 30.0, 1
        elif enemy.x > config.WIDTH - 30.0:
            enemy.x, self.direction = config.WIDTH - 30.0, -1


class HomingDrift:
    """Slow, relentless pursuit -- punishes standing still."""

    def __init__(self, speed=0.85, turn_rate=1.1):
        self.speed = speed
        self.turn_rate = turn_rate

    def step(self, enemy, ctx):
        dts = ctx.clock.dts
        speed = config.ENEMY_BASE_SPEED * self.speed * enemy.speed_scale
        if ctx.player is not None and ctx.player.alive:
            if enemy.vx == 0.0 and enemy.vy == 0.0:
                enemy.vy = speed
            enemy.vx, enemy.vy = patterns.homing_turn(
                enemy.vx, enemy.vy, ctx.player.x - enemy.x, ctx.player.y - enemy.y,
                speed, self.turn_rate * dts)
        else:
            enemy.vy = speed
        enemy.integrate(dts)


class EnterFromSide:
    """Fly in from an edge, then settle into a descent."""

    def __init__(self, speed=1.5):
        self.speed = speed
        self.target_x = None

    def step(self, enemy, ctx):
        dts = ctx.clock.dts
        if self.target_x is None:
            self.target_x = (config.WIDTH * 0.3 if enemy.x < 0 else config.WIDTH * 0.7)
        if abs(enemy.x - self.target_x) > 6.0:
            direction = 1.0 if self.target_x > enemy.x else -1.0
            enemy.x += direction * config.ENEMY_BASE_SPEED * self.speed * enemy.speed_scale * dts
        enemy.y += config.ENEMY_BASE_SPEED * 0.45 * enemy.speed_scale * dts


# ---------- weapon components ----------

class NoWeapon:
    telegraphing = False

    def step(self, enemy, ctx):
        return

    def draw_telegraph(self, enemy, surface):
        return


class _TelegraphedWeapon:
    """Base for anything that shoots. Enforces a visible warning window."""

    def __init__(self, interval_ms, telegraph_ms):
        self.interval_ms = interval_ms
        self.telegraph_ms = max(config.TELEGRAPH_MIN_MS, telegraph_ms)
        self.next_fire = None

    def step(self, enemy, ctx):
        if self.next_fire is None:
            self.next_fire = ctx.clock.t + self.interval_ms * rng.uniform(0.6, 1.2)
            return
        if enemy.y < 0:
            return
        remaining = self.next_fire - ctx.clock.t
        self.telegraphing = 0.0 < remaining <= self.telegraph_ms
        if remaining <= 0.0:
            self.fire(enemy, ctx)
            self.telegraphing = False
            self.next_fire = ctx.clock.t + self.interval_ms

    def fire(self, enemy, ctx):  # pragma: no cover - overridden
        raise NotImplementedError

    def draw_telegraph(self, enemy, surface):
        if not getattr(self, "telegraphing", False):
            return
        pygame.draw.circle(surface, theme.color(config.COLOR_ENEMY_AIMED),
                           (int(enemy.x), int(enemy.y)), int(enemy.height * 0.75), 1)


class AimedShot(_TelegraphedWeapon):
    def __init__(self, interval_ms=1900.0, telegraph_ms=380.0, count=1, arc=0.0):
        super().__init__(interval_ms, telegraph_ms)
        self.count = count
        self.arc = arc

    def fire(self, enemy, ctx):
        if ctx.player is None:
            return
        shots = patterns.burst_at(enemy.x, enemy.y, ctx.player.x, ctx.player.y,
                                  self.count, self.arc, config.ENEMY_BULLET_SPEED)
        for vx, vy in shots:
            ctx.world.add_enemy_bullet(EnemyBullet(
                enemy.x, enemy.y, vx, vy, config.COLOR_ENEMY_AIMED, "dart", 6.0))
        ctx.audio.play("shoot_spread", 0.4)

    def draw_telegraph(self, enemy, surface):
        if not getattr(self, "telegraphing", False):
            return
        color = theme.color(config.COLOR_ENEMY_AIMED)
        pygame.draw.circle(surface, color, (int(enemy.x), int(enemy.y)),
                           int(enemy.height * 0.8), 1)
        pygame.draw.polygon(surface, color, [
            (enemy.x, enemy.y + enemy.height * 0.8),
            (enemy.x - 5, enemy.y + enemy.height * 0.5),
            (enemy.x + 5, enemy.y + enemy.height * 0.5)])


class FanShot(_TelegraphedWeapon):
    def __init__(self, count=3, arc=0.7, interval_ms=2200.0, telegraph_ms=460.0):
        super().__init__(interval_ms, telegraph_ms)
        self.count = count
        self.arc = arc

    def fire(self, enemy, ctx):
        for vx, vy in patterns.fan(math.pi / 2, self.count, self.arc,
                                   config.ENEMY_BULLET_SPEED * 0.9):
            ctx.world.add_enemy_bullet(EnemyBullet(
                enemy.x, enemy.y, vx, vy, config.COLOR_ENEMY_SPRAY, "orb", 7.0))
        ctx.audio.play("shoot_spread", 0.45)


class RingShot(_TelegraphedWeapon):
    def __init__(self, count=10, interval_ms=2600.0, telegraph_ms=500.0):
        super().__init__(interval_ms, telegraph_ms)
        self.count = count

    def fire(self, enemy, ctx):
        for vx, vy in patterns.ring(self.count, config.ENEMY_BULLET_SPEED * 0.8,
                                    rng.uniform(0.0, 0.6)):
            ctx.world.add_enemy_bullet(EnemyBullet(
                enemy.x, enemy.y, vx, vy, config.COLOR_ENEMY_SPRAY, "orb", 6.0))
        ctx.audio.play("shoot_spread", 0.5)


class SpiralShot:
    """A continuous rotating stream: dense but perfectly predictable."""

    telegraphing = False

    def __init__(self, shot_interval_ms=150.0, per_turn=13, telegraph_ms=600.0):
        self.shot_interval_ms = shot_interval_ms
        self.per_turn = per_turn
        self.telegraph_ms = telegraph_ms
        self.index = 0
        self.next_shot = None
        self.armed_at = None

    def step(self, enemy, ctx):
        if self.armed_at is None:
            self.armed_at = ctx.clock.t + self.telegraph_ms
            return
        if ctx.clock.t < self.armed_at:
            self.telegraphing = True
            return
        self.telegraphing = False
        if self.next_shot is None:
            self.next_shot = ctx.clock.t
        if ctx.clock.t >= self.next_shot and enemy.y > 0:
            self.next_shot = ctx.clock.t + self.shot_interval_ms
            vx, vy = patterns.spiral(self.index, self.per_turn,
                                     config.ENEMY_BULLET_SPEED * 0.7)
            self.index += 1
            ctx.world.add_enemy_bullet(EnemyBullet(
                enemy.x, enemy.y, vx, vy, config.COLOR_ENEMY_SPRAY, "spiral", 6.0))

    def draw_telegraph(self, enemy, surface):
        if not self.telegraphing:
            return
        pygame.draw.circle(surface, theme.color(config.COLOR_ENEMY_AIMED),
                           (int(enemy.x), int(enemy.y)), int(enemy.height), 1)


class DropMine(_TelegraphedWeapon):
    def __init__(self, interval_ms=2000.0, telegraph_ms=340.0, fuse_ms=2600.0):
        super().__init__(interval_ms, telegraph_ms)
        self.fuse_ms = fuse_ms

    def fire(self, enemy, ctx):
        ctx.world.add_enemy_bullet(EnemyBullet(
            enemy.x, enemy.y + enemy.height * 0.5, 0.0, config.ENEMY_BULLET_SPEED * 0.35,
            config.COLOR_ENEMY_SPRAY, "mine", 9.0, fuse_ms=self.fuse_ms))


# ---------- archetypes ----------

class Archetype:
    def __init__(self, key, hp, width, height, score, cost, unlock, shape,
                 color_role, movement, weapon, speed=1.0, drop_weight=1.0):
        self.key = key
        self.hp = hp
        self.width = width
        self.height = height
        self.score = score
        self.cost = cost
        self.unlock = unlock          # minimum director intensity before it appears
        self.shape = shape
        self.color_role = color_role
        self.movement = movement      # zero-arg factory
        self.weapon = weapon          # zero-arg factory
        self.speed = speed
        self.drop_weight = drop_weight


ARCHETYPES = {
    "DRONE": Archetype("DRONE", 1, 26, 20, 10, 1.0, 0.0, "box",
                       config.COLOR_ENEMY_BODY, lambda: Descend(1.0), NoWeapon, drop_weight=1.4),
    "WEAVER": Archetype("WEAVER", 2, 24, 18, 20, 2.0, 1.0, "diamond",
                        config.COLOR_ENEMY_BODY, lambda: SineDrift(), lambda: AimedShot()),
    "DARTER": Archetype("DARTER", 2, 20, 22, 25, 3.0, 2.0, "wedge",
                        config.COLOR_ENEMY_BODY, lambda: HoverThenCharge(), NoWeapon),
    "SEEKER": Archetype("SEEKER", 2, 18, 18, 25, 3.0, 3.0, "seeker",
                        config.COLOR_ENEMY_BODY, lambda: HomingDrift(), NoWeapon),
    "SPLITTER": Archetype("SPLITTER", 3, 30, 26, 30, 4.0, 3.0, "split",
                          config.COLOR_ENEMY_BODY, lambda: Descend(0.8), NoWeapon),
    "MINELAYER": Archetype("MINELAYER", 3, 28, 20, 35, 4.0, 3.5, "hex",
                           config.COLOR_ENEMY_TOUGH, lambda: Strafe(), lambda: DropMine()),
    "TANK": Archetype("TANK", 6, 34, 26, 40, 5.0, 2.5, "wide",
                      config.COLOR_ENEMY_TOUGH, lambda: Descend(0.55), lambda: FanShot()),
    "SHIELDER": Archetype("SHIELDER", 3, 26, 20, 30, 5.0, 4.5, "shield",
                          config.COLOR_ENEMY_TOUGH, lambda: Descend(0.7), NoWeapon),
    "TURRET": Archetype("TURRET", 4, 30, 18, 35, 6.0, 4.0, "wide",
                        config.COLOR_ENEMY_TOUGH, lambda: ParkAndStay(), lambda: SpiralShot()),
}

ARCHETYPE_KEYS = list(ARCHETYPES.keys())


def available(intensity):
    """Archetypes unlocked at this intensity -- the roster widens as a run goes on."""
    return [key for key, arch in ARCHETYPES.items() if arch.unlock <= intensity]


# ---------- the enemy ----------

class Enemy(Entity):
    def __init__(self, key, x, y, speed_scale=1.0, elite=False):
        arch = ARCHETYPES[key]
        super().__init__(x, y, radius=max(arch.width, arch.height) * 0.5)
        self.arch = arch
        self.key = key
        self.width = arch.width
        self.height = arch.height
        self.elite = elite
        self.max_hp = max(1, int(round(arch.hp * (1.0 + config.ELITE_HP_BONUS if elite else 1.0))))
        self.hp = self.max_hp
        self.speed_scale = speed_scale
        self.movement = arch.movement()
        self.weapon = arch.weapon()
        self.spawn_t = 0.0
        self.telegraph = 0.0
        self.hit_flash_until = 0.0
        self.shield_hp = 4 if arch.shape == "shield" else 0
        self._t = 0.0
        self._spawned = False

    @property
    def score_value(self):
        return int(self.arch.score * (config.ELITE_SCORE_BONUS if self.elite else 1.0))

    @property
    def rect(self):
        return pygame.Rect(int(self.x - self.width * 0.5), int(self.y - self.height * 0.5),
                           self.width, self.height)

    def update(self, ctx):
        if not self._spawned:
            self.spawn_t = ctx.clock.t
            self._spawned = True
        self._t = ctx.clock.t
        self.movement.step(self, ctx)
        self.weapon.step(self, ctx)
        if self.y > config.HEIGHT + 60 or self.x < -120 or self.x > config.WIDTH + 120:
            self.kill()

    def take_damage(self, amount, ctx, from_dir=None):
        """Apply damage. Returns True if this killed the enemy."""
        if self.shield_hp > 0:
            self.shield_hp -= amount
            ctx.audio.play("tank_hit", 0.5)
            ctx.fx.burst(self.x, self.y + self.height * 0.6, theme.color("CYAN"), 4,
                         direction=-math.pi / 2, speed=(50.0, 160.0), spread=1.4)
            if self.shield_hp <= 0:
                ctx.fx.shockwave(self.x, self.y, 6, 40, 260, theme.color("CYAN"), 2)
            return False

        self.hp -= amount
        self.hit_flash_until = ctx.clock.t + config.ENEMY_HIT_FLASH_MS
        if self.hp > 0:
            ctx.audio.play("tank_hit" if self.arch.cost >= 5 else "enemy_hit", 0.5)
            ctx.fx.burst(self.x, self.y, theme.color("CYAN"), 4, direction=from_dir,
                         speed=(60.0, 190.0), life=(120.0, 280.0), size=(1, 3), spread=1.6)
            return False

        self.kill()
        self.on_death(ctx)
        return True

    def on_death(self, ctx):
        big = self.arch.cost >= 4
        color = theme.color(self.arch.color_role)
        ctx.fx.burst(self.x, self.y, color, 18 if big else 12)
        ctx.audio.play("enemy_kill_big" if big else "enemy_kill_small")
        if big:
            ctx.fx.shockwave(self.x, self.y, 4, 46, 300, color, 2)
            ctx.fx.shake(config.SHAKE_ON_BIG_KILL)
            ctx.fx.hitstop(config.HITSTOP_BIG_KILL_MS)
        else:
            ctx.fx.shake(config.SHAKE_ON_KILL)

        if self.arch.shape == "split":
            # Splitters are drawn with visible segments so this reads as expected.
            for direction in (-1.0, 1.0):
                child = Enemy("DRONE", self.x + direction * 14.0, self.y,
                              self.speed_scale * 1.1)
                child.movement = Descend(1.0, drift=direction * 0.6)
                ctx.world.add_enemy(child)

    # -- draw ------------------------------------------------------------

    def draw(self, surface):
        flashing = self._t < self.hit_flash_until
        color = theme.color("WHITE") if flashing else theme.color(self.arch.color_role)
        outline = theme.color("YELLOW") if self.elite else theme.color(config.COLOR_ENEMY_LASER)
        x, y = self.x, self.y
        hw, hh = self.width * 0.5, self.height * 0.5
        shape = self.arch.shape

        if shape == "box":
            pygame.draw.rect(surface, color, self.rect)
            pygame.draw.rect(surface, outline, self.rect, 2)
        elif shape == "diamond":
            points = [(x, y - hh), (x + hw, y), (x, y + hh), (x - hw, y)]
            pygame.draw.polygon(surface, color, points)
            pygame.draw.polygon(surface, outline, points, 2)
        elif shape == "wedge":
            points = [(x, y + hh), (x - hw, y - hh), (x + hw, y - hh)]
            pygame.draw.polygon(surface, color, points)
            pygame.draw.polygon(surface, outline, points, 2)
        elif shape == "wide":
            pygame.draw.rect(surface, color, self.rect)
            pygame.draw.rect(surface, outline, self.rect, 2)
            pygame.draw.rect(surface, outline, (int(x - hw * 0.4), int(y - hh - 3),
                                                int(hw * 0.8), 4))
        elif shape == "split":
            pygame.draw.rect(surface, color, self.rect)
            pygame.draw.line(surface, outline, (int(x), int(y - hh)), (int(x), int(y + hh)), 2)
            pygame.draw.rect(surface, outline, self.rect, 2)
        elif shape == "hex":
            points = [(x - hw, y), (x - hw * 0.5, y - hh), (x + hw * 0.5, y - hh),
                      (x + hw, y), (x + hw * 0.5, y + hh), (x - hw * 0.5, y + hh)]
            pygame.draw.polygon(surface, color, points)
            pygame.draw.polygon(surface, outline, points, 2)
        elif shape == "seeker":
            pygame.draw.circle(surface, color, (int(x), int(y)), int(hw))
            pygame.draw.circle(surface, outline, (int(x), int(y)), int(hw), 2)
        elif shape == "shield":
            pygame.draw.rect(surface, color, self.rect)
            pygame.draw.rect(surface, outline, self.rect, 2)
            if self.shield_hp > 0:
                arc_rect = pygame.Rect(int(x - hw - 10), int(y - hh), int(self.width + 20),
                                       int(self.height + 26))
                pygame.draw.arc(surface, theme.color("CYAN"), arc_rect, math.pi, math.tau, 3)
        else:
            pygame.draw.rect(surface, color, self.rect)

        if self.elite:
            pygame.draw.rect(surface, theme.color("YELLOW"), self.rect.inflate(6, 6), 1)

        if self.max_hp > 1 and self.hp < self.max_hp:
            width = self.width
            fill = int(width * (self.hp / self.max_hp))
            bar_y = int(y - hh - 7)
            pygame.draw.rect(surface, theme.color("BLACK"), (int(x - hw), bar_y, width, 3))
            pygame.draw.rect(surface, theme.color("RED"), (int(x - hw), bar_y, fill, 3))

        if self.telegraph > 0.0:
            # Charge tell for divers: a line down the lane they're about to take.
            pygame.draw.line(surface, theme.color(config.COLOR_ENEMY_AIMED),
                             (int(x), int(y + hh)), (int(x), config.HEIGHT), 1)
            pygame.draw.circle(surface, theme.color("WHITE"), (int(x), int(y)),
                               int(4 + (1.0 - self.telegraph) * 8), 1)

        self.weapon.draw_telegraph(self, surface)
