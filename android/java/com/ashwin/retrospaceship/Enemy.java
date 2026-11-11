package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enemy archetypes, built from composable movement and weapon parts.
 *
 * <p>One {@code Enemy} class plus a data table beats nine subclasses: adding an
 * enemy is adding a row, and the director reads cost and unlock values straight
 * off the table to decide what it can afford to spawn.
 *
 * <p>Every weapon component carries a telegraph. That is an invariant, not a
 * preference -- an attack that fires without warning reads as unfair, and the
 * whole difficulty curve depends on the player trusting what they see.
 */
public final class Enemy extends Entity {

    // ---------- movement components ----------

    public interface Movement {
        void step(Enemy e, Ctx ctx);
    }

    /** Straight down at a fixed pace. */
    public static final class Descend implements Movement {
        private final float speed;
        private final float drift;

        public Descend(float speed, float drift) {
            this.speed = speed;
            this.drift = drift;
        }

        @Override
        public void step(Enemy e, Ctx ctx) {
            e.vy = Cfg.enemySpeed() * speed * e.speedScale;
            e.vx = drift * Cfg.enemySpeed() * e.speedScale;
            e.integrate(ctx.clock.dts());
        }
    }

    /** Weaves across the screen while descending -- readable and rhythmic. */
    public static final class SineDrift implements Movement {
        private final float amplitude = 90f;
        private final float periodMs = 1800f;
        private final float speed = 0.8f;
        private Float originX = null;

        @Override
        public void step(Enemy e, Ctx ctx) {
            if (originX == null) {
                originX = e.x;
            }
            e.vy = Cfg.enemySpeed() * speed * e.speedScale;
            float phase = (float) ((ctx.clock.t - e.spawnT) / periodMs * Patterns.TAU);
            e.x = originX + (float) Math.sin(phase) * amplitude;
            e.y += e.vy * ctx.clock.dts();
            e.x = clamp(e.x, 12f, Cfg.W - 12f);
        }
    }

    /** Descend, hold with a visible tell, then dive at the player's lane. */
    public static final class HoverThenCharge implements Movement {
        private final float holdY = Cfg.H * Cfg.HOVER_Y;
        private final float holdMs = 900f;
        private final float chargeSpeed = 3.4f;
        private int state = 0;               // 0 approach, 1 hold, 2 charge
        private double holdUntil = 0.0;

        @Override
        public void step(Enemy e, Ctx ctx) {
            float dts = ctx.clock.dts();
            if (state == 0) {
                e.y += Cfg.enemySpeed() * 1.1f * e.speedScale * dts;
                if (e.y >= holdY) {
                    state = 1;
                    holdUntil = ctx.clock.t + holdMs;
                    e.telegraph = 1f;
                }
            } else if (state == 1) {
                if (ctx.player != null) {
                    e.x += (ctx.player.x - e.x) * Math.min(1f, dts * 2.2f);
                }
                e.telegraph = (float) Math.max(0.0, (holdUntil - ctx.clock.t) / holdMs);
                if (ctx.clock.t >= holdUntil) {
                    state = 2;
                    e.telegraph = 0f;
                }
            } else {
                e.y += Cfg.enemySpeed() * chargeSpeed * e.speedScale * dts;
            }
        }
    }

    /** Take up a position and hold it -- turrets that own a piece of the screen. */
    public static final class ParkAndStay implements Movement {
        private final float stopY = Cfg.H * Cfg.PARK_Y;
        private final float stayMs = 7000f;
        private Double parkedAt = null;

        @Override
        public void step(Enemy e, Ctx ctx) {
            float dts = ctx.clock.dts();
            if (e.y < stopY) {
                e.y += Cfg.enemySpeed() * 0.9f * e.speedScale * dts;
                return;
            }
            if (parkedAt == null) {
                parkedAt = ctx.clock.t;
            }
            if (ctx.clock.t - parkedAt > stayMs) {
                e.y += Cfg.enemySpeed() * 1.4f * e.speedScale * dts;
            }
        }
    }

    /** Slide along the top of the playfield, bouncing at the edges. */
    public static final class Strafe implements Movement {
        private final float stopY = Cfg.H * Cfg.STRAFE_Y;
        private final float speed = 1.2f;
        private int direction = 0;

