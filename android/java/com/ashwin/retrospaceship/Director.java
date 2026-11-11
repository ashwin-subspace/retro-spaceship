package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The difficulty director.
 *
 * <p>Difficulty is never player-selectable: this class <em>is</em> the
 * difficulty setting. It works on two axes -- a monotone floor that rises with
 * the stage reached, so the game always trends harder the longer you survive,
 * and a bounded adaptive offset that rides on top and reacts to how well you are
 * actually playing.
 *
 * <p>Because the offset cannot invert the floor, the ramp is guaranteed; because
 * it is bounded and guarded (mercy windows, no adaptation during a boss) it
 * cannot spiral or feel arbitrary.
 *
 * <p>Enemies are bought from a per-wave threat budget using per-archetype costs,
 * and each archetype has an unlock intensity, so the roster widens across a run
 * instead of showing everything at once.
 */
public final class Director {

    private static final Map<String, Map<String, Float>> FLAVOR_BIAS = new HashMap<>();

    static {
        FLAVOR_BIAS.put("STANDARD", new HashMap<>());
        FLAVOR_BIAS.put("SWARM", bias("DRONE", 3.2f, "WEAVER", 1.4f, "TANK", 0.2f,
                "TURRET", 0.2f, "SHIELDER", 0.2f));
        FLAVOR_BIAS.put("ARMORED", bias("TANK", 3f, "SHIELDER", 2.6f, "TURRET", 1.4f,
                "DRONE", 0.4f));
        FLAVOR_BIAS.put("BLITZ", bias("DARTER", 3f, "SEEKER", 2.2f, "DRONE", 1.2f,
                "TANK", 0.2f, "TURRET", 0.2f));
        FLAVOR_BIAS.put("BARRAGE", bias("TURRET", 3f, "MINELAYER", 2.6f, "WEAVER", 1.6f,
                "DRONE", 0.3f));
        FLAVOR_BIAS.put("AMBUSH", bias("SEEKER", 2.4f, "DARTER", 2.2f, "WEAVER", 1.8f,
                "TANK", 0.3f));
        FLAVOR_BIAS.put("ELITE", bias("TANK", 1.6f, "SHIELDER", 1.4f, "WEAVER", 1.4f,
                "DRONE", 0.5f));
    }

