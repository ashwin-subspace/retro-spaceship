package com.ashwin.retrospaceship;

import android.graphics.Bitmap;

/**
 * The background: a tinted nebula wash, a slowly turning planet, drifting rock
 * debris, and three parallax star layers.
 *
 * <p>Depth is the whole point. A single flat layer of stars reads as static
 * noise; three layers at different speeds, with real rock sprites tumbling
 * between them, reads as travel. None of it is interactive -- the rocks are
 * scenery and have no collision, so the background can be as busy as it likes
 * without ever making the playfield harder to read.
 */
public final class Starfield {

    private static final int[] LAYER_COUNT = {34, 26, 16};
    private static final float[] LAYER_SPEED = {26f, 62f, 118f};
    private static final float[] LAYER_SIZE = {1f, 1.4f, 2.2f};
    private static final float[] LAYER_BRIGHT = {0.34f, 0.62f, 1f};

    private static final int ROCKS = 7;

    private final float[][] starX = new float[3][];
    private final float[][] starY = new float[3][];

    private final float[] rockX = new float[ROCKS];
    private final float[] rockY = new float[ROCKS];
    private final float[] rockSize = new float[ROCKS];
    private final float[] rockSpeed = new float[ROCKS];
    private final float[] rockSpin = new float[ROCKS];
    private final float[] rockAngle = new float[ROCKS];
    private final int[] rockKind = new int[ROCKS];

    private float planetY;
    private float planetDrift;

    public Starfield() {
        for (int layer = 0; layer < 3; layer++) {
            int n = LAYER_COUNT[layer];
            starX[layer] = new float[n];
            starY[layer] = new float[n];
            for (int i = 0; i < n; i++) {
                starX[layer][i] = Rng.uniform(0f, Cfg.W);
                starY[layer][i] = Rng.uniform(0f, Cfg.H);
            }
        }
        for (int i = 0; i < ROCKS; i++) {
            resetRock(i, Rng.uniform(0f, Cfg.H));
        }
        planetY = Cfg.H * 0.24f;
        planetDrift = 4.5f;
    }

    private void resetRock(int i, float y) {
        rockX[i] = Rng.uniform(-20f, Cfg.W + 20f);
        rockY[i] = y;
        rockSize[i] = Rng.uniform(22f, 74f);
        // Bigger rocks read as nearer, so they travel faster: the size and the
        // speed have to agree or the parallax illusion collapses.
        rockSpeed[i] = 12f + rockSize[i] * 0.55f;
        rockSpin[i] = Rng.uniform(-14f, 14f);
        rockAngle[i] = Rng.uniform(0f, 360f);
        rockKind[i] = Rng.randint(0, 3);
    }

    public void update(float dtMs, float boost) {
        float dts = dtMs / 1000f;
        for (int layer = 0; layer < 3; layer++) {
            float step = LAYER_SPEED[layer] * boost * dts;
            float[] ys = starY[layer];
            float[] xs = starX[layer];
            for (int i = 0; i < ys.length; i++) {
                ys[i] += step;
                if (ys[i] > Cfg.H) {
                    ys[i] = -2f;
                    xs[i] = Rng.uniform(0f, Cfg.W);
                }
            }
        }
        for (int i = 0; i < ROCKS; i++) {
            rockY[i] += rockSpeed[i] * boost * dts;
            rockAngle[i] += rockSpin[i] * dts;
            if (rockY[i] > Cfg.H + rockSize[i]) {
                resetRock(i, -rockSize[i] - Rng.uniform(0f, 240f));
            }
        }
        planetY += planetDrift * boost * dts;
        if (planetY > Cfg.H + 200f) {
            planetY = -200f;
        }
    }

    public void draw(Draw d) {
        int base = Theme.color(Theme.BLACK);
        d.clear(base);

        // A wash of the theme's deep tone, brightest at the top, so the field
        // has a horizon instead of being a flat black rectangle.
        int nebula = Theme.nebula();
        int bands = 8;
        for (int i = 0; i < bands; i++) {
            float y = Cfg.H * i / bands;
            int alpha = (int) (46 * (1f - i / (float) bands));
            d.fullRect(0, y, Cfg.W, Cfg.H / bands + 1, nebula, alpha);
        }

        Bitmap planet = d.assets.planet(Theme.planetIndex());
        d.raw(planet, Cfg.W * 0.72f, planetY, 264f, 74);

        for (int i = 0; i < ROCKS; i++) {
            int alpha = (int) (40 + rockSize[i] * 1.6f);
            // Faceted like everything else, but the base tone is lifted to offset
            // the hull darkening, and the accent is dim: these are scenery and
            // must not pull attention from the bullets in front of them.
            d.detailSprite(Assets.rockName(rockKind[i]), rockX[i], rockY[i], rockSize[i],
                    rockAngle[i], Theme.shade(Theme.color(Theme.WHITE), 0.55f),
                    Theme.shade(Theme.color(Theme.WHITE), 0.45f), 1.2f,
                    Math.min(150, alpha));
        }

        int white = Theme.color(Theme.WHITE);
        for (int layer = 0; layer < 3; layer++) {
            int color = Theme.shade(white, LAYER_BRIGHT[layer]);
            float size = LAYER_SIZE[layer];
            float[] xs = starX[layer];
            float[] ys = starY[layer];
            for (int i = 0; i < xs.length; i++) {
                d.rect(xs[i], ys[i], size, size, color);
            }
        }
    }
}
