package com.ashwin.retrospaceship;

/**
 * The in-game HUD.
 *
 * <p>Laid out around the ship's dodge lane: status in the top corners, the
 * overdrive meter pinned to the bottom edge of the playfield, and nothing in the
 * middle where the player is actually flying. Everything is drawn dim so it
 * never competes with a bullet for attention.
 */
public final class Hud {
    private Hud() {}

    private static final int DIM = 165;

    public static void draw(Draw d, World world, Director director, int highScore, double t) {
        Player player = world.player;
        drawHealth(d, player);
        drawBombs(d, player);
        drawScore(d, world, highScore);
        drawWeapon(d, player);
        drawOverdrive(d, player, t);
        drawGraze(d, player);
        drawBanner(d, director, t);
        if (world.boss != null) {
            if (world.boss.state == Boss.WARNING) {
                drawBossWarning(d, t);
            } else {
                drawBossBar(d, world.boss);
            }
        }
    }

    private static void drawHealth(Draw d, Player player) {
        float x = 16f, y = 16f;
        for (int i = 0; i < player.maxHealth; i++) {
            float bx = x + i * 16f;
            if (i < player.health) {
                d.rect(bx, y, 12f, 12f, Theme.color(Theme.GREEN));
            }
            d.rectOutline(bx, y, 12f, 12f, Theme.color(Theme.WHITE), 1f);
        }
        if (player.shield) {
            d.circleOutline(x + player.maxHealth * 16f + 8f, y + 6f, 6f,
                    Theme.color(Theme.CYAN), 2f);
        }
    }

    private static void drawBombs(Draw d, Player player) {
        float x = 22f, y = 40f;
        for (int i = 0; i < player.bombs; i++) {
            d.circle(x + i * 15f, y, 5f, Theme.color(Cfg.C_PICKUP_BOMB));
            d.circleOutline(x + i * 15f, y, 5f, Theme.color(Theme.WHITE), 1f);
        }
    }

    private static void drawScore(Draw d, World world, int highScore) {
        d.textRight(pad(world.score), 22f, Cfg.W - 14f, 20f,
                Theme.color(Cfg.C_PICKUP_WEAPON), 255);
        d.textRight("HI " + pad(Math.max(highScore, world.score)), 12f, Cfg.W - 14f, 42f,
                Theme.color(Theme.WHITE), DIM);
        if (world.combo >= 5) {
            d.textRight("x" + world.comboMultiplier() + "  " + world.combo + " CHAIN", 14f,
                    Cfg.W - 14f, 60f, Theme.color(Theme.YELLOW), 255);
        }
    }

    private static String pad(int score) {
        String value = Integer.toString(Math.max(0, score));
        StringBuilder out = new StringBuilder();
        for (int i = value.length(); i < 7; i++) {
            out.append('0');
        }
        return out.append(value).toString();
    }

    private static void drawWeapon(Draw d, Player player) {
        float y = Cfg.H - 56f;
        d.textLeft(player.weaponName(), 14f, 16f, y, Theme.color(Theme.WHITE), DIM);
        for (int i = 0; i < Cfg.WEAPON_MAX_LEVEL; i++) {
            float x = 16f + i * 9f;
            if (i < player.weaponLevel) {
                d.rect(x, y + 10f, 6f, 5f, Theme.color(Cfg.C_PICKUP_WEAPON));
            } else {
                d.rectOutline(x, y + 10f, 6f, 5f, Theme.color(Theme.WHITE), 1f);
            }
        }
    }