        @Override
        public void step(Enemy e, Ctx ctx) {
            float dts = ctx.clock.dts();
            if (direction == 0) {
                direction = e.x > Cfg.W * 0.5f ? -1 : 1;
            }
            if (e.y < stopY) {
                e.y += Cfg.enemySpeed() * e.speedScale * dts;
                return;
            }
            e.x += direction * Cfg.enemySpeed() * speed * e.speedScale * dts;
            if (e.x < 30f) {
                e.x = 30f;
                direction = 1;
            } else if (e.x > Cfg.W - 30f) {
                e.x = Cfg.W - 30f;
                direction = -1;
            }
        }
    }

    /** Slow, relentless pursuit -- punishes standing still. */
    public static final class HomingDrift implements Movement {
        private final float speed = 0.85f;
        private final float turnRate = 1.1f;

        @Override
        public void step(Enemy e, Ctx ctx) {
            float dts = ctx.clock.dts();
            float s = Cfg.enemySpeed() * speed * e.speedScale;
            if (ctx.player != null && ctx.player.alive) {
                if (e.vx == 0f && e.vy == 0f) {
                    e.vy = s;
                }
                float[] v = Patterns.homingTurn(e.vx, e.vy,
                        ctx.player.x - e.x, ctx.player.y - e.y, s, turnRate * dts);
                e.vx = v[0];
                e.vy = v[1];
            } else {
                e.vy = s;
            }
            e.integrate(dts);
        }
    }

    // ---------- weapon components ----------

    public interface Weapon {
        void step(Enemy e, Ctx ctx);

        void drawTelegraph(Enemy e, Draw d);
    }

    public static final class NoWeapon implements Weapon {
        @Override
        public void step(Enemy e, Ctx ctx) {
        }

        @Override
        public void drawTelegraph(Enemy e, Draw d) {
        }
    }

    /** Base for anything that shoots. Enforces a visible warning window. */
    private abstract static class Telegraphed implements Weapon {
        final float intervalMs;
        final float telegraphMs;
        Double nextFire = null;
        boolean telegraphing = false;

        Telegraphed(float intervalMs, float telegraphMs) {
            this.intervalMs = intervalMs;
            this.telegraphMs = Math.max(Cfg.TELEGRAPH_MIN_MS, telegraphMs);
        }

        @Override
        public void step(Enemy e, Ctx ctx) {
            if (nextFire == null) {
                // Stagger first shots so a whole rank never fires in one frame.
                nextFire = ctx.clock.t + intervalMs * Rng.uniform(0.6f, 1.2f);
                return;
            }
            if (e.y < 0f) {
                return;
            }
            double remaining = nextFire - ctx.clock.t;
            telegraphing = remaining > 0.0 && remaining <= telegraphMs;
            if (remaining <= 0.0) {
                fire(e, ctx);
                telegraphing = false;
                nextFire = ctx.clock.t + intervalMs;
            }
        }

        abstract void fire(Enemy e, Ctx ctx);

        @Override
        public void drawTelegraph(Enemy e, Draw d) {
            if (!telegraphing) {
                return;
            }
            d.circleOutline(e.x, e.y, e.h * 0.75f, Theme.color(Cfg.C_ENEMY_AIMED), 1.5f);
        }
    }

    public static final class AimedShot extends Telegraphed {
        private final int count;
        private final float arc;

        public AimedShot(int count, float arc, float intervalMs, float telegraphMs) {
            super(intervalMs, telegraphMs);
            this.count = count;
            this.arc = arc;
        }

