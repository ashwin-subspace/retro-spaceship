"""Bosses: a lifecycle state machine wrapped around scripted attack phases.

Lifecycle: WARNING -> ENTERING -> FIGHTING -> PHASE_BREAK -> DYING -> GONE.

Phases swap at HP thresholds and each runs a cyclic script of attacks. Every
attack is telegraphed, and each phase break clears the screen of bullets and
converts them to score -- the classic reward for surviving a phase.
"""

import math

import pygame

from .. import config, patterns, rng, theme
from .base import Entity
from .bullets import EnemyBullet, Laser
from .enemies import Enemy

WARNING_MS = 2500.0
ENTER_MS = 1500.0
PHASE_BREAK_MS = 1200.0
DYING_MS = 2500.0


# ---------- attacks ----------

def aimed_fan(count, arc, speed_scale=1.0):
    def fire(boss, ctx):
        if ctx.player is None:
            return
        for vx, vy in patterns.burst_at(boss.x, boss.y + boss.height * 0.4,
                                        ctx.player.x, ctx.player.y, count, arc,
                                        config.ENEMY_BULLET_SPEED * speed_scale):
            ctx.world.add_enemy_bullet(EnemyBullet(
                boss.x, boss.y + boss.height * 0.4, vx, vy,
                config.COLOR_ENEMY_AIMED, "dart", 7.0))
        ctx.audio.play("shoot_spread", 0.5)
    return fire


def ring_burst(count, speed_scale=0.85):
    def fire(boss, ctx):
        offset = rng.uniform(0.0, 0.5)
        for vx, vy in patterns.ring(count, config.ENEMY_BULLET_SPEED * speed_scale, offset):
            ctx.world.add_enemy_bullet(EnemyBullet(
                boss.x, boss.y, vx, vy, config.COLOR_ENEMY_SPRAY, "orb", 7.0))
        ctx.audio.play("shoot_spread", 0.55)
    return fire


def spiral_stream(per_turn, speed_scale=0.7):
    def fire(boss, ctx):
        vx, vy = patterns.spiral(boss.spiral_index, per_turn,
                                 config.ENEMY_BULLET_SPEED * speed_scale)
        boss.spiral_index += 1
        ctx.world.add_enemy_bullet(EnemyBullet(
            boss.x, boss.y, vx, vy, config.COLOR_ENEMY_SPRAY, "spiral", 6.0))
    return fire


def wall_with_gap(count=11, speed_scale=0.75):
    def fire(boss, ctx):
        gap = rng.randint(1, max(1, count - 2))
        for x, vx, vy in patterns.wall(config.WIDTH, count, gap,
                                       config.ENEMY_BULLET_SPEED * speed_scale, 2):
            ctx.world.add_enemy_bullet(EnemyBullet(
                x, boss.y + boss.height * 0.5, vx, vy,
                config.COLOR_ENEMY_SPRAY, "orb", 7.0))
        ctx.audio.play("shoot_spread", 0.6)
    return fire


def sweep_laser():
    def fire(boss, ctx):
        target_x = ctx.player.x if ctx.player is not None else config.WIDTH * 0.5
        ctx.world.add_laser(Laser(target_x, boss.y + boss.height * 0.5, 26.0,
                                  config.LASER_TELEGRAPH_MS, 900.0))
        ctx.audio.play("shoot_laser", 0.8)
    return fire


def spawn_adds(count, key="DRONE"):
    def fire(boss, ctx):
        for i in range(count):
            x = config.WIDTH * (i + 1) / (count + 1)
            ctx.world.add_enemy(Enemy(key, x, boss.y + 40.0, 1.0))
        ctx.audio.play("wave_start", 0.6)
    return fire


def homing_seeds(count):
    def fire(boss, ctx):
        for vx, vy in patterns.fan(math.pi / 2, count, 1.2,
                                   config.ENEMY_BULLET_SPEED * 0.55):
            ctx.world.add_enemy_bullet(EnemyBullet(
                boss.x, boss.y, vx, vy, config.COLOR_ENEMY_AIMED, "homing", 7.0))
        ctx.audio.play("shoot_homing", 0.6)
    return fire


# ---------- phases ----------

class BossPhase:
    def __init__(self, script, sway=1.0, name=""):
        self.script = script       # list of (fire_fn, times, interval_ms, cooldown_ms)
        self.sway = sway
        self.name = name


class BossArm:
    """A destructible sub-target. Killing arms is how you open the core."""

    def __init__(self, offset_x, hp, width=34, height=30):
        self.offset_x = offset_x
        self.max_hp = hp
        self.hp = hp
        self.width = width
        self.height = height
        self.x = 0.0
        self.y = 0.0
        self.hit_flash_until = 0.0

    @property
    def alive(self):
        return self.hp > 0

    @property
    def rect(self):
        return pygame.Rect(int(self.x - self.width * 0.5), int(self.y - self.height * 0.5),
                           self.width, self.height)


