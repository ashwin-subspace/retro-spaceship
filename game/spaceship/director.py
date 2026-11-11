"""The difficulty director.

Difficulty is never player-selectable: this module *is* the difficulty setting.
It works on two axes:

* a **monotone floor** that rises with the stage you've reached, so the game
  always trends harder the longer you survive, and
* a **bounded adaptive offset** (never more than +/-1.5..2.5) that rides on top
  of that floor and reacts to how well you're actually playing.

Because the offset can't invert the floor, the ramp is guaranteed; because it's
bounded and guarded (mercy windows, no adaptation during bosses) it can't spiral
or feel arbitrary.

Enemies are bought from a per-wave *threat budget* using per-archetype costs,
and each archetype has an ``unlock`` intensity, so the roster widens as a run
progresses rather than everything showing up at once.
"""

from . import config, formations, rng
from .entities import enemies as enemies_mod
from .entities.boss import Boss

FLAVOR_BIAS = {
    "STANDARD": {},
    "SWARM": {"DRONE": 3.2, "WEAVER": 1.4, "TANK": 0.2, "TURRET": 0.2, "SHIELDER": 0.2},
    "ARMORED": {"TANK": 3.0, "SHIELDER": 2.6, "TURRET": 1.4, "DRONE": 0.4},
    "BLITZ": {"DARTER": 3.0, "SEEKER": 2.2, "DRONE": 1.2, "TANK": 0.2, "TURRET": 0.2},
    "BARRAGE": {"TURRET": 3.0, "MINELAYER": 2.6, "WEAVER": 1.6, "DRONE": 0.3},
    "AMBUSH": {"SEEKER": 2.4, "DARTER": 2.2, "WEAVER": 1.8, "TANK": 0.3},
    "ELITE": {"TANK": 1.6, "SHIELDER": 1.4, "WEAVER": 1.4, "DRONE": 0.5},
}


class PerformanceTracker:
    """Turns recent play into a single ``skill`` value in 0..1."""

    def __init__(self):
        self.hits_taken = 0
        self.waves_seen = 0
        self.clear_times = []

    def note_wave(self, duration_ms, took_damage):
        self.waves_seen += 1
        self.clear_times.append(duration_ms)
        if len(self.clear_times) > 5:
            self.clear_times.pop(0)
        if took_damage:
            self.hits_taken += 1

    def skill(self, world):
        """Weighted read of how comfortably the player is coping."""
        stats = world.stats
        player = world.player

        health_fraction = player.health / max(1, player.max_health)
        hit_rate = stats.hits_taken / max(1.0, stats.time_ms / 60000.0)  # hits per minute
        hit_score = max(0.0, 1.0 - hit_rate / 6.0)

        accuracy = stats.accuracy
        combo_score = min(1.0, stats.best_combo / 25.0)
        graze_score = min(1.0, stats.graze_total / 120.0)
        weapon_score = min(1.0, (player.weapon_level - 1) / 4.0)

        expected = 12000.0
        if self.clear_times:
            average = sum(self.clear_times) / len(self.clear_times)
            speed_score = max(0.0, min(1.0, expected / max(1000.0, average)))
        else:
            speed_score = 0.5

        score = (hit_score * 0.34 + health_fraction * 0.18 + accuracy * 0.12
                 + combo_score * 0.12 + graze_score * 0.08 + speed_score * 0.10
                 + weapon_score * 0.06)
        return max(0.0, min(1.0, score))