    private static Map<String, Float> bias(Object... pairs) {
        Map<String, Float> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Float) pairs[i + 1]);
        }
        return map;
    }

    /** Turns recent play into a single skill value in 0..1. */
    private static final class Tracker {
        final List<Float> clearTimes = new ArrayList<>();

        void noteWave(float durationMs) {
            clearTimes.add(durationMs);
            if (clearTimes.size() > 5) {
                clearTimes.remove(0);
            }
        }

        float skill(World world) {
            World.Stats stats = world.stats;
            Player player = world.player;

            float healthFraction = player.health / (float) Math.max(1, player.maxHealth);
            float hitRate = stats.hitsTaken / (float) Math.max(1.0, stats.timeMs / 60000.0);
            float hitScore = Math.max(0f, 1f - hitRate / 6f);

            float accuracy = stats.accuracy();
            float comboScore = Math.min(1f, stats.bestCombo / 25f);
            float grazeScore = Math.min(1f, stats.grazeTotal / 120f);
            float weaponScore = Math.min(1f, (player.weaponLevel - 1) / 4f);

            float speedScore = 0.5f;
            if (!clearTimes.isEmpty()) {
                float sum = 0f;
                for (float value : clearTimes) {
                    sum += value;
                }
                float average = sum / clearTimes.size();
                speedScore = Math.max(0f, Math.min(1f, 12000f / Math.max(1000f, average)));
            }

            float score = hitScore * 0.34f + healthFraction * 0.18f + accuracy * 0.12f
                    + comboScore * 0.12f + grazeScore * 0.08f + speedScore * 0.10f
                    + weaponScore * 0.06f;
            return Math.max(0f, Math.min(1f, score));
        }
    }

    private static final int IDLE = 0, GAP = 1, ACTIVE = 2, BOSS = 3, CALM = 4;

    /** A queued spawn: what appears, where, and when. */
    private static final class Pending {
        final double at;
        final String key;
        final float x, y;
        final boolean elite;

        Pending(double at, String key, float x, float y, boolean elite) {
            this.at = at;
            this.key = key;
            this.x = x;
            this.y = y;
            this.elite = elite;
        }
    }

    public int stage = 1;
    public int wave = 1;
    private int waveInStage = 1;
    private int wavesThisStage = Rng.randint(Cfg.WAVES_PER_STAGE_MIN, Cfg.WAVES_PER_STAGE_MAX);
    public String flavor = "STANDARD";
    private String lastFlavor = null;
    private int state = IDLE;
    private double stateUntil = 0.0;
    private final List<Pending> pending = new ArrayList<>();
    private double waveDeadline = 0.0;
    private double waveStartedT = 0.0;
    private float adaptiveOffset = 0f;
    private final Tracker tracker = new Tracker();
    public String banner = null;
    public double bannerUntil = 0.0;
    private boolean started = false;
    /** Set for one frame when a boss has just been defeated, for the upgrade screen. */
    public boolean bossJustCleared = false;

    // ---------- intensity ----------

    private float baseRamp() {
        return Cfg.INTENSITY_MIN + Cfg.RAMP_PER_STAGE * (stage - 1);
    }

    public float intensity() {
        return Math.max(Cfg.INTENSITY_MIN,
                Math.min(Cfg.INTENSITY_MAX, baseRamp() + adaptiveOffset));
    }

    private float speedScale() {
        return Math.min(1.75f, 1f + 0.06f * (intensity() - 1f));
    }

    private float eliteChance() {
        if ("ELITE".equals(flavor)) {
            return 0.9f;
        }
        return Math.min(0.25f, 0.02f * intensity());
    }

    private void updateAdaptation(Ctx ctx, World world) {
        // Never adapt while a boss is on screen: bosses are fixed benchmarks,
        // and a boss that got easier because you were struggling is not a boss.
        if (world.boss != null) {
            return;
        }
        float target = Cfg.ADAPT_OFFSET_MIN
                + (Cfg.ADAPT_OFFSET_MAX - Cfg.ADAPT_OFFSET_MIN) * tracker.skill(world);

        boolean recentlyHit = (ctx.clock.t - world.lastHitT) < Cfg.MERCY_WINDOW_MS;
        if (recentlyHit && target > adaptiveOffset) {
            return;
        }
        if (world.player.health <= 1) {
            target = Math.min(target, 0f);
        }
        float alpha = Cfg.ADAPT_EMA_PER_SEC * (ctx.clock.dt / 1000f);
        adaptiveOffset += (target - adaptiveOffset) * Math.min(1f, alpha);
        adaptiveOffset = Math.max(Cfg.ADAPT_OFFSET_MIN,
                Math.min(Cfg.ADAPT_OFFSET_MAX, adaptiveOffset));
    }

    // ---------- wave construction ----------

    private String pickFlavor() {
        // Every Nth wave is a deliberate breather, and no flavour repeats twice
        // running -- a plain random pick can roll BLITZ three times in a row,
        // which reads as the game being broken rather than as being hard.
        if (wave % Cfg.FORCED_STANDARD_EVERY == 0) {
            return "STANDARD";
        }
        List<String> options = new ArrayList<>();
        for (String f : Cfg.WAVE_FLAVORS) {
            if (!f.equals(lastFlavor)) {
                options.add(f);
            }
        }
        List<String> unlocked = new ArrayList<>();
        for (String f : options) {
            if ("STANDARD".equals(f) || "SWARM".equals(f) || intensity() >= 2f) {
                unlocked.add(f);
            }
        }
        return Rng.choice(unlocked.isEmpty() ? options : unlocked);
    }

    /** Spend a threat budget on unlocked archetypes, then arrange them. */
    private List<Pending> buildWave(double now) {
        float budget = Cfg.THREAT_BUDGET_BASE + Cfg.THREAT_BUDGET_PER_INTENSITY * intensity();
        if ("ELITE".equals(flavor)) {
            budget *= 0.6f;
        }
        List<String> keys = Enemy.available(intensity());
        Map<String, Float> bias = FLAVOR_BIAS.get(flavor);
        if (bias == null) {
            bias = FLAVOR_BIAS.get("STANDARD");
        }

        List<String> picks = new ArrayList<>();
        int guard = 0;
        while (budget > 0f && guard < 60) {
            guard++;
            List<String> affordable = new ArrayList<>();
            List<Float> weights = new ArrayList<>();
            for (String key : keys) {
                Enemy.Archetype a = Enemy.ARCHETYPES.get(key);
                if (a.cost <= budget) {
                    affordable.add(key);
                    Float b = bias.get(key);
                    weights.add(a.dropWeight * (b == null ? 1f : b));
                }
            }
            if (affordable.isEmpty()) {
                break;
            }
            String key = Rng.weighted(affordable, weights);
            picks.add(key);
            budget -= Enemy.ARCHETYPES.get(key).cost;
        }
        if (picks.isEmpty()) {
            picks.add("DRONE");
            picks.add("DRONE");
            picks.add("DRONE");
        }

        List<Formations.Point> points = Formations.build(Formations.pickFor(flavor), picks.size());
        float chance = eliteChance();
        List<Pending> spawns = new ArrayList<>(picks.size());
        for (int i = 0; i < picks.size() && i < points.size(); i++) {
            Formations.Point p = points.get(i);
            spawns.add(new Pending(now + p.delayMs, picks.get(i), p.x, p.y, Rng.chance(chance)));
        }
        return spawns;
    }

    private void startWave(Ctx ctx, World world) {
        flavor = pickFlavor();
        lastFlavor = flavor;
        pending.clear();
        pending.addAll(buildWave(ctx.clock.t));
        waveDeadline = ctx.clock.t + Cfg.WAVE_MAX_MS;
        waveStartedT = ctx.clock.t;
        world.waveDamageTaken = false;
        world.stats.stage = stage;
        world.stats.wave = wave;
        state = ACTIVE;
        String label = "STAGE " + stage + " - WAVE " + waveInStage;
        if (!"STANDARD".equals(flavor)) {
            label += "  " + flavor;
        }
        showBanner(ctx, label);
        ctx.sfx.play("wave_start");
    }

    public void showBanner(Ctx ctx, String text) {
        banner = text;
        bannerUntil = ctx.clock.t + Cfg.STAGE_BANNER_MS;
    }

    // ---------- flow ----------

    public void update(Ctx ctx, World world) {
        bossJustCleared = false;
        if (!started) {
            started = true;
            state = GAP;
            stateUntil = ctx.clock.t + 700f;
            ctx.sfx.playMusic(Sfx.BATTLE_A);
        }
        updateAdaptation(ctx, world);
        if (banner != null && ctx.clock.t > bannerUntil) {
            banner = null;
        }

        switch (state) {
            case GAP:
                if (ctx.clock.t >= stateUntil) {
                    startWave(ctx, world);
                }
                break;
            case ACTIVE:
                updateActive(ctx, world);
                break;
            case BOSS:
                if (world.boss == null) {
                    state = CALM;
                    stateUntil = ctx.clock.t + Cfg.BOSS_CALM_MS;
                    ctx.sfx.playMusic(stage >= 3 ? Sfx.BATTLE_B : Sfx.BATTLE_A);
                    showBanner(ctx, "STAGE " + stage);
                    bossJustCleared = true;
                }
                break;
            case CALM:
                if (ctx.clock.t >= stateUntil) {
                    state = GAP;
                    stateUntil = ctx.clock.t + Cfg.WAVE_GAP_MS;
                }
                break;
            default:
                break;
        }
    }

    private void updateActive(Ctx ctx, World world) {
        double now = ctx.clock.t;
        if (!pending.isEmpty()) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                Pending p = pending.get(i);
                if (now >= p.at) {
                    world.addEnemy(new Enemy(p.key, p.x, p.y, speedScale(), p.elite));
                    pending.remove(i);
                }
            }
        }
        boolean cleared = pending.isEmpty() && world.enemies.isEmpty();
        boolean timedOut = now >= waveDeadline;
        if (cleared || timedOut) {
            finishWave(ctx, world, cleared);
        }
    }

    private void finishWave(Ctx ctx, World world, boolean cleared) {
        tracker.noteWave((float) (ctx.clock.t - waveStartedT));

        if (cleared && !world.waveDamageTaken) {
            // A goal beyond "don't die": clearing a wave untouched is worth
            // chasing, and it hands back the shield so the reward compounds.
            world.stats.perfectWaves++;
            world.award(Cfg.PERFECT_WAVE_BONUS, Cfg.W * 0.5f, Cfg.H * 0.42f, false);
            ctx.fx.popup(Cfg.W * 0.5f, Cfg.H * 0.42f, "PERFECT!",
                    Theme.color(Theme.YELLOW), 24f);
            ctx.sfx.play("perfect");
            if (!world.player.shield) {
                world.player.shield = true;
                world.player.shieldReadyAt = 0.0;
            }
        } else if (cleared) {
            ctx.sfx.play("wave_clear");
        }

        wave++;
        waveInStage++;
        if (waveInStage > wavesThisStage) {
            endStage(ctx, world);
        } else {
            state = GAP;
            stateUntil = ctx.clock.t + Cfg.WAVE_GAP_MS;
        }
    }

    private void endStage(Ctx ctx, World world) {
        stage++;
        waveInStage = 1;
        wavesThisStage = Rng.randint(Cfg.WAVES_PER_STAGE_MIN, Cfg.WAVES_PER_STAGE_MAX);
        if ((stage - 1) % Cfg.BOSS_EVERY_N_STAGES == 0) {
            beginBoss(ctx, world, -1);
        } else {
            if (stage == 3) {
                ctx.sfx.playMusic(Sfx.BATTLE_B);
            }
            state = GAP;
            stateUntil = ctx.clock.t + Cfg.WAVE_GAP_MS * 1.5f;
            showBanner(ctx, "STAGE " + stage);
            ctx.sfx.play("stage_banner");
        }
    }

    public void beginBoss(Ctx ctx, World world, int index) {
        int useIndex = index >= 0
                ? index : ((stage - 1) / Cfg.BOSS_EVERY_N_STAGES - 1);
        started = true;
        world.enemyBullets.clear();
        world.setBoss(new Boss(Math.max(0, useIndex), stage));
        state = BOSS;
        pending.clear();
        showBanner(ctx, "WARNING");
    }

    public boolean inBoss() {
        return state == BOSS;
    }
}