# ---------- boss definitions ----------

def _sentinel_phases():
    return [
        BossPhase([(aimed_fan(5, 0.9), 3, 420.0, 1500.0)], sway=1.0, name="SWEEP"),
        BossPhase([(aimed_fan(5, 1.0), 2, 380.0, 900.0),
                   (spiral_stream(11), 26, 90.0, 1200.0)], sway=1.3, name="SPIRAL"),
        BossPhase([(wall_with_gap(11), 1, 100.0, 1500.0),
                   (aimed_fan(7, 1.2), 2, 320.0, 900.0),
                   (spawn_adds(2), 1, 100.0, 2200.0)], sway=1.6, name="BARRAGE"),
    ]


def _hive_phases():
    return [
        BossPhase([(ring_burst(12), 2, 700.0, 1600.0),
                   (spawn_adds(2), 1, 100.0, 1800.0)], sway=0.5, name="BROOD"),
        BossPhase([(ring_burst(16), 2, 600.0, 1300.0),
                   (homing_seeds(3), 1, 100.0, 1500.0),
                   (spawn_adds(3), 1, 100.0, 1800.0)], sway=0.7, name="SWARM"),
        BossPhase([(ring_burst(18), 3, 520.0, 1100.0),
                   (homing_seeds(5), 1, 100.0, 1200.0),
                   (sweep_laser(), 1, 100.0, 2400.0)], sway=0.9, name="FRENZY"),
    ]


def _warden_phases():
    return [
        BossPhase([(aimed_fan(3, 0.6), 3, 400.0, 1400.0),
                   (wall_with_gap(9), 1, 100.0, 1800.0)], sway=0.8, name="GUARD"),
        BossPhase([(aimed_fan(5, 0.9), 3, 340.0, 1100.0),
                   (spiral_stream(9), 20, 100.0, 1200.0)], sway=1.1, name="EXPOSED"),
        BossPhase([(sweep_laser(), 1, 100.0, 1900.0),
                   (ring_burst(14), 2, 520.0, 1200.0),
                   (aimed_fan(7, 1.1), 2, 300.0, 1000.0)], sway=1.4, name="CORE"),
    ]


BOSSES = [
    {"name": "SENTINEL", "hp": 260, "width": 92, "height": 54, "score": 1500,
     "phases": _sentinel_phases, "arms": 0, "shape": "sentinel"},
    {"name": "HIVE", "hp": 320, "width": 104, "height": 62, "score": 2000,
     "phases": _hive_phases, "arms": 0, "shape": "hive"},
    {"name": "WARDEN", "hp": 300, "width": 84, "height": 50, "score": 2400,
     "phases": _warden_phases, "arms": 2, "shape": "warden"},
]


