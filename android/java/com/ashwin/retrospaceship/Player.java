package com.ashwin.retrospaceship;

/**
 * The player ship.
 *
 * <p>The sprite is roughly 34x30, but the collision circle is 3px at the core
 * and is drawn on screen at all times, per bullet-hell convention: what can be
 * hit must always be visible. Focus mode slows movement, brightens that dot and
 * shows the graze ring, for threading dense patterns.
 */
public final class Player extends Entity {

    public static final int SPREAD = 0, LASER = 1, HOMING = 2;
    public static final String[] WEAPON_NAMES = {"SPREAD", "LASER", "HOMING"};

    private static final float[] WEAPON_INTERVAL = {300f, 200f, 380f};
    private static final float[] WEAPON_PER_LEVEL = {-30f, -20f, -34f};
    private static final int[] WEAPON_DAMAGE = {1, 1, 2};
    private static final boolean[] WEAPON_PIERCING = {false, true, false};

    public final float spriteW = Cfg.PLAYER_SPRITE_W;
    public final float spriteH = Cfg.PLAYER_SPRITE_H;

    public int maxHealth = Cfg.PLAYER_MAX_HEALTH;
    public int health = Cfg.PLAYER_MAX_HEALTH;
    public boolean shield = true;
    public double shieldReadyAt = 0.0;
    public float shieldRechargeScale = 1f;
    public double invincibleUntil = 0.0;

    public int bombs = Cfg.PLAYER_START_BOMBS;
    public int maxBombs = Cfg.PLAYER_MAX_BOMBS;

    public int weapon = SPREAD;
    public int weaponLevel = 1;
    private double nextShot = 0.0;

    public float overdrive = 0f;
    public double overdriveUntil = 0.0;
    public float overdriveBonusMs = 0f;
    public int grazeCount = 0;
    public int grazeStreak = 0;
    private double grazeStreakUntil = 0.0;
    public float grazeRadius = Cfg.GRAZE_RADIUS;
    public float scoreBonus = 0f;

    public boolean focus = false;

    private double t = 0.0;
    private double muzzleUntil = 0.0;
    private final Flight flight = new Flight();

    public Player() {
        super(Cfg.W * 0.5f, Cfg.H - 90f, 0f, 0f, Cfg.PLAYER_HITBOX_RADIUS);
    }

    // ---------- derived state ----------

    public boolean overdriveActive() {
        return t < overdriveUntil;
    }

    public boolean overdriveReady() {
        return overdrive >= Cfg.OVERDRIVE_MAX && !overdriveActive();
    }

    public boolean isInvincible(double now) {
        return now < invincibleUntil;
    }

    public float fireInterval() {
        float interval = WEAPON_INTERVAL[weapon] + WEAPON_PER_LEVEL[weapon] * (weaponLevel - 1);
        if (overdriveActive()) {
            interval *= Cfg.OVERDRIVE_FIRE_SCALE;
        }
        return Math.max(Cfg.FIRE_RATE_MS_MIN, interval);
    }

    public String weaponName() {
        return WEAPON_NAMES[weapon];
    }

    // ---------- movement ----------

    /**
     * Take the controls. The ship flies from wherever it currently is.
     *
     * <p>There is deliberately no precision mode. On a touch screen precision
     * already comes from moving the finger slowly, and slowing the ship on top
     * of that only makes it lag behind.
     */
    public void beginPilot() {
        flight.begin();
    }

    /** Where the finger is asking the ship to be. */
    public void setTarget(float targetX, float targetY) {
        flight.setTarget(targetX, targetY);
    }

    public void endPilot() {
        flight.end();
    }

    /** 0..1 of how hard the engines are working, for exhaust and trail. */
    public float thrust() {
        return Math.min(1f, flight.speed() / 420f);
    }

    public float bank() {
        return flight.bank;
    }

    private void clampToField() {
        float halfW = spriteW * 0.5f;
        float halfH = spriteH * 0.5f;
        x = clamp(x, halfW, Cfg.W - halfW);
        // The ship is kept out of the top quarter: enemies need somewhere to
        // exist, and a player parked at the spawn line trivialises every wave.
        y = clamp(y, Cfg.H * 0.25f + halfH, Cfg.H - halfH - 8f);
    }

    // ---------- frame ----------

