package com.ashwin.retrospaceship;

/**
 * Projectiles: the player's shots, enemy fire, and telegraphed beams.
 *
 * <p>The two sides are deliberately treated differently. Enemy bullets test
 * against the player's 3px core with exact circle maths, because that precision
 * is what makes a dense screen feel fair rather than cheap. Player shots get the
 * opposite treatment -- an inflated box, so a near miss counts as a hit.
 */
public final class Bullets {
    private Bullets() {}

    // ---------- player ----------

    public static final class PlayerBullet extends Entity {
        public static final int SPREAD = 0, LASER = 1, HOMING = 2;

        public final int damage;
        public final boolean piercing;
        public final int kind;
        private int hits = 0;
        private float angle = -90f;

        public PlayerBullet(float x, float y, float vx, float vy,
                            int damage, boolean piercing, int kind) {
            super(x, y, vx, vy, 4f);
            this.damage = damage;
            this.piercing = piercing;
            this.kind = kind;
        }

        public float halfW() {
            return kind == LASER ? 3f : radius + Cfg.PLAYER_BULLET_INFLATE;
        }

        public float halfH() {
            return kind == LASER ? 12f : radius + Cfg.PLAYER_BULLET_INFLATE;
        }

        @Override
        public void update(Ctx ctx) {
            float dts = ctx.clock.dts();
            if (kind == HOMING) {
                Object target = ctx.world.nearestTarget(x, y);
                if (target != null) {
                    float tx = target instanceof Entity ? ((Entity) target).x : x;
                    float ty = target instanceof Entity ? ((Entity) target).y : y;
                    float speed = (float) Math.hypot(vx, vy);
                    if (speed <= 0f) {
                        speed = Cfg.bulletSpeed();
                    }
                    float[] v = Patterns.homingTurn(vx, vy, tx - x, ty - y, speed, 4.5f * dts);
                    vx = v[0];
                    vy = v[1];
                }
            }
            angle = (float) Math.toDegrees(Math.atan2(vy, vx)) + 90f;
            integrate(dts);
            if (offscreen(30f)) {
                kill();
            }
        }

        /** Returns true when the bullet should be removed after landing a hit. */
        public boolean onHit() {
            hits++;
            if (!piercing) {
                return true;
            }
            // A piercing shot is not infinite: four bodies and it is spent, so
            // the laser stays a line-clearing tool rather than a win button.
            return hits >= 4;
        }

        @Override
        public void draw(Draw d) {
            int color = Theme.color(Cfg.C_PLAYER_BULLET);
            switch (kind) {
                case LASER:
                    d.glowSprite(d.assets.get(Assets.BEAM), x, y, 30f, angle, color, 2.1f, 255);
                    break;
                case HOMING:
                    d.glowSprite(d.assets.get(Assets.ORB_SMALL), x, y, 15f, angle, color, 2f, 255);
                    break;
                default:
                    d.glowSprite(d.assets.get(Assets.BOLT), x, y, 20f, angle, color, 1.9f, 255);
                    break;
            }
        }
    }

    // ---------- enemy ----------

    public static final class EnemyBullet extends Entity {
        public static final int ORB = 0, DART = 1, SPIRAL = 2, HOMING = 3, MINE = 4;

        public final int colorRole;
        public final int kind;
        public boolean grazed = false;
        public final int damage;
        private final float fuseMs;
        private float age = 0f;
        private float angle = 90f;

        private final float[] trailX;
        private final float[] trailY;
        private int trailCount = 0;

        public EnemyBullet(float x, float y, float vx, float vy, int colorRole, int kind,
                           float radius) {
            this(x, y, vx, vy, colorRole, kind, radius, 0f, 1);
        }

        public EnemyBullet(float x, float y, float vx, float vy, int colorRole, int kind,
                           float radius, float fuseMs, int damage) {
            super(x, y, vx, vy, Math.max(Cfg.ENEMY_BULLET_MIN_RADIUS, radius));
            this.colorRole = colorRole;
            this.kind = kind;
            this.fuseMs = fuseMs;
            this.damage = damage;
            boolean trailed = kind == SPIRAL || kind == HOMING;
            trailX = trailed ? new float[6] : null;
            trailY = trailed ? new float[6] : null;
        }

        @Override
        public void update(Ctx ctx) {
            float dt = ctx.clock.dt;
            float dts = ctx.clock.dts();
            age += dt;

            if (kind == MINE) {
                // Mines drift to a halt, pulse faster as the fuse burns, then
                // burst into a ring. The blink rate is the telegraph.
                float drag = (float) Math.pow(0.94, dts * 60f);
                vx *= drag;
                vy *= drag;
                integrate(dts);
                if (age >= fuseMs) {
                    detonate(ctx);
                }
                return;
            }

            if (trailX != null) {
                if (trailCount < trailX.length) {
                    trailCount++;
                } else {
                    System.arraycopy(trailX, 1, trailX, 0, trailX.length - 1);
                    System.arraycopy(trailY, 1, trailY, 0, trailY.length - 1);
                }
                trailX[trailCount - 1] = x;
                trailY[trailCount - 1] = y;
            }

            if (kind == HOMING && ctx.player != null && ctx.player.alive) {
                float speed = (float) Math.hypot(vx, vy);
                if (speed <= 0f) {
                    speed = Cfg.enemyBulletSpeed();
                }
                float[] v = Patterns.homingTurn(vx, vy, ctx.player.x - x, ctx.player.y - y,
                        speed, 1.4f * dts);
                vx = v[0];
                vy = v[1];
            }

            angle = (float) Math.toDegrees(Math.atan2(vy, vx)) + 90f;
            integrate(dts);
            if (offscreen(40f)) {
                kill();
            }
        }