        @Override
        void fire(Enemy e, Ctx ctx) {
            if (ctx.player == null) {
                return;
            }
            float[] v = Patterns.burstAt(e.x, e.y, ctx.player.x, ctx.player.y,
                    count, arc, Cfg.enemyBulletSpeed());
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(e.x, e.y, v[i], v[i + 1],
                        Cfg.C_ENEMY_AIMED, Bullets.EnemyBullet.DART, 6f));
            }
            ctx.sfx.play("shoot_spread", 0.4f);
        }

        @Override
        public void drawTelegraph(Enemy e, Draw d) {
            if (!telegraphing) {
                return;
            }
            int color = Theme.color(Cfg.C_ENEMY_AIMED);
            d.circleOutline(e.x, e.y, e.h * 0.8f, color, 1.5f);
            d.polygon(new float[]{
                    e.x, e.y + e.h * 0.8f,
                    e.x - 5f, e.y + e.h * 0.5f,
                    e.x + 5f, e.y + e.h * 0.5f}, color);
        }
    }

    public static final class FanShot extends Telegraphed {
        private final int count;
        private final float arc;

        public FanShot(int count, float arc, float intervalMs, float telegraphMs) {
            super(intervalMs, telegraphMs);
            this.count = count;
            this.arc = arc;
        }

        @Override
        void fire(Enemy e, Ctx ctx) {
            float[] v = Patterns.fan((float) (Math.PI / 2), count, arc,
                    Cfg.enemyBulletSpeed() * 0.9f);
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(e.x, e.y, v[i], v[i + 1],
                        Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.ORB, 7f));
            }
            ctx.sfx.play("shoot_spread", 0.45f);
        }
    }

    public static final class RingShot extends Telegraphed {
        private final int count;

        public RingShot(int count, float intervalMs, float telegraphMs) {
            super(intervalMs, telegraphMs);
            this.count = count;
        }

        @Override
        void fire(Enemy e, Ctx ctx) {
            float[] v = Patterns.ring(count, Cfg.enemyBulletSpeed() * 0.8f,
                    Rng.uniform(0f, 0.6f));
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(e.x, e.y, v[i], v[i + 1],
                        Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.ORB, 6f));
            }
            ctx.sfx.play("shoot_spread", 0.5f);
        }
    }

    /** A continuous rotating stream: dense but perfectly predictable. */
    public static final class SpiralShot implements Weapon {
        private final float shotIntervalMs = 150f;
        private final int perTurn = 13;
        private final float telegraphMs = 600f;
        private int index = 0;
        private Double nextShot = null;
        private Double armedAt = null;
        private boolean telegraphing = false;

        @Override
        public void step(Enemy e, Ctx ctx) {
            if (armedAt == null) {
                armedAt = ctx.clock.t + telegraphMs;
                return;
            }
            if (ctx.clock.t < armedAt) {
                telegraphing = true;
                return;
            }
            telegraphing = false;
            if (nextShot == null) {
                nextShot = ctx.clock.t;
            }
            if (ctx.clock.t >= nextShot && e.y > 0f) {
                nextShot = ctx.clock.t + shotIntervalMs;
                float[] v = Patterns.spiral(index++, perTurn, Cfg.enemyBulletSpeed() * 0.7f);
                ctx.world.addEnemyBullet(new Bullets.EnemyBullet(e.x, e.y, v[0], v[1],
                        Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.SPIRAL, 6f));
            }
        }

        @Override
        public void drawTelegraph(Enemy e, Draw d) {
            if (!telegraphing) {
                return;
            }
            d.circleOutline(e.x, e.y, e.h, Theme.color(Cfg.C_ENEMY_AIMED), 1.5f);
        }
    }

    public static final class DropMine extends Telegraphed {
        private final float fuseMs = 2600f;

        public DropMine() {
            super(2000f, 340f);
        }

        @Override
        void fire(Enemy e, Ctx ctx) {
            ctx.world.addEnemyBullet(new Bullets.EnemyBullet(
                    e.x, e.y + e.h * 0.5f, 0f, Cfg.enemyBulletSpeed() * 0.35f,
                    Cfg.C_ENEMY_SPRAY, Bullets.EnemyBullet.MINE, 9f, fuseMs, 1));
        }
    }

    // ---------- archetypes ----------

    public interface Factory<T> {
        T create();
    }

    public static final class Archetype {
        public final String key;
        public final int hp;
        public final float w, h;
        public final int score;
        public final float cost;
        /** Minimum director intensity before this archetype can appear at all. */
        public final float unlock;
        public final String sprite;
        public final int colorRole;
        public final Factory<Movement> movement;
        public final Factory<Weapon> weapon;
        public final float dropWeight;
        public final boolean shielded;
        public final boolean splits;
        public final boolean spins;

        Archetype(String key, int hp, float w, float h, int score, float cost, float unlock,
                  String sprite, int colorRole, Factory<Movement> movement,
                  Factory<Weapon> weapon, float dropWeight,
                  boolean shielded, boolean splits, boolean spins) {
            this.key = key;
            this.hp = hp;
            this.w = w;
            this.h = h;
            this.score = score;
            this.cost = cost;
            this.unlock = unlock;
            this.sprite = sprite;
            this.colorRole = colorRole;
            this.movement = movement;
            this.weapon = weapon;
            this.dropWeight = dropWeight;
            this.shielded = shielded;
            this.splits = splits;
            this.spins = spins;
        }
    }

    public static final Map<String, Archetype> ARCHETYPES = new LinkedHashMap<>();

    static {
        add(new Archetype("DRONE", 1, 30, 24, 10, 1f, 0f, "e_drone", Cfg.C_ENEMY_BODY,
                () -> new Descend(1f, 0f), NoWeapon::new, 1.4f, false, false, false));
        add(new Archetype("WEAVER", 2, 28, 24, 20, 2f, 1f, "e_weaver", Cfg.C_ENEMY_BODY,
                SineDrift::new, () -> new AimedShot(1, 0f, 1900f, 380f),
                1f, false, false, false));
        add(new Archetype("DARTER", 2, 24, 28, 25, 3f, 2f, "e_darter", Cfg.C_ENEMY_BODY,
                HoverThenCharge::new, NoWeapon::new, 1f, false, false, false));
        add(new Archetype("SEEKER", 2, 24, 22, 25, 3f, 3f, "e_seeker", Cfg.C_ENEMY_BODY,
                HomingDrift::new, NoWeapon::new, 1f, false, false, true));
        add(new Archetype("SPLITTER", 3, 34, 28, 30, 4f, 3f, "e_splitter", Cfg.C_ENEMY_BODY,
                () -> new Descend(0.8f, 0f), NoWeapon::new, 1f, false, true, false));
        add(new Archetype("MINELAYER", 3, 32, 24, 35, 4f, 3.5f, "e_minelayer", Cfg.C_ENEMY_TOUGH,
                Strafe::new, DropMine::new, 1f, false, false, false));
        add(new Archetype("TANK", 6, 38, 32, 40, 5f, 2.5f, "e_tank", Cfg.C_ENEMY_TOUGH,
                () -> new Descend(0.55f, 0f), () -> new FanShot(3, 0.7f, 2200f, 460f),
                1f, false, false, false));
        add(new Archetype("SHIELDER", 3, 30, 26, 30, 5f, 4.5f, "e_shielder", Cfg.C_ENEMY_TOUGH,
                () -> new Descend(0.7f, 0f), NoWeapon::new, 1f, true, false, false));
        add(new Archetype("TURRET", 4, 34, 30, 35, 6f, 4f, "e_turret", Cfg.C_ENEMY_TOUGH,
                ParkAndStay::new, SpiralShot::new, 1f, false, false, true));
    }

    private static void add(Archetype a) {
        ARCHETYPES.put(a.key, a);
    }

    /** Archetypes unlocked at this intensity -- the roster widens across a run. */
    public static List<String> available(float intensity) {
        List<String> keys = new ArrayList<>();
        for (Archetype a : ARCHETYPES.values()) {
            if (a.unlock <= intensity) {
                keys.add(a.key);
            }
        }
        return keys;
    }

    // ---------- the enemy ----------

    public final Archetype arch;
    public final String key;
    public final float w, h;
    public final boolean elite;
    public final int maxHp;
    public int hp;
    public final float speedScale;
    private Movement movement;
    private final Weapon weapon;
    public double spawnT = 0.0;
    public float telegraph = 0f;
    private double hitFlashUntil = 0.0;
    private int shieldHp;
    private double t = 0.0;
    private boolean spawned = false;
    private float spin = 0f;

    public Enemy(String key, float x, float y, float speedScale, boolean elite) {
        super(x, y, 0f, 0f, 0f);
        this.arch = ARCHETYPES.get(key);
        this.key = key;
        this.w = arch.w;
        this.h = arch.h;
        this.radius = Math.max(arch.w, arch.h) * 0.5f;
        this.elite = elite;
        this.maxHp = Math.max(1, Math.round(arch.hp * (elite ? 1f + Cfg.ELITE_HP_BONUS : 1f)));
        this.hp = maxHp;
        this.speedScale = speedScale;
        this.movement = arch.movement.create();
        this.weapon = arch.weapon.create();
        this.shieldHp = arch.shielded ? 4 : 0;
    }

    /** Override the movement part -- used for splitter children. */
    public void setMovement(Movement value) {
        movement = value;
    }

    public int scoreValue() {
        return Math.round(arch.score * (elite ? Cfg.ELITE_SCORE_BONUS : 1f));
    }

    public float halfW() {
        return w * 0.5f;
    }

    public float halfH() {
        return h * 0.5f;
    }

    @Override
    public void update(Ctx ctx) {
        if (!spawned) {
            spawnT = ctx.clock.t;
            spawned = true;
        }
        t = ctx.clock.t;
        if (arch.spins) {
            spin += ctx.clock.dts() * 90f;
        }
        movement.step(this, ctx);
        weapon.step(this, ctx);
        if (y > Cfg.H + 60f || x < -120f || x > Cfg.W + 120f) {
            kill();
        }
    }

    /** Apply damage. Returns true if this killed the enemy. */
    public boolean takeDamage(int amount, Ctx ctx, float dirX, float dirY) {
        if (shieldHp > 0) {
            // A shielder's bubble absorbs whole hits, so the readable answer is
            // "keep shooting", not "your weapon is broken".
            shieldHp -= amount;
            ctx.sfx.play("tank_hit", 0.5f);
            ctx.fx.burst(x, y + h * 0.6f, Theme.color(Theme.CYAN), 4,
                    (float) (-Math.PI / 2), 50f, 160f, 160f, 320f, 1f, 3f, 1.4f);
            if (shieldHp <= 0) {
                ctx.fx.shockwave(x, y, 6f, 40f, 260f, Theme.color(Theme.CYAN), 2f);
            }
            return false;
        }

        hp -= amount;
        hitFlashUntil = ctx.clock.t + Cfg.ENEMY_HIT_FLASH_MS;
        if (hp > 0) {
            ctx.sfx.play(arch.cost >= 5f ? "tank_hit" : "enemy_hit", 0.5f);
            float direction = Float.isNaN(dirX) ? Float.NaN
                    : (float) Math.atan2(dirY, dirX);
            ctx.fx.burst(x, y, Theme.color(Theme.CYAN), 4, direction,
                    60f, 190f, 120f, 280f, 1f, 3f, 1.6f);
            return false;
        }

        kill();
        onDeath(ctx);
        return true;
    }

    private void onDeath(Ctx ctx) {
        boolean big = arch.cost >= 4f;
        int color = Theme.color(arch.colorRole);
        ctx.fx.burst(x, y, color, big ? 18 : 12);
        ctx.sfx.play(big ? "enemy_kill_big" : "enemy_kill_small");
        if (big) {
            ctx.fx.shockwave(x, y, 4f, 46f, 300f, color, 2f);
            ctx.fx.shake(Cfg.SHAKE_ON_BIG_KILL);
            ctx.clock.hitstop(Cfg.HITSTOP_BIG_KILL_MS);
        } else {
            ctx.fx.shake(Cfg.SHAKE_ON_KILL);
        }

        if (arch.splits) {
            // The splitter's sprite is a visibly two-part hull, so this reads as
            // the expected outcome rather than as a surprise second wave.
            for (float direction : new float[]{-1f, 1f}) {
                Enemy child = new Enemy("DRONE", x + direction * 14f, y,
                        speedScale * 1.1f, false);
                child.setMovement(new Descend(1f, direction * 0.6f));
                ctx.world.addEnemy(child);
            }
        }
    }

    // ---------- draw ----------

    @Override
    public void draw(Draw d) {
        boolean flashing = t < hitFlashUntil;
        float angle = arch.spins ? spin : 0f;

        if (flashing) {
            // Flat white on purpose. Splitting a hit flash into a dim hull and
            // lit panels would soften the clearest feedback in the game.
            d.glowSprite(d.assets.get(arch.sprite), x, y, h, angle,
                    Theme.color(Theme.WHITE), 1.45f, 255);
        } else {
            // An elite is the same hull with yellow linework rather than a
            // different silhouette, so the tell needs nothing new to learn.
            int accent = Theme.color(elite ? Theme.YELLOW : Theme.WHITE);
            d.detailSprite(arch.sprite, x, y, h, angle,
                    Theme.color(arch.colorRole), accent, 1.45f, 255);
        }

        if (shieldHp > 0) {
            d.arc(x, y, Math.max(w, h) * 0.72f, 200f, 140f,
                    Theme.withAlpha(Theme.color(Theme.CYAN), 200), 3f);
        }
        if (elite) {
            // An elite is a normal enemy with more health, so the tell is a ring
            // rather than a different silhouette the player would have to relearn.
            d.circleOutline(x, y, Math.max(w, h) * 0.62f,
                    Theme.withAlpha(Theme.color(Theme.YELLOW), 220), 1.5f);
        }

        if (maxHp > 1 && hp < maxHp) {
            float barW = w;
            float fill = barW * (hp / (float) maxHp);
            float barY = y - halfH() - 8f;
            d.rect(x - barW * 0.5f, barY, barW, 3f,
                    Theme.withAlpha(Theme.color(Theme.BLACK), 200));
            d.rect(x - barW * 0.5f, barY, fill, 3f, Theme.color(Theme.RED));
        }

        if (telegraph > 0f) {
            // Charge tell for divers: a line down the lane they are about to take.
            d.line(x, y + halfH(), x, Cfg.H,
                    Theme.withAlpha(Theme.color(Cfg.C_ENEMY_AIMED), 170), 1.5f);
            d.circleOutline(x, y, 4f + (1f - telegraph) * 8f, Theme.color(Theme.WHITE), 1.5f);
        }
        weapon.drawTelegraph(this, d);
    }
}
