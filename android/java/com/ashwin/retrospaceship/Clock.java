package com.ashwin.retrospaceship;

/**
 * Delta-time clock with hitstop and slow motion.
 *
 * <p>Gameplay measures time against {@link #t}, an accumulated <em>game</em>
 * millisecond counter that only advances while the game is actually running.
 * Pausing simply stops calling {@link #tick}, so no code ever has to shift
 * stored deadlines. Menus and screen-level effects use wall time instead, which
 * is what lets them keep animating through a hitstop freeze -- that contrast is
 * exactly what makes a hitstop read as impact rather than as a dropped frame.
 */
public final class Clock {
    public double t = 0.0;
    public float dt = 0f;
    public float realDt = 0f;
    public float scale = 1f;

    private float hitstopRemaining = 0f;
    private float slowRemaining = 0f;
    private float slowScale = 1f;

    public float tick(float realMs) {
        float real = Math.max(0f, realMs);
        // Guard against the spiral of death after a stall (app resume, GC hitch):
        // never simulate more than 50ms in a single step.
        real = Math.min(real, 50f);
        realDt = real;

        if (hitstopRemaining > 0f) {
            hitstopRemaining = Math.max(0f, hitstopRemaining - real);
            dt = 0f;
            return 0f;
        }
        if (slowRemaining > 0f) {
            slowRemaining = Math.max(0f, slowRemaining - real);
            scale = slowRemaining <= 0f ? 1f : slowScale;
        } else {
            scale = 1f;
        }
        dt = real * scale;
        t += dt;
        return dt;
    }

    /** Scaled delta in seconds -- multiply per-second speeds by this. */
    public float dts() {
        return dt / 1000f;
    }

    public boolean frozen() {
        return hitstopRemaining > 0f;
    }

    public void hitstop(float ms) {
        hitstopRemaining = Math.min(Cfg.HITSTOP_MAX_MS, Math.max(hitstopRemaining, ms));
    }

    /** Run gameplay at {@code newScale} speed for {@code ms} of real time. */
    public void slowmo(float newScale, float ms) {
        // A stronger (slower) request always wins over a weaker active one.
        if (slowRemaining <= 0f || newScale < slowScale) {
            slowScale = Math.max(0.05f, newScale);
        }
        slowRemaining = Math.max(slowRemaining, ms);
    }

    public void clearEffects() {
        hitstopRemaining = 0f;
        slowRemaining = 0f;
        slowScale = 1f;
        scale = 1f;
    }
}