        /** Burst into a readable ring -- the mine's whole purpose. */
        private void detonate(Ctx ctx) {
            kill();
            float[] velocities = Patterns.ring(8, Cfg.enemyBulletSpeed() * 0.85f, 0f);
            for (int i = 0; i < velocities.length; i += 2) {
                ctx.world.addEnemyBullet(new EnemyBullet(x, y, velocities[i], velocities[i + 1],
                        Cfg.C_ENEMY_SPRAY, ORB, 6f));
            }
            ctx.fx.burst(x, y, Theme.color(Cfg.C_ENEMY_SPRAY), 10);
            ctx.fx.shake(2f);
            ctx.sfx.play("enemy_kill_small");
        }

        @Override
        public void draw(Draw d) {
            int color = Theme.color(colorRole);
            if (trailX != null) {
                for (int i = 0; i < trailCount; i++) {
                    float fade = (i + 1) / (float) (trailCount + 1);
                    d.circle(trailX[i], trailY[i], Math.max(1f, radius * fade * 0.6f),
                            Theme.withAlpha(color, (int) (120 * fade)));
                }
            }
            switch (kind) {
                case MINE: {
                    float remaining = Math.max(0f, 1f - age / Math.max(1f, fuseMs));
                    boolean blink = ((int) (age / Math.max(60f, 220f * remaining))) % 2 == 0;
                    d.sprite(d.assets.get(Assets.MINE), x, y, radius * 2.6f,
                            age * 0.06f, color, 255);
                    if (blink) {
                        d.circle(x, y, Math.max(2f, radius * 0.45f), Theme.color(Theme.WHITE));
                    }
                    break;
                }
                case DART:
                    d.glowSprite(d.assets.get(Assets.BOLT), x, y, radius * 3.4f, angle,
                            color, 1.9f, 255);
                    break;
                case SPIRAL:
                    d.glowSprite(d.assets.get(Assets.ORB_SMALL), x, y, radius * 2.6f,
                            age * 0.2f, color, 2f, 255);
                    break;
                default:
                    // A bright core inside a dim halo: legible over the starfield
                    // at any density, which orbs alone are not.
                    d.glowSprite(d.assets.get(Assets.ORB), x, y, radius * 2.6f,
                            age * 0.1f, color, 2.1f, 255);
                    d.circle(x, y, Math.max(1f, radius * 0.28f), Theme.color(Theme.WHITE));
                    break;
            }
        }
    }

    // ---------- beams ----------

    /** A telegraphed vertical beam: a warning line first, then the column. */
    public static final class Laser extends Entity {
        public final float beamWidth;
        private final float telegraphMs;
        private final float durationMs;
        private float age = 0f;

        public Laser(float x, float y, float beamWidth, float telegraphMs, float durationMs) {
            super(x, y, 0f, 0f, beamWidth * 0.5f);
            this.beamWidth = beamWidth;
            this.telegraphMs = telegraphMs;
            this.durationMs = durationMs;
        }

        public boolean firing() {
            return age >= telegraphMs && age < telegraphMs + durationMs;
        }

        public boolean hitsPoint(float px, float py, float pr) {
            if (!firing()) {
                return false;
            }
            return Math.abs(px - x) <= beamWidth * 0.5f + pr && py >= y;
        }

        @Override
        public void update(Ctx ctx) {
            age += ctx.clock.dt;
            if (age >= telegraphMs + durationMs) {
                kill();
            }
        }

        @Override
        public void draw(Draw d) {
            int color = Theme.color(Cfg.C_ENEMY_LASER);
            if (age < telegraphMs) {
                // A thin warning line for the full charge time. Nothing in this
                // game fires unannounced.
                d.line(x, y, x, Cfg.H, Theme.withAlpha(Theme.color(Theme.WHITE), 170), 1.5f);
                float charge = age / Math.max(1f, telegraphMs);
                d.circleOutline(x, y, 4f + charge * 10f, color, 2f);
                return;
            }
            float progress = (age - telegraphMs) / Math.max(1f, durationMs);
            float width = beamWidth * (1f - Math.abs(progress - 0.5f) * 0.4f);
            d.rect(x - width * 0.5f, y, width, Cfg.H, Theme.withAlpha(color, 150));
            d.rect(x - width * 0.18f, y, Math.max(2f, width * 0.36f), Cfg.H,
                    Theme.color(Theme.WHITE));
        }
    }
}