    private static void drawOverdrive(Draw d, Player player, double t) {
        float height = 4f;
        float y = Cfg.H - height - 10f;
        float fraction = player.overdrive / Cfg.OVERDRIVE_MAX;
        if (player.overdriveActive()) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(t / 90.0);
            d.rect(0, y, Cfg.W, height, Theme.shade(Theme.color(Theme.CYAN), pulse));
            d.text("OVERDRIVE", 12f, Cfg.W * 0.5f, y - 12f, Theme.color(Theme.CYAN));
            return;
        }
        d.rect(0, y, Cfg.W, height, Theme.shade(Theme.color(Theme.WHITE), 0.18f));
        d.rect(0, y, Cfg.W * fraction, height, Theme.color(Theme.CYAN));
        if (player.overdriveReady()) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(t / 110.0);
            d.text("OVERDRIVE READY  -  HOLD 2ND FINGER", 12f, Cfg.W * 0.5f, y - 12f,
                    Theme.shade(Theme.color(Theme.CYAN), pulse));
        }
    }

    /** Graze count floats by the ship, so the player's eyes stay on the hitbox. */
    private static void drawGraze(Draw d, Player player) {
        if (player.grazeStreak >= 3) {
            d.text("GRAZE " + player.grazeStreak, 12f, player.x, player.y + 30f,
                    Theme.color(Theme.CYAN), 200, d.assets.hud);
        }
    }

    private static void drawBanner(Draw d, Director director, double t) {
        if (director.banner == null) {
            return;
        }
        double remaining = director.bannerUntil - t;
        int alpha = remaining > 500 ? 255 : (int) Math.max(0, 255 * remaining / 500.0);
        int color = "WARNING".equals(director.banner)
                ? Theme.color(Theme.RED) : Theme.color(Theme.YELLOW);
        d.text(director.banner, 24f, Cfg.W * 0.5f, Cfg.H * 0.30f, color, alpha, d.assets.hud);
    }

    private static void drawBossWarning(Draw d, double t) {
        if (((int) (t / 240.0)) % 2 == 0) {
            d.text("!! WARNING !!", 30f, Cfg.W * 0.5f, Cfg.H * 0.44f, Theme.color(Theme.RED));
        }
        d.text("INCOMING", 16f, Cfg.W * 0.5f, Cfg.H * 0.44f + 32f,
                Theme.color(Theme.WHITE), DIM, d.assets.hud);
    }

    private static void drawBossBar(Draw d, Boss boss) {
        float margin = 40f;
        float width = Cfg.W - margin * 2f;
        float x = margin, y = 78f, height = 9f;
        d.rect(x - 2f, y - 2f, width + 4f, height + 4f,
                Theme.withAlpha(Theme.color(Theme.BLACK), 220));
        d.rectOutline(x - 2f, y - 2f, width + 4f, height + 4f, Theme.color(Theme.WHITE), 1f);
        float fill = width * boss.hpFraction();
        int color = boss.hpFraction() > 0.33f
                ? Theme.color(Theme.RED) : Theme.color(Theme.YELLOW);
        d.rect(x, y, fill, height, color);
        // Phase ticks make the fight's structure legible at a glance: the player
        // can see how much of this boss is left as phases, not just as a bar.
        for (int i = 1; i < boss.phaseCount(); i++) {
            float tick = x + width * (1f - i / (float) boss.phaseCount());
            d.line(tick, y, tick, y + height, Theme.color(Theme.BLACK), 2f);
        }
        d.text(boss.name, 13f, Cfg.W * 0.5f, y - 12f, Theme.color(Theme.WHITE), 200,
                d.assets.hud);
        if (!boss.arms.isEmpty() && !boss.coreVulnerable()) {
            d.text("DESTROY ARMS", 11f, Cfg.W * 0.5f, y + height + 12f,
                    Theme.color(Theme.YELLOW), 200, d.assets.hud);
        }
    }

    // ---------- pause target ----------

    /** Half-size of the pause glyph, and of its (larger) touch target. */
    private static final float PAUSE_ICON = 11f;
    private static final float PAUSE_TOUCH = 25f;
    private static final float PAUSE_CY = 26f;

    public static boolean inPause(float x, float y) {
        return Math.abs(x - Cfg.W * 0.5f) <= PAUSE_TOUCH
                && Math.abs(y - PAUSE_CY) <= PAUSE_TOUCH;
    }

    /**
     * A deliberately small pause target, centred at the very top.
     *
     * <p>It sits in the band above the ship's ceiling, so it never overlaps
     * anywhere the player actually flies, and it is drawn dim because an
     * accidental pause mid-boss is far worse than a pause that took two tries
     * to find.
     */
    public static void drawPause(Draw d, boolean armed) {
        float cx = Cfg.W * 0.5f;
        int color = armed ? Theme.color(Theme.CYAN)
                : Theme.shade(Theme.color(Theme.WHITE), 0.45f);
        d.rectOutline(cx - PAUSE_ICON, PAUSE_CY - PAUSE_ICON,
                PAUSE_ICON * 2f, PAUSE_ICON * 2f, color, 1.5f);
        d.rect(cx - 4f, PAUSE_CY - 5f, 2.5f, 10f, color);
        d.rect(cx + 1.5f, PAUSE_CY - 5f, 2.5f, 10f, color);
    }

    /**
     * The gesture reminder shown for the first few seconds of a run.
     *
     * <p>Permanent on-screen labels for controls that are gestures would be
     * clutter the player stops reading after one run, so this fades out and
     * lives on in HOW TO PLAY.
     */
    public static void drawGestureHint(Draw d, float fade) {
        int alpha = (int) (200 * Math.max(0f, Math.min(1f, fade)));
        if (alpha <= 0) {
            return;
        }
        // Placed in the band above the ship's ceiling -- the only part of the
        // field the player can never fly into, so the reminder cannot sit on top
        // of the ship the way a bottom-anchored one did.
        d.text("SECOND FINGER:  TAP = BOMB", 13f, Cfg.W * 0.5f, Cfg.H * 0.165f,
                Theme.color(Theme.WHITE), alpha, d.assets.hud);
        d.text("HOLD = OVERDRIVE", 13f, Cfg.W * 0.5f, Cfg.H * 0.165f + 20f,
                Theme.color(Theme.WHITE), alpha, d.assets.hud);
    }

    /**
     * Queue a red edge pulse when the player is nearly out.
     *
     * <p>This only asks the effect bus for a vignette; it draws nothing itself,
     * which is why it belongs in the update step rather than the draw step.
     */
    public static void lowHealthVignette(World world, Fx fx) {
        Player player = world.player;
        if (!player.alive) {
            return;
        }
        if (player.health <= 1) {
            fx.vignette(Theme.color(Theme.RED), 260f, 70);
        } else if (player.health <= player.maxHealth * 0.4f) {
            fx.vignette(Theme.color(Theme.RED), 260f, 34);
        }
    }
}
