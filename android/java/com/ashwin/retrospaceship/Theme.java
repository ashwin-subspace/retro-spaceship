package com.ashwin.retrospaceship;

import android.graphics.Color;

/**
 * The four colour themes, and the role indices draw code resolves against.
 *
 * <p>Sprites ship as white/grey art and are multiplied by a role colour at draw
 * time, so one art set serves every theme instead of needing four hand-drawn
 * variants. {@link #token} increments on each change so anything caching a
 * pre-tinted bitmap knows to rebuild it.
 */
public final class Theme {
    private Theme() {}

    public static final int BLACK = 0, GREEN = 1, CYAN = 2, MAGENTA = 3,
            YELLOW = 4, ORANGE = 5, RED = 6, WHITE = 7;
    public static final int ROLES = 8;

    public static final String[] NAMES = {"PHOSPHOR", "CYBERPUNK", "AMBER", "ICE"};

    /** Indexed [theme][role]; role order matches the constants above. */
    private static final int[][] PALETTES = {
            {rgb(5, 5, 10), rgb(57, 255, 20), rgb(0, 255, 255), rgb(255, 0, 200),
             rgb(255, 255, 0), rgb(255, 140, 0), rgb(255, 40, 40), rgb(230, 230, 230)},
            {rgb(8, 4, 18), rgb(190, 60, 255), rgb(0, 230, 255), rgb(255, 20, 180),
             rgb(255, 225, 60), rgb(255, 90, 180), rgb(255, 40, 90), rgb(235, 225, 255)},
            {rgb(10, 6, 0), rgb(255, 176, 0), rgb(255, 210, 120), rgb(255, 90, 0),
             rgb(255, 220, 90), rgb(255, 140, 0), rgb(255, 60, 30), rgb(255, 235, 200)},
            {rgb(4, 8, 20), rgb(120, 220, 255), rgb(0, 255, 255), rgb(120, 140, 255),
             rgb(210, 240, 255), rgb(90, 180, 255), rgb(255, 70, 90), rgb(235, 245, 255)},
    };

    /** A deep wash painted behind the starfield, one per theme. */
    private static final int[] NEBULA = {
            rgb(10, 40, 22), rgb(40, 10, 58), rgb(44, 24, 0), rgb(8, 26, 60)};

    /** Which bundled planet bitmap sits in the far background, per theme. */
    private static final int[] PLANET = {0, 1, 2, 3};

    private static int index = 0;
    public static int token = 0;

    private static int rgb(int r, int g, int b) {
        return Color.rgb(r, g, b);
    }

    public static int current() {
        return index;
    }

    public static String currentName() {
        return NAMES[index];
    }

    public static void apply(int themeIndex) {
        int next = ((themeIndex % NAMES.length) + NAMES.length) % NAMES.length;
        if (next == index && token != 0) {
            return;
        }
        index = next;
        token++;
    }

    public static void cycle() {
        apply(index + 1);
    }

    public static int color(int role) {
        return PALETTES[index][role];
    }

    public static int nebula() {
        return NEBULA[index];
    }

    public static int planetIndex() {
        return PLANET[index];
    }

    /** Scale a colour's brightness, keeping its alpha. */
    public static int shade(int color, float factor) {
        return Color.argb(Color.alpha(color),
                clamp(Color.red(color) * factor),
                clamp(Color.green(color) * factor),
                clamp(Color.blue(color) * factor));
    }

    public static int withAlpha(int color, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int mix(int a, int b, float amount) {
        float k = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                clamp(Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                clamp(Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                clamp(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
