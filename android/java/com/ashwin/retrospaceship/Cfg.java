package com.ashwin.retrospaceship;

/**
 * Every tunable number in the game.
 *
 * <p>Speeds and rates are per <em>second</em>, never per frame: the whole
 * simulation is delta-time driven, so the game plays identically whether the
 * device is holding 60fps or dipping into the 40s.
 */
public final class Cfg {
    private Cfg() {}

    // ---------- playfield ----------
    /** Virtual playfield width. All gameplay maths happens in these units. */
    public static final float W = 480f;

    /**
     * Virtual playfield height, set once from the real screen so the field
     * fills it edge to edge. Not a constant: a 9:20 phone gets a much taller
     * field than the 480x640 the game was originally tuned against.
     */
    public static float H = 640f;

    /** The height the balance numbers below were tuned at. */
    private static final float TUNED_H = 640f;

    /**
     * How much to scale travel speeds for a taller field.
     *
     * <p>Scaling fully by {@code H / TUNED_H} would keep every timing identical
     * but make bullets proportionally faster than a fingertip can move, since
     * drag moves the ship a fixed number of world units per screen pixel no
     * matter how tall the field is. Leaving speeds alone instead makes a tall
     * field feel sluggish and empty. Damping the scale splits the difference:
     * the extra room is a real advantage, paid for with somewhat quicker fire.
     */
    private static float fieldScale = 1f;

    public static void setPlayfield(float height) {
        H = Math.max(TUNED_H, height);
        fieldScale = 1f + (H / TUNED_H - 1f) * 0.6f;
    }

    public static float enemySpeed() {
        return ENEMY_BASE_SPEED * fieldScale;
    }

    public static float enemyBulletSpeed() {
        return ENEMY_BULLET_SPEED * fieldScale;
    }

    public static float bulletSpeed() {
        return BULLET_SPEED * fieldScale;
    }

    // ---------- vertical anchors ----------
    // Fractions of the playfield, not pixels: an enemy that parked at a fixed
    // y=150 would sit harmlessly near the top of a tall screen.
    /** Where divers stop to telegraph before charging. */
    public static final float HOVER_Y = 0.34f;
    /** Where turrets park to hold a piece of the screen. */
    public static final float PARK_Y = 0.23f;
    /** Where strafing enemies settle before sliding sideways. */
    public static final float STRAFE_Y = 0.17f;
    /** Where a boss hovers for the fight. */
    public static final float BOSS_HOVER_Y = 0.20f;

    // ---------- player ----------
    public static final float PLAYER_SPEED = 360f;
    public static final int PLAYER_MAX_HEALTH = 3;
    /** The real collision circle: tiny, bullet-hell style, and always drawn. */
    public static final float PLAYER_HITBOX_RADIUS = 3f;
    public static final float PLAYER_SPRITE_W = 34f;
    public static final float PLAYER_SPRITE_H = 30f;
    public static final int PLAYER_START_BOMBS = 2;
    public static final int PLAYER_MAX_BOMBS = 4;
    public static final float INVINCIBILITY_MS = 1400f;
    public static final float BOMB_INVINCIBILITY_MS = 1500f;
    public static final float SHIELD_RECHARGE_MS = 12000f;

    // ---------- graze / overdrive ----------
    public static final float GRAZE_RADIUS = 22f;
    public static final int GRAZE_SCORE = 2;
    public static final float GRAZE_OVERDRIVE = 1.4f;
    public static final float KILL_OVERDRIVE = 2.2f;
    public static final float OVERDRIVE_MAX = 100f;
    public static final float OVERDRIVE_DURATION_MS = 6000f;
    public static final float OVERDRIVE_FIRE_SCALE = 0.5f;
    public static final float OVERDRIVE_SCORE_BONUS = 0.5f;

    // ---------- weapons ----------
    public static final float BULLET_SPEED = 540f;
    public static final float FIRE_RATE_MS_MIN = 95f;
    public static final int WEAPON_MAX_LEVEL = 5;
    public static final float PLAYER_BULLET_INFLATE = 4f;
    public static final float ENEMY_HURTBOX_INFLATE = 3f;