    @Override
    public void update(Ctx ctx) {
        Clock clock = ctx.clock;
        t = clock.t;

        if (shieldReadyAt > 0.0 && clock.t >= shieldReadyAt && !shield) {
            shield = true;
            shieldReadyAt = 0.0;
            ctx.sfx.play("shield_recharge");
            ctx.fx.popup(x, y - 34f, "SHIELD", Theme.color(Theme.CYAN), 13f);
        }
        if (grazeStreak > 0 && clock.t > grazeStreakUntil) {
            grazeStreak = 0;
        }
        if (!overdriveActive() && overdriveUntil > 0.0 && clock.t >= overdriveUntil) {
            overdriveUntil = 0.0;
            ctx.sfx.play("overdrive_end");
        }

        // Flight is integrated once per frame, not once per touch event. Driving
        // movement from the event stream tied the ship's motion to the digitizer's
        // report rate rather than to the frame it is drawn on.
        flight.step(clock.dts(), spriteW * 0.5f, Cfg.W - spriteW * 0.5f,
                Cfg.H * 0.25f + spriteH * 0.5f, Cfg.H - spriteH * 0.5f - 8f);
        x = flight.x;
        y = flight.y;
        vx = flight.vx;
        vy = flight.vy;

        if (clock.dt > 0f) {
            // The exhaust answers the throttle, and streams away from the turn.
            float thrust = thrust();
            ctx.fx.burst(x, y + spriteH * 0.42f, Theme.color(Cfg.C_PLAYER),
                    thrust > 0.55f ? 2 : 1,
                    (float) Math.PI / 2f + flight.bank * 0.006f,
                    30f + 130f * thrust, 90f + 240f * thrust,
                    120f, 260f + 160f * thrust, 1f, 2f, 0.8f);
        }
        tryFire(ctx);
    }

    private void tryFire(Ctx ctx) {
        if (!alive || ctx.clock.t < nextShot) {
            return;
        }
        nextShot = ctx.clock.t + fireInterval();
        float muzzleY = y - spriteH * 0.5f;
        float speed = Cfg.bulletSpeed();
        int damage = WEAPON_DAMAGE[weapon];
        boolean piercing = WEAPON_PIERCING[weapon] || overdriveActive();

        if (weapon == SPREAD) {
            for (float offset : Patterns.spreadOffsets(weaponLevel)) {
                float[] v = Patterns.fromAngle((float) (-Math.PI / 2) + offset, speed);
                ctx.world.addPlayerBullet(new Bullets.PlayerBullet(
                        x, muzzleY, v[0], v[1], damage, piercing, Bullets.PlayerBullet.SPREAD));
            }
        } else if (weapon == LASER) {
            int columns = 1 + (weaponLevel >= 3 ? 1 : 0) + (weaponLevel >= 5 ? 1 : 0);
            float spacing = 9f;
            float start = -(columns - 1) * 0.5f;
            for (int i = 0; i < columns; i++) {
                ctx.world.addPlayerBullet(new Bullets.PlayerBullet(
                        x + (start + i) * spacing, muzzleY, 0f, -speed * 1.25f,
                        damage, true, Bullets.PlayerBullet.LASER));
            }
        } else {
            int count = 1 + weaponLevel / 2;
            float[] v = Patterns.fan((float) (-Math.PI / 2), count, 0.5f, speed * 0.8f);
            for (int i = 0; i < v.length; i += 2) {
                ctx.world.addPlayerBullet(new Bullets.PlayerBullet(
                        x, muzzleY, v[i], v[i + 1], damage, piercing,
                        Bullets.PlayerBullet.HOMING));
            }
        }

        muzzleUntil = ctx.clock.t + 55f;
        ctx.sfx.play(weapon == SPREAD ? "shoot_spread"
                : weapon == LASER ? "shoot_laser" : "shoot_homing", 0.6f);
    }

    // ---------- events ----------

    /** A bullet passed close to the core without hitting it. */
    public void addGraze(Ctx ctx) {
        grazeCount++;
        grazeStreak++;
        grazeStreakUntil = ctx.clock.t + 1400f;
        overdrive = Math.min(Cfg.OVERDRIVE_MAX, overdrive + Cfg.GRAZE_OVERDRIVE);
        ctx.sfx.grazeTick(grazeStreak / 3);
        ctx.fx.burst(x, y, Theme.color(Theme.CYAN), 2,
                Float.NaN, 40f, 120f, 120f, 260f, 1f, 2f, (float) Math.PI);
    }

    public void addKillCharge() {
        overdrive = Math.min(Cfg.OVERDRIVE_MAX, overdrive + Cfg.KILL_OVERDRIVE);
    }

    public boolean activateOverdrive(Ctx ctx) {
        if (!overdriveReady()) {
            return false;
        }
        overdrive = 0f;
        overdriveUntil = ctx.clock.t + Cfg.OVERDRIVE_DURATION_MS + overdriveBonusMs;
        ctx.sfx.play("overdrive_start");
        ctx.fx.flash(Theme.color(Theme.CYAN), 160f, 70);
        ctx.fx.shockwave(x, y, 10f, 150f, 420f, Theme.color(Theme.CYAN), 3f);
        ctx.fx.popup(x, y - 46f, "OVERDRIVE", Theme.color(Theme.CYAN), 18f);
        return true;
    }

    public boolean useBomb(Ctx ctx) {
        if (bombs <= 0 || !alive) {
            return false;
        }
        bombs--;
        invincibleUntil = Math.max(invincibleUntil, ctx.clock.t + Cfg.BOMB_INVINCIBILITY_MS);
        return true;
    }