class Boss(Entity):
    def __init__(self, index=0, stage=1):
        spec = BOSSES[index % len(BOSSES)]
        super().__init__(config.WIDTH * 0.5, -80.0, radius=spec["width"] * 0.5)
        self.spec = spec
        self.name = spec["name"]
        self.width = spec["width"]
        self.height = spec["height"]
        # Later bosses in a run are tougher, so repeats still escalate.
        scale = 1.0 + 0.35 * max(0, (stage // config.BOSS_EVERY_N_STAGES) - 1)
        self.max_hp = int(spec["hp"] * scale)
        self.hp = self.max_hp
        self.score_value = int(spec["score"] * scale)
        self.phases = spec["phases"]()
        self.phase_index = 0
        self.state = "WARNING"
        self.state_until = 0.0
        self.hover_y = 120.0
        self.spiral_index = 0
        self.hit_flash_until = 0.0
        self.arms = []
        if spec["arms"]:
            for i in range(spec["arms"]):
                side = -1 if i % 2 == 0 else 1
                self.arms.append(BossArm(side * (self.width * 0.5 + 14.0), 70))
        self._script_step = 0
        self._shots_left = 0
        self._next_action = 0.0
        self._t = 0.0
        self._started = False
        self._death_next_boom = 0.0
        self.defeated = False

    # -- state -----------------------------------------------------------

    @property
    def phase(self):
        return self.phases[min(self.phase_index, len(self.phases) - 1)]

    @property
    def phase_count(self):
        return len(self.phases)

    @property
    def hp_fraction(self):
        return max(0.0, self.hp / self.max_hp)

    @property
    def core_vulnerable(self):
        """The Warden's core stays sealed until both arms are destroyed."""
        if not self.arms:
            return True
        return not any(arm.alive for arm in self.arms)

    @property
    def active(self):
        return self.state in ("FIGHTING", "PHASE_BREAK")

    @property
    def gone(self):
        return self.state == "GONE"

    def _enter(self, state, ctx, duration=0.0):
        self.state = state
        self.state_until = ctx.clock.t + duration

    # -- update ----------------------------------------------------------

    def update(self, ctx):
        self._t = ctx.clock.t
        if not self._started:
            self._started = True
            self._enter("WARNING", ctx, WARNING_MS)
            ctx.audio.play("boss_warning")
            ctx.audio.play_music("BOSS")

        for arm in self.arms:
            arm.x = self.x + arm.offset_x
            arm.y = self.y + 6.0

        handler = getattr(self, f"_update_{self.state.lower()}", None)
        if handler is not None:
            handler(ctx)

    def _update_warning(self, ctx):
        # Pulse a red edge glow while the siren runs; nothing spawns.
        if int(ctx.clock.t // 220) % 2 == 0:
            ctx.fx.vignette(theme.color("RED"), 240.0, 90)
        if ctx.clock.t >= self.state_until:
            self._enter("ENTERING", ctx, ENTER_MS)

    def _update_entering(self, ctx):
        self.y += (self.hover_y - self.y) * min(1.0, ctx.clock.dts * 2.4)
        if ctx.clock.t >= self.state_until:
            self.y = self.hover_y
            self._enter("FIGHTING", ctx)
            self._begin_phase(ctx)

    def _update_fighting(self, ctx):
        self._sway(ctx)
        self._run_script(ctx)

    def _update_phase_break(self, ctx):
        self._sway(ctx, 0.3)
        if ctx.clock.t >= self.state_until:
            self._enter("FIGHTING", ctx)
            self._begin_phase(ctx)

    def _update_dying(self, ctx):
        self._sway(ctx, 0.2)
        if ctx.clock.t >= self._death_next_boom:
            self._death_next_boom = ctx.clock.t + 190.0
            bx = self.x + rng.uniform(-self.width * 0.5, self.width * 0.5)
            by = self.y + rng.uniform(-self.height * 0.5, self.height * 0.5)
            ctx.fx.burst(bx, by, theme.color(config.COLOR_ENEMY_TOUGH), 20)
            ctx.fx.shockwave(bx, by, 4, 60, 380, theme.color("YELLOW"), 2)
            ctx.fx.shake(6.0)
            ctx.fx.hitstop(60)
            ctx.audio.play("enemy_kill_big")
        if ctx.clock.t >= self.state_until:
            ctx.fx.flash(theme.color("WHITE"), 320, 200)
            ctx.fx.shockwave(self.x, self.y, 10, 320, 700, theme.color("WHITE"), 4)
            ctx.audio.play("boss_death")
            self.state = "GONE"
            self.defeated = True
            self.kill()

    def _sway(self, ctx, scale=1.0):
        sway = self.phase.sway * scale
        span = (config.WIDTH * 0.5) - self.width * 0.5 - 14.0
        self.x = config.WIDTH * 0.5 + math.sin(ctx.clock.t / 1400.0 * sway) * span

    # -- attack script ---------------------------------------------------

    def _begin_phase(self, ctx):
        self._script_step = 0
        self._shots_left = 0
        self._next_action = ctx.clock.t + 400.0

    def _run_script(self, ctx):
        if ctx.clock.t < self._next_action:
            return
        script = self.phase.script
        if not script:
            return
        fire_fn, times, interval, cooldown = script[self._script_step % len(script)]
        if self._shots_left <= 0:
            self._shots_left = times
        fire_fn(self, ctx)
        self._shots_left -= 1
        if self._shots_left > 0:
            self._next_action = ctx.clock.t + interval
        else:
            self._script_step += 1
            self._next_action = ctx.clock.t + cooldown

    # -- damage ----------------------------------------------------------

    def hit_targets(self):
        """Rects a player bullet can strike, most specific first."""
        targets = [(arm.rect, arm) for arm in self.arms if arm.alive]
        targets.append((self.rect, self))
        return targets

    @property
    def rect(self):
        return pygame.Rect(int(self.x - self.width * 0.5), int(self.y - self.height * 0.5),
                           self.width, self.height)

    def damage_target(self, target, amount, ctx):
        """Apply damage to an arm or the core. Returns True if the boss died."""
        if self.state not in ("FIGHTING",):
            return False

        if isinstance(target, BossArm):
            target.hp -= amount
            target.hit_flash_until = ctx.clock.t + config.ENEMY_HIT_FLASH_MS
            ctx.fx.burst(target.x, target.y, theme.color("CYAN"), 4,
                         speed=(60.0, 180.0), size=(1, 3))
            ctx.audio.play("tank_hit", 0.5)
            if target.hp <= 0:
                ctx.fx.burst(target.x, target.y, theme.color(config.COLOR_ENEMY_TOUGH), 24)
                ctx.fx.shockwave(target.x, target.y, 6, 80, 400, theme.color("YELLOW"), 3)
                ctx.fx.shake(10.0)
                ctx.fx.hitstop(config.HITSTOP_BIG_KILL_MS)
                ctx.audio.play("enemy_kill_big")
                if self.core_vulnerable:
                    ctx.fx.popup(self.x, self.y - 40, "CORE EXPOSED",
                                 theme.color("YELLOW"), 17)
            return False

        if not self.core_vulnerable:
            # Armoured: sparks, no damage. Reads clearly as "shoot the arms".
            ctx.fx.burst(self.x, self.y + self.height * 0.4, theme.color("WHITE"), 3,
                         speed=(50.0, 140.0), size=(1, 2))
            ctx.audio.play("tank_hit", 0.3)
            return False

        self.hp -= amount
        self.hit_flash_until = ctx.clock.t + config.ENEMY_HIT_FLASH_MS
        ctx.audio.play("enemy_hit", 0.35)
        ctx.fx.burst(self.x, self.y + self.height * 0.3, theme.color("CYAN"), 3,
                     speed=(50.0, 170.0), size=(1, 3))

        if self.hp <= 0:
            self.hp = 0
            self._enter("DYING", ctx, DYING_MS)
            self._death_next_boom = ctx.clock.t
            ctx.fx.slowmo(config.SLOWMO_DEATH_SCALE, 1200.0)
            ctx.world.clear_enemy_bullets(ctx, to_score=True)
            return True

        # Phase thresholds at 66% and 33%.
        threshold = 1.0 - (self.phase_index + 1) / self.phase_count
        if self.hp_fraction <= threshold and self.phase_index < self.phase_count - 1:
            self.phase_index += 1
            self._enter("PHASE_BREAK", ctx, PHASE_BREAK_MS)
            ctx.fx.flash(theme.color("WHITE"), 220, 150)
            ctx.fx.shockwave(self.x, self.y, 10, 260, 620, theme.color("CYAN"), 4)
            ctx.fx.shake(14.0)
            ctx.fx.hitstop(config.HITSTOP_BOSS_PHASE_MS)
            ctx.fx.popup(config.WIDTH * 0.5, config.HEIGHT * 0.4,
                         f"PHASE {self.phase_index + 1}", theme.color("YELLOW"), 22)
            ctx.audio.play("boss_phase")
            ctx.world.clear_enemy_bullets(ctx, to_score=True)
        return False

    # -- draw ------------------------------------------------------------

    def draw(self, surface):
        if self.state == "WARNING":
            return
        flashing = self._t < self.hit_flash_until
        body = theme.color("WHITE") if flashing else theme.color(config.COLOR_ENEMY_TOUGH)
        outline = theme.color(config.COLOR_ENEMY_LASER)
        rect = self.rect

        if self.state == "DYING" and int(self._t // 90) % 2 == 0:
            body = theme.color("WHITE")

        shape = self.spec["shape"]
        if shape == "hive":
            pygame.draw.ellipse(surface, body, rect)
            pygame.draw.ellipse(surface, outline, rect, 3)
            pygame.draw.circle(surface, outline, (int(self.x), int(self.y)),
                               int(self.height * 0.28), 2)
        elif shape == "warden":
            pygame.draw.rect(surface, body, rect)
            pygame.draw.rect(surface, outline, rect, 3)
            core_color = theme.color("RED") if self.core_vulnerable else theme.color("BLACK")
            pygame.draw.circle(surface, core_color, (int(self.x), int(self.y)), 12)
            pygame.draw.circle(surface, outline, (int(self.x), int(self.y)), 12, 2)
        else:  # sentinel
            points = [(self.x - self.width * 0.5, self.y),
                      (self.x - self.width * 0.3, self.y - self.height * 0.5),
                      (self.x + self.width * 0.3, self.y - self.height * 0.5),
                      (self.x + self.width * 0.5, self.y),
                      (self.x + self.width * 0.25, self.y + self.height * 0.5),
                      (self.x - self.width * 0.25, self.y + self.height * 0.5)]
            pygame.draw.polygon(surface, body, points)
            pygame.draw.polygon(surface, outline, points, 3)
            pygame.draw.circle(surface, theme.color("RED"), (int(self.x), int(self.y)), 8)

        for arm in self.arms:
            if not arm.alive:
                continue
            arm_color = (theme.color("WHITE") if self._t < arm.hit_flash_until
                         else theme.color(config.COLOR_ENEMY_BODY))
            pygame.draw.rect(surface, arm_color, arm.rect)
            pygame.draw.rect(surface, outline, arm.rect, 2)
            fill = int(arm.width * (arm.hp / arm.max_hp))
            pygame.draw.rect(surface, theme.color("RED"),
                             (arm.rect.left, arm.rect.top - 6, fill, 3))
