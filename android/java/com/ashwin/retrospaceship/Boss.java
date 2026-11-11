package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.List;

/**
 * Bosses: a lifecycle state machine wrapped around scripted attack phases.
 *
 * <p>Lifecycle is WARNING to ENTERING to FIGHTING to PHASE_BREAK to DYING to
 * GONE. Phases swap at HP thresholds and each runs a cyclic script of attacks.
 * Every attack is telegraphed, and each phase break clears the screen and turns
 * the bullets into score -- the classic reward for surviving a phase.
 */
public final class Boss extends Entity {

    private static final float WARNING_MS = 2500f;
    private static final float ENTER_MS = 1500f;
    private static final float PHASE_BREAK_MS = 1200f;
    private static final float DYING_MS = 2500f;

    public static final int WARNING = 0, ENTERING = 1, FIGHTING = 2,
            PHASE_BREAK = 3, DYING = 4, GONE = 5;

    // ---------- attacks ----------

    public interface Attack {
        void fire(Boss boss, Ctx ctx);
    }

    private static Attack aimedFan(int count, float arc, float speedScale) {
        return (boss, ctx) -> {
            if (ctx.player == null) {
                return;
            }
            float muzzleY = boss.y + boss.bh * 0.4f;
            float[] v = Patterns.burstAt(boss.x, muzzleY, ctx.player.x, ctx.player.y,
                    count, arc, Cfg.enemyBulletSpeed() * speedScale);
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(boss.x, muzzleY,
                        v[i], v[i + 1], Cfg.C_ENEMY_AIMED, Bullets.EnemyBullet.DART, 7f));
            }
            ctx.sfx.play("shoot_spread", 0.5f);
        };
    }

    private static Attack ringBurst(int count, float speedScale) {
        return (boss, ctx) -> {
            float[] v = Patterns.ring(count, Cfg.enemyBulletSpeed() * speedScale,
                    Rng.uniform(0f, 0.5f));
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(boss.x, boss.y,
                        v[i], v[i + 1], Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.ORB, 7f));
            }
            ctx.sfx.play("shoot_spread", 0.55f);
        };
    }

    private static Attack spiralStream(int perTurn) {
        return (boss, ctx) -> {
            float[] v = Patterns.spiral(boss.spiralIndex++, perTurn,
                    Cfg.enemyBulletSpeed() * 0.7f);
            ctx.world.addEnemyBullet(new Bullets.EnemyBullet(boss.x, boss.y, v[0], v[1],
                    Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.SPIRAL, 6f));
        };
    }

    /** A horizontal wall of bullets with a gap: a barrage becomes a route. */
    private static Attack wallWithGap(int count, float speedScale) {
        return (boss, ctx) -> {
            int gap = Rng.randint(1, Math.max(1, count - 3));
            float spacing = Cfg.W / count;
            float speed = Cfg.enemyBulletSpeed() * speedScale;
            float muzzleY = boss.y + boss.bh * 0.5f;
            for (int i = 0; i < count; i++) {
                if (i >= gap && i < gap + 2) {
                    continue;
                }
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(
                        spacing * (i + 0.5f), muzzleY, 0f, speed,
                        Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.ORB, 7f));
            }
            ctx.sfx.play("shoot_spread", 0.6f);
        };
    }

    private static Attack sweepLaser() {
        return (boss, ctx) -> {
            float targetX = ctx.player != null ? ctx.player.x : Cfg.W * 0.5f;
            ctx.world.addLaser(new Bullets.Laser(targetX, boss.y + boss.bh * 0.5f, 26f,
                    Cfg.LASER_TELEGRAPH_MS, 900f));
            ctx.sfx.play("shoot_laser", 0.8f);
        };
    }

    private static Attack spawnAdds(int count) {
        return (boss, ctx) -> {
            for (int i = 0; i < count; i++) {
                float x = Cfg.W * (i + 1) / (count + 1f);
                ctx.world.addEnemy(new Enemy("DRONE", x, boss.y + 40f, 1f, false));
            }
            ctx.sfx.play("wave_start", 0.6f);
        };
    }

    private static Attack homingSeeds(int count) {
        return (boss, ctx) -> {
            float[] v = Patterns.fan((float) (Math.PI / 2), count, 1.2f,
                    Cfg.enemyBulletSpeed() * 0.55f);
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(boss.x, boss.y,
                        v[i], v[i + 1], Cfg.C_ENEMY_AIMED, Bullets.EnemyBullet.HOMING, 7f));
            }
            ctx.sfx.play("shoot_homing", 0.6f);
        };
    }

    // ---------- phases ----------

    /** One entry in a phase script: an attack, a repeat count and its timings. */
    private static final class Step {
        final Attack attack;
        final int times;
        final float intervalMs;
        final float cooldownMs;

        Step(Attack attack, int times, float intervalMs, float cooldownMs) {
            this.attack = attack;
            this.times = times;
            this.intervalMs = intervalMs;
            this.cooldownMs = cooldownMs;
        }
    }

    private static final class Phase {
        final Step[] script;
        final float sway;
        final String name;

        Phase(String name, float sway, Step... script) {
            this.name = name;
            this.sway = sway;
            this.script = script;
        }
    }

    /** A destructible sub-target. Killing the arms is how you open the core. */
    public static final class Arm {
        public final float offsetX;
        public final int maxHp;
        public int hp;
        public final float w = 38f, h = 34f;
        public float x, y;
        public double hitFlashUntil = 0.0;

        Arm(float offsetX, int hp) {
            this.offsetX = offsetX;
            this.maxHp = hp;
            this.hp = hp;
        }

        public boolean alive() {
            return hp > 0;
        }
    }

    // ---------- definitions ----------

    private static Phase[] sentinelPhases() {
        return new Phase[]{
                new Phase("SWEEP", 1f,
                        new Step(aimedFan(5, 0.9f, 1f), 3, 420f, 1500f)),
                new Phase("SPIRAL", 1.3f,
                        new Step(aimedFan(5, 1f, 1f), 2, 380f, 900f),
                        new Step(spiralStream(11), 26, 90f, 1200f)),
                new Phase("BARRAGE", 1.6f,
                        new Step(wallWithGap(11, 0.75f), 1, 100f, 1500f),
                        new Step(aimedFan(7, 1.2f, 1f), 2, 320f, 900f),
                        new Step(spawnAdds(2), 1, 100f, 2200f)),
        };
    }

    private static Phase[] hivePhases() {
        return new Phase[]{
                new Phase("BROOD", 0.5f,
                        new Step(ringBurst(12, 0.85f), 2, 700f, 1600f),
                        new Step(spawnAdds(2), 1, 100f, 1800f)),
                new Phase("SWARM", 0.7f,
                        new Step(ringBurst(16, 0.85f), 2, 600f, 1300f),
                        new Step(homingSeeds(3), 1, 100f, 1500f),
                        new Step(spawnAdds(3), 1, 100f, 1800f)),
                new Phase("FRENZY", 0.9f,
                        new Step(ringBurst(18, 0.85f), 3, 520f, 1100f),
                        new Step(homingSeeds(5), 1, 100f, 1200f),
                        new Step(sweepLaser(), 1, 100f, 2400f)),
        };
    }

    private static Phase[] wardenPhases() {
        return new Phase[]{
                new Phase("GUARD", 0.8f,
                        new Step(aimedFan(3, 0.6f, 1f), 3, 400f, 1400f),
                        new Step(wallWithGap(9, 0.75f), 1, 100f, 1800f)),
                new Phase("EXPOSED", 1.1f,
                        new Step(aimedFan(5, 0.9f, 1f), 3, 340f, 1100f),
                        new Step(spiralStream(9), 20, 100f, 1200f)),
                new Phase("CORE", 1.4f,
                        new Step(sweepLaser(), 1, 100f, 1900f),
                        new Step(ringBurst(14, 0.85f), 2, 520f, 1200f),
                        new Step(aimedFan(7, 1.1f, 1f), 2, 300f, 1000f)),
        };
    }

    private static final String[] NAMES = {"SENTINEL", "HIVE", "WARDEN"};
    private static final String[] SPRITES = {"boss_sentinel", "boss_hive", "boss_warden"};
    private static final int[] BASE_HP = {260, 320, 300};
    private static final float[] BODY_W = {104f, 96f, 100f};
    private static final float[] BODY_H = {78f, 96f, 84f};
    private static final int[] BASE_SCORE = {1500, 2000, 2400};
    private static final int[] ARM_COUNT = {0, 0, 2};

    // ---------- instance ----------

    public final String name;
    public final String sprite;
    public final float bw, bh;
    public final int maxHp;
    public int hp;
    public final int scoreValue;
    private final Phase[] phases;
    private int phaseIndex = 0;
    public int state = WARNING;
    private double stateUntil = 0.0;
    private final float hoverY = Cfg.H * Cfg.BOSS_HOVER_Y;
    int spiralIndex = 0;
    private double hitFlashUntil = 0.0;
    public final List<Arm> arms = new ArrayList<>();
    private int scriptStep = 0;
    private int shotsLeft = 0;
    private double nextAction = 0.0;
    private double t = 0.0;
    private boolean started = false;
    private double deathNextBoom = 0.0;

    public Boss(int index, int stage) {
        super(Cfg.W * 0.5f, -100f, 0f, 0f, 0f);
        int i = Math.abs(index) % NAMES.length;
        name = NAMES[i];
        sprite = SPRITES[i];
        bw = BODY_W[i];
        bh = BODY_H[i];
        radius = bw * 0.5f;
        // Later bosses in a run are tougher, so repeats still escalate rather
        // than becoming a lap of honour.
        float scale = 1f + 0.35f * Math.max(0, (stage / Cfg.BOSS_EVERY_N_STAGES) - 1);
        maxHp = Math.round(BASE_HP[i] * scale);
        hp = maxHp;
        scoreValue = Math.round(BASE_SCORE[i] * scale);
        phases = i == 0 ? sentinelPhases() : i == 1 ? hivePhases() : wardenPhases();
        for (int a = 0; a < ARM_COUNT[i]; a++) {
            float side = a % 2 == 0 ? -1f : 1f;
            arms.add(new Arm(side * (bw * 0.5f + 16f), 70));
        }
    }

    private Phase phase() {
        return phases[Math.min(phaseIndex, phases.length - 1)];
    }

    public int phaseCount() {
        return phases.length;
    }

    public float hpFraction() {
        return Math.max(0f, hp / (float) maxHp);
    }

    /** The Warden's core stays sealed until both arms are destroyed. */
    public boolean coreVulnerable() {
        for (Arm arm : arms) {
            if (arm.alive()) {
                return false;
            }
        }
        return true;
    }

    public boolean active() {
        return state == FIGHTING || state == PHASE_BREAK;
    }

    public boolean gone() {
        return state == GONE;
    }

    private void enter(int next, Ctx ctx, float duration) {
        state = next;
        stateUntil = ctx.clock.t + duration;
    }

    // ---------- update ----------

    @Override
    public void update(Ctx ctx) {
        t = ctx.clock.t;
        if (!started) {
            started = true;
            enter(WARNING, ctx, WARNING_MS);
            ctx.sfx.play("boss_warning");
            ctx.sfx.playMusic(Sfx.BOSS);
        }
        for (Arm arm : arms) {
            arm.x = x + arm.offsetX;
            arm.y = y + 6f;
        }

        switch (state) {
            case WARNING:
                // Pulse a red edge glow while the siren runs; nothing spawns yet.
                if (((int) (ctx.clock.t / 220.0)) % 2 == 0) {
                    ctx.fx.vignette(Theme.color(Theme.RED), 240f, 90);
                }
                if (ctx.clock.t >= stateUntil) {
                    enter(ENTERING, ctx, ENTER_MS);
                }
                break;
            case ENTERING:
                y += (hoverY - y) * Math.min(1f, ctx.clock.dts() * 2.4f);
                if (ctx.clock.t >= stateUntil) {
                    y = hoverY;
                    enter(FIGHTING, ctx, 0f);
                    beginPhase(ctx);
                }
                break;
            case FIGHTING:
                sway(ctx, 1f);
                runScript(ctx);
                break;
            case PHASE_BREAK:
                sway(ctx, 0.3f);
                if (ctx.clock.t >= stateUntil) {
                    enter(FIGHTING, ctx, 0f);
                    beginPhase(ctx);
                }
                break;
            case DYING:
                updateDying(ctx);
                break;
            default:
                break;
        }
    }

    private void updateDying(Ctx ctx) {
        sway(ctx, 0.2f);
        if (ctx.clock.t >= deathNextBoom) {
            deathNextBoom = ctx.clock.t + 190f;
            float bx = x + Rng.uniform(-bw * 0.5f, bw * 0.5f);
            float by = y + Rng.uniform(-bh * 0.5f, bh * 0.5f);
            ctx.fx.burst(bx, by, Theme.color(Cfg.C_ENEMY_TOUGH), 20);
            ctx.fx.shockwave(bx, by, 4f, 60f, 380f, Theme.color(Theme.YELLOW), 2f);
            ctx.fx.shake(6f);
            ctx.clock.hitstop(60f);
            ctx.sfx.play("enemy_kill_big");
        }
        if (ctx.clock.t >= stateUntil) {
            ctx.fx.flash(Theme.color(Theme.WHITE), 320f, 200);
            ctx.fx.shockwave(x, y, 10f, 320f, 700f, Theme.color(Theme.WHITE), 4f);
            ctx.sfx.play("boss_death");
            state = GONE;
            kill();
        }
    }

    private void sway(Ctx ctx, float scale) {
        float amount = phase().sway * scale;
        float span = Cfg.W * 0.5f - bw * 0.5f - 14f;
        x = Cfg.W * 0.5f + (float) Math.sin(ctx.clock.t / 1400.0 * amount) * span;
    }

    private void beginPhase(Ctx ctx) {
        scriptStep = 0;
        shotsLeft = 0;
        nextAction = ctx.clock.t + 400f;
    }

    private void runScript(Ctx ctx) {
        if (ctx.clock.t < nextAction) {
            return;
        }
        Step[] script = phase().script;
        if (script.length == 0) {
            return;
        }
        Step step = script[scriptStep % script.length];
        if (shotsLeft <= 0) {
            shotsLeft = step.times;
        }
        step.attack.fire(this, ctx);
        shotsLeft--;
        if (shotsLeft > 0) {
            nextAction = ctx.clock.t + step.intervalMs;
        } else {
            scriptStep++;
            nextAction = ctx.clock.t + step.cooldownMs;
        }
    }

    // ---------- damage ----------

    public float halfW() {
        return bw * 0.5f;
    }

    public float halfH() {
        return bh * 0.5f;
    }

    /**
     * Apply damage to an arm or the core.
     *
     * @param arm the arm that was struck, or null for the core
     * @return true if this killed the boss
     */
    public boolean damage(Arm arm, int amount, Ctx ctx) {
        if (state != FIGHTING) {
            return false;
        }
        if (arm != null) {
            arm.hp -= amount;
            arm.hitFlashUntil = ctx.clock.t + Cfg.ENEMY_HIT_FLASH_MS;
            ctx.fx.burst(arm.x, arm.y, Theme.color(Theme.CYAN), 4,
                    Float.NaN, 60f, 180f, 200f, 400f, 1f, 3f, (float) Math.PI);
            ctx.sfx.play("tank_hit", 0.5f);
            if (arm.hp <= 0) {
                ctx.fx.burst(arm.x, arm.y, Theme.color(Cfg.C_ENEMY_TOUGH), 24);
                ctx.fx.shockwave(arm.x, arm.y, 6f, 80f, 400f, Theme.color(Theme.YELLOW), 3f);
                ctx.fx.shake(10f);
                ctx.clock.hitstop(Cfg.HITSTOP_BIG_KILL_MS);
                ctx.sfx.play("enemy_kill_big");
                if (coreVulnerable()) {
                    ctx.fx.popup(x, y - 40f, "CORE EXPOSED", Theme.color(Theme.YELLOW), 17f);
                }
            }
            return false;
        }

        if (!coreVulnerable()) {
            // Armoured: sparks, no damage. Reads clearly as "shoot the arms".
            ctx.fx.burst(x, y + bh * 0.4f, Theme.color(Theme.WHITE), 3,
                    Float.NaN, 50f, 140f, 120f, 240f, 1f, 2f, (float) Math.PI);
            ctx.sfx.play("tank_hit", 0.3f);
            return false;
        }

        hp -= amount;
        hitFlashUntil = ctx.clock.t + Cfg.ENEMY_HIT_FLASH_MS;
        ctx.sfx.play("enemy_hit", 0.35f);
        ctx.fx.burst(x, y + bh * 0.3f, Theme.color(Theme.CYAN), 3,
                Float.NaN, 50f, 170f, 120f, 260f, 1f, 3f, (float) Math.PI);

        if (hp <= 0) {
            hp = 0;
            enter(DYING, ctx, DYING_MS);
            deathNextBoom = ctx.clock.t;
            ctx.clock.slowmo(Cfg.SLOWMO_DEATH_SCALE, 1200f);
            ctx.world.clearEnemyBullets(true);
            return true;
        }

        float threshold = 1f - (phaseIndex + 1) / (float) phaseCount();
        if (hpFraction() <= threshold && phaseIndex < phaseCount() - 1) {
            phaseIndex++;
            enter(PHASE_BREAK, ctx, PHASE_BREAK_MS);
            ctx.fx.flash(Theme.color(Theme.WHITE), 220f, 150);
            ctx.fx.shockwave(x, y, 10f, 260f, 620f, Theme.color(Theme.CYAN), 4f);
            ctx.fx.shake(14f);
            ctx.clock.hitstop(Cfg.HITSTOP_BOSS_PHASE_MS);
            ctx.fx.popup(Cfg.W * 0.5f, Cfg.H * 0.4f, "PHASE " + (phaseIndex + 1),
                    Theme.color(Theme.YELLOW), 22f);
            ctx.sfx.play("boss_phase");
            ctx.world.clearEnemyBullets(true);
        }
        return false;
    }

    // ---------- draw ----------

    @Override
    public void draw(Draw d) {
        if (state == WARNING) {
            return;
        }
        boolean flashing = t < hitFlashUntil;
        // The dying blink and the hit flash both want a solid white hull, for the
        // same reason an enemy's does: they are feedback, not decoration.
        boolean solidWhite = flashing || (state == DYING && ((int) (t / 90.0)) % 2 == 0);
        int body = solidWhite ? Theme.color(Theme.WHITE) : Theme.color(Cfg.C_ENEMY_TOUGH);

        for (Arm arm : arms) {
            if (!arm.alive()) {
                continue;
            }
            if (t < arm.hitFlashUntil) {
                d.glowSprite(d.assets.get(Assets.BOSS_ARM), arm.x, arm.y, arm.h, 0f,
                        Theme.color(Theme.WHITE), 1.4f, 255);
            } else {
                d.detailSprite(Assets.BOSS_ARM, arm.x, arm.y, arm.h, 0f,
                        Theme.color(Cfg.C_ENEMY_BODY), Theme.color(Theme.WHITE), 1.4f, 255);
            }
            float fill = arm.w * (arm.hp / (float) arm.maxHp);
            d.rect(arm.x - arm.w * 0.5f, arm.y - arm.h * 0.5f - 8f, fill, 3f,
                    Theme.color(Theme.RED));
        }

        if (solidWhite) {
            d.glowSprite(d.assets.get(sprite), x, y, bh, 0f, body, 1.35f, 255);
        } else {
            d.detailSprite(sprite, x, y, bh, 0f, body, Theme.color(Theme.WHITE), 1.35f, 255);
        }

        // The core is the only thing that matters when there are arms, so it is
        // drawn as a hot dot that is dark while sealed and lit once exposed.
        if (!arms.isEmpty()) {
            int core = coreVulnerable()
                    ? Theme.color(Theme.RED) : Theme.shade(Theme.color(Theme.BLACK), 1.6f);
            d.circle(x, y, 12f, core);
            d.circleOutline(x, y, 12f, Theme.color(Cfg.C_ENEMY_LASER), 2f);
        }
    }
}