    // ---------- enemies ----------
    public static final float ENEMY_BASE_SPEED = 120f;
    public static final float ENEMY_BULLET_SPEED = 190f;
    public static final float ELITE_HP_BONUS = 0.6f;
    public static final float ELITE_SCORE_BONUS = 2f;

    // ---------- difficulty director ----------
    public static final float INTENSITY_MIN = 1f;
    public static final float INTENSITY_MAX = 12f;
    public static final float RAMP_PER_STAGE = 0.55f;
    public static final float ADAPT_OFFSET_MIN = -1.5f;
    public static final float ADAPT_OFFSET_MAX = 2.5f;
    public static final float ADAPT_EMA_PER_SEC = 0.05f;
    public static final float MERCY_WINDOW_MS = 5000f;

    public static final int WAVES_PER_STAGE_MIN = 4;
    public static final int WAVES_PER_STAGE_MAX = 6;
    public static final float WAVE_MAX_MS = 32000f;
    public static final float WAVE_GAP_MS = 1400f;
    public static final float STAGE_BANNER_MS = 2200f;
    public static final int BOSS_EVERY_N_STAGES = 5;
    public static final float BOSS_CALM_MS = 4500f;

    public static final float THREAT_BUDGET_BASE = 8f;
    public static final float THREAT_BUDGET_PER_INTENSITY = 6f;

    public static final int PERFECT_WAVE_BONUS = 250;

    public static final String[] WAVE_FLAVORS = {
            "STANDARD", "SWARM", "ARMORED", "BLITZ", "BARRAGE", "AMBUSH", "ELITE"};
    public static final int FORCED_STANDARD_EVERY = 4;

    // ---------- scoring ----------
    public static final float COMBO_TIMEOUT_MS = 1600f;
    public static final float PICKUP_CHANCE = 0.16f;
    public static final float LOW_HP_PICKUP_BOOST = 2f;

    // ---------- feel ----------
    public static final float SHAKE_ON_KILL = 2.5f;
    public static final float SHAKE_ON_BIG_KILL = 6f;
    public static final float SHAKE_ON_HIT = 14f;
    public static final float SHAKE_ON_BOMB = 20f;
    public static final float SHAKE_MAX = 18f;
    public static final float SHAKE_DECAY_PER_SEC = 6f;

    public static final float HITSTOP_BIG_KILL_MS = 40f;
    public static final float HITSTOP_PLAYER_HIT_MS = 90f;
    public static final float HITSTOP_BOSS_PHASE_MS = 120f;
    public static final float HITSTOP_MAX_MS = 120f;

    public static final float SLOWMO_DEATH_SCALE = 0.35f;
    public static final float SLOWMO_BOMB_SCALE = 0.6f;
    public static final float SLOWMO_BOMB_MS = 400f;

    public static final float ENEMY_HIT_FLASH_MS = 60f;
    public static final int MAX_PARTICLES = 640;
    public static final int MAX_ENEMY_BULLETS = 220;

    // ---------- colour law ----------
    // Role indices resolved against the live theme at draw time. Enemy fire
    // never borrows the player-shot colour: misreading a bullet must be
    // impossible, so these roles are assigned once and never swapped.
    public static final int C_PLAYER = Theme.GREEN;
    public static final int C_PLAYER_BULLET = Theme.YELLOW;
    public static final int C_ENEMY_AIMED = Theme.RED;
    public static final int C_ENEMY_SPRAY = Theme.MAGENTA;
    public static final int C_ENEMY_LASER = Theme.CYAN;
    public static final int C_ENEMY_BODY = Theme.MAGENTA;
    public static final int C_ENEMY_TOUGH = Theme.ORANGE;
    public static final int C_PICKUP_WEAPON = Theme.CYAN;
    public static final int C_PICKUP_BOMB = Theme.ORANGE;

    public static final float ENEMY_BULLET_MIN_RADIUS = 5f;

    // ---------- telegraphs ----------
    // Every enemy weapon warns before it fires. No exceptions.
    public static final float TELEGRAPH_MIN_MS = 300f;
    public static final float LASER_TELEGRAPH_MS = 500f;
}