class Director:
    def __init__(self):
        self.stage = 1
        self.wave = 1
        self.wave_in_stage = 1
        self.waves_this_stage = rng.randint(config.WAVES_PER_STAGE_MIN,
                                            config.WAVES_PER_STAGE_MAX)
        self.flavor = "STANDARD"
        self.last_flavor = None
        self.state = "IDLE"
        self.state_until = 0.0
        self.pending = []           # [(spawn_t, key, x, y, elite)]
        self.wave_deadline = 0.0
        self.wave_started_t = 0.0
        self.adaptive_offset = 0.0
        self.tracker = PerformanceTracker()
        self.banner = None
        self.banner_until = 0.0
        self.boss_queued = None
        self.started = False

    # -- intensity -------------------------------------------------------

    def base_ramp(self):
        return config.INTENSITY_MIN + config.RAMP_PER_STAGE * (self.stage - 1)

    @property
    def intensity(self):
        value = self.base_ramp() + self.adaptive_offset
        return max(config.INTENSITY_MIN, min(config.INTENSITY_MAX, value))

    @property
    def speed_scale(self):
        return min(1.75, 1.0 + 0.06 * (self.intensity - 1.0))

    @property
    def elite_chance(self):
        if self.flavor == "ELITE":
            return 0.9
        return min(0.25, 0.02 * self.intensity)

    def _update_adaptation(self, ctx, world):
        # Never adapt while a boss is on screen: bosses are fixed benchmarks.
        if world.boss is not None:
            return
        target_skill = self.tracker.skill(world)
        target = (config.ADAPT_OFFSET_MIN
                  + (config.ADAPT_OFFSET_MAX - config.ADAPT_OFFSET_MIN) * target_skill)

        # Mercy: don't pile on immediately after the player gets hit.
        recently_hit = (ctx.clock.t - world.last_hit_t) < config.MERCY_WINDOW_MS
        if recently_hit and target > self.adaptive_offset:
            return
        if world.player.health <= 1:
            target = min(target, 0.0)

        alpha = config.ADAPT_EMA_PER_SEC * (ctx.clock.dt / 1000.0)
        self.adaptive_offset += (target - self.adaptive_offset) * min(1.0, alpha)
        self.adaptive_offset = max(config.ADAPT_OFFSET_MIN,
                                   min(config.ADAPT_OFFSET_MAX, self.adaptive_offset))

    # -- wave construction ----------------------------------------------

    def pick_flavor(self):
        # Every Nth wave is a deliberate breather, and no flavour repeats twice
        # in a row -- the prototype's plain random.choice could roll RUSH three
        # times running, which reads as the game being broken rather than hard.
        if self.wave % config.FORCED_STANDARD_EVERY == 0:
            return "STANDARD"
        options = [f for f in config.WAVE_FLAVORS if f != self.last_flavor]
        unlocked = [f for f in options
                    if f in ("STANDARD", "SWARM") or self.intensity >= 2.0]
        return rng.choice(unlocked or options)

    def build_wave(self):
        """Spend a threat budget on unlocked archetypes, then arrange them."""
        intensity = self.intensity
        budget = config.THREAT_BUDGET_BASE + config.THREAT_BUDGET_PER_INTENSITY * intensity
        if self.flavor == "ELITE":
            budget *= 0.6

        keys = enemies_mod.available(intensity)
        bias = FLAVOR_BIAS.get(self.flavor, {})
        picks = []
        guard = 0
        while budget > 0 and guard < 60:
            guard += 1
            affordable = [k for k in keys if enemies_mod.ARCHETYPES[k].cost <= budget]
            if not affordable:
                break
            weights = [enemies_mod.ARCHETYPES[k].drop_weight * bias.get(k, 1.0)
                       for k in affordable]
            key = rng.weighted_choice(affordable, weights)
            picks.append(key)
            budget -= enemies_mod.ARCHETYPES[key].cost
        if not picks:
            picks = ["DRONE"] * 3

        formation_name = formations.pick_for(self.flavor)
        points = formations.build(formation_name, len(picks))
        elite_chance = self.elite_chance
        spawns = []
        for key, (x, y, delay) in zip(picks, points):
            spawns.append((delay, key, x, y, rng.chance(elite_chance)))
        return spawns, formation_name

    def start_wave(self, ctx, world):
        self.flavor = self.pick_flavor()
        self.last_flavor = self.flavor
        spawns, formation_name = self.build_wave()
        base_t = ctx.clock.t
        self.pending = [(base_t + delay, key, x, y, elite)
                        for delay, key, x, y, elite in spawns]
        self.wave_deadline = base_t + config.WAVE_MAX_MS
        self.wave_started_t = base_t
        world.wave_damage_taken = False
        world.stats.stage = self.stage
        world.stats.wave = self.wave
        self.state = "ACTIVE"
        label = f"STAGE {self.stage} - WAVE {self.wave_in_stage}"
        if self.flavor != "STANDARD":
            label += f"  {self.flavor}"
        self.show_banner(ctx, label)
        ctx.audio.play("wave_start")

    def show_banner(self, ctx, text):
        self.banner = text
        self.banner_until = ctx.clock.t + config.STAGE_BANNER_MS

    # -- flow ------------------------------------------------------------

    def update(self, ctx, world):
        if not self.started:
            self.started = True
            self.state = "GAP"
            self.state_until = ctx.clock.t + 700.0
            ctx.audio.play_music("BATTLE_A")

        self._update_adaptation(ctx, world)

        if self.banner and ctx.clock.t > self.banner_until:
            self.banner = None

        handler = getattr(self, f"_state_{self.state.lower()}", None)
        if handler is not None:
            handler(ctx, world)

    def _state_gap(self, ctx, world):
        if ctx.clock.t >= self.state_until:
            self.start_wave(ctx, world)

    def _state_active(self, ctx, world):
        now = ctx.clock.t
        if self.pending:
            remaining = []
            for spawn_t, key, x, y, elite in self.pending:
                if now >= spawn_t:
                    world.add_enemy(enemies_mod.Enemy(key, x, y, self.speed_scale, elite))
                else:
                    remaining.append((spawn_t, key, x, y, elite))
            self.pending = remaining

        cleared = not self.pending and not world.enemies
        timed_out = now >= self.wave_deadline
        if cleared or timed_out:
            self.finish_wave(ctx, world, cleared)

    def finish_wave(self, ctx, world, cleared):
        duration = ctx.clock.t - self.wave_started_t
        self.tracker.note_wave(duration, world.wave_damage_taken)

        if cleared and not world.wave_damage_taken:
            # A goal beyond "don't die": clearing untouched is worth chasing.
            world.stats.perfect_waves += 1
            world.award(config.PERFECT_WAVE_BONUS, ctx, config.WIDTH * 0.5,
                        config.HEIGHT * 0.42, popup=False)
            ctx.fx.popup(config.WIDTH * 0.5, config.HEIGHT * 0.42, "PERFECT!",
                         self._accent(), 24)
            ctx.audio.play("perfect")
            if not world.player.shield:
                world.player.shield = True
                world.player.shield_ready_at = 0.0
        elif cleared:
            ctx.audio.play("wave_clear")

        self.wave += 1
        self.wave_in_stage += 1
        if self.wave_in_stage > self.waves_this_stage:
            self.end_stage(ctx, world)
        else:
            self.state = "GAP"
            self.state_until = ctx.clock.t + config.WAVE_GAP_MS

    def end_stage(self, ctx, world):
        self.stage += 1
        self.wave_in_stage = 1
        self.waves_this_stage = rng.randint(config.WAVES_PER_STAGE_MIN,
                                            config.WAVES_PER_STAGE_MAX)
        if (self.stage - 1) % config.BOSS_EVERY_N_STAGES == 0:
            self.begin_boss(ctx, world)
        else:
            if self.stage == 3:
                ctx.audio.play_music("BATTLE_B")
            self.state = "GAP"
            self.state_until = ctx.clock.t + config.WAVE_GAP_MS * 1.5
            self.show_banner(ctx, f"STAGE {self.stage}")
            ctx.audio.play("stage_banner")

    def begin_boss(self, ctx, world, index=None):
        if index is None:
            index = ((self.stage - 1) // config.BOSS_EVERY_N_STAGES - 1)
        # Mark the director started so the lazy first-update init can't reset
        # the state back to GAP and strand the boss (matters when force_boss is
        # called before the first update, as tests do).
        self.started = True
        world.enemy_bullets.clear()
        world.set_boss(Boss(max(0, index), self.stage))
        self.state = "BOSS"
        self.pending = []
        self.show_banner(ctx, "WARNING")

    def _state_boss(self, ctx, world):
        if world.boss is None:
            self.state = "CALM"
            self.state_until = ctx.clock.t + config.BOSS_CALM_MS
            ctx.audio.play_music("BATTLE_B" if self.stage >= 3 else "BATTLE_A")
            self.show_banner(ctx, f"STAGE {self.stage}")

    def _state_calm(self, ctx, world):
        if ctx.clock.t >= self.state_until:
            self.state = "GAP"
            self.state_until = ctx.clock.t + config.WAVE_GAP_MS

    def _accent(self):
        from . import theme
        return theme.color("YELLOW")

    # -- test hooks ------------------------------------------------------

    def force_boss(self, ctx, world, index=0):
        """Documented hook so tests never have to poke internals."""
        self.begin_boss(ctx, world, index)

    def snapshot(self):
        return {
            "stage": self.stage,
            "wave": self.wave,
            "flavor": self.flavor,
            "state": self.state,
            "intensity": round(self.intensity, 3),
            "offset": round(self.adaptive_offset, 3),
            "pending": len(self.pending),
        }