    /** Returns true if damage actually landed. */
    public boolean takeHit(Ctx ctx, float dirX, float dirY) {
        if (isInvincible(ctx.clock.t) || !alive) {
            return false;
        }
        invincibleUntil = ctx.clock.t + Cfg.INVINCIBILITY_MS;
        overdrive = Math.max(0f, overdrive * 0.5f);
        grazeStreak = 0;

        if (shield) {
            // The shield eats one hit and comes back if you stay clean, which is
            // what stops a single unlucky frame ending a good run.
            shield = false;
            shieldReadyAt = ctx.clock.t + Cfg.SHIELD_RECHARGE_MS * shieldRechargeScale;
            ctx.sfx.play("shield_break");
            ctx.fx.shockwave(x, y, 8f, 62f, 300f, Theme.color(Theme.CYAN), 2f);
            ctx.fx.shake(Cfg.SHAKE_ON_HIT * 0.6f, dirX, dirY);
            ctx.clock.hitstop(Cfg.HITSTOP_PLAYER_HIT_MS * 0.6f);
            return true;
        }

        health--;
        ctx.sfx.play("player_hit");
        ctx.fx.shake(Cfg.SHAKE_ON_HIT, dirX, dirY);
        ctx.clock.hitstop(Cfg.HITSTOP_PLAYER_HIT_MS);
        ctx.fx.flash(Theme.color(Theme.RED), 140f, 90);
        ctx.fx.burst(x, y, Theme.color(Cfg.C_PLAYER), 22);
        if (health <= 0) {
            alive = false;
        }
        return true;
    }

    public void heal(int amount) {
        health = Math.min(maxHealth, health + amount);
    }

    /** Same weapon type levels up; a different type swaps at a one-level cost. */
    public void giveWeapon(int kind, Ctx ctx) {
        if (kind == weapon) {
            if (weaponLevel < Cfg.WEAPON_MAX_LEVEL) {
                weaponLevel++;
                ctx.fx.popup(x, y - 40f, WEAPON_NAMES[kind] + " L" + weaponLevel,
                        Theme.color(Theme.CYAN), 14f);
            } else {
                ctx.fx.popup(x, y - 40f, "MAX", Theme.color(Theme.YELLOW), 14f);
            }
            ctx.sfx.play("pickup_weapon");
        } else {
            weapon = kind;
            weaponLevel = Math.max(1, weaponLevel - 1);
            ctx.sfx.play("weapon_swap");
            ctx.fx.popup(x, y - 40f, WEAPON_NAMES[kind], Theme.color(Theme.CYAN), 15f);
        }
    }

    public void giveBomb(Ctx ctx) {
        if (bombs < maxBombs) {
            bombs++;
            ctx.fx.popup(x, y - 40f, "+BOMB", Theme.color(Theme.ORANGE), 14f);
        }
        ctx.sfx.play("pickup_bomb");
    }

    public void resetForRun() {
        x = Cfg.W * 0.5f;
        y = Cfg.H - 90f;
        vx = vy = 0f;
        alive = true;
        flight.reset(x, y);
    }

    // ---------- draw ----------

    @Override
    public void draw(Draw d) {
        if (!alive) {
            return;
        }
        boolean flicker = isInvincible(t) && ((int) (t / 80.0)) % 2 == 0;

        if (overdriveActive()) {
            float r = 26f + 3f * (float) Math.sin(t / 90.0);
            d.circleOutline(x, y, r, Theme.shade(Theme.color(Theme.CYAN), 0.5f), 2f);
        }

        if (!flicker) {
            int color = Theme.color(Cfg.C_PLAYER);
            float thrust = thrust();
            float flame = 9f + 17f * thrust + 4f * (float) Math.sin(t / 40.0);
            d.sprite(d.assets.get(Assets.FLAME), x, y + spriteH * 0.5f + flame * 0.35f,
                    flame, 180f + flight.bank * 0.6f, Theme.color(Cfg.C_PLAYER_BULLET),
                    (int) (150f + 90f * thrust));
            // Full bank now, not half: the roll is the clearest signal that this
            // is a ship leaning into a turn rather than a dot being dragged.
            d.detailSprite(Assets.PLAYER, x, y, spriteH, flight.bank, color,
                    Theme.color(Theme.WHITE), 1.5f, 255);
            if (t < muzzleUntil) {
                d.circle(x, y - spriteH * 0.5f, 4f, Theme.color(Theme.WHITE));
            }
        }

        if (shield) {
            d.circleOutline(x, y, spriteW * 0.78f,
                    Theme.withAlpha(Theme.color(Theme.CYAN), 150), 1.6f);
        }

        // The real hitbox, always visible, brightest while focusing.
        if (focus) {
            d.circleOutline(x, y, grazeRadius, Theme.withAlpha(Theme.color(Theme.RED), 160), 1.2f);
        }
        int core = focus ? Theme.color(Theme.WHITE) : Theme.shade(Theme.color(Theme.WHITE), 0.55f);
        d.circle(x, y, radius + (focus ? 1f : 0f), core);
    }
}
