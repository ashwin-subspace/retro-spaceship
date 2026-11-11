package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.List;

/**
 * Every visual effect, behind a verb-shaped API.
 *
 * <p>Gameplay calls {@code fx.shake()}, {@code fx.burst()}, {@code fx.popup()}
 * and never touches the internals, so the whole game's feel can be retuned or
 * switched off in one place.
 *
 * <p>The split between clocks is deliberate: particles advance on <em>game</em>
 * time so they freeze with the world during a hitstop, while shake, flash and
 * vignette advance on <em>real</em> time so they keep moving. That contrast is
 * what makes a hitstop read as impact rather than as a dropped frame.
 */
public final class Fx {

    // Structure-of-arrays particles: one allocation at startup, none per burst.
    private final int capacity;
    private final float[] px, py, pvx, pvy, plife, pmax, psize;
    private final int[] pcolor;
    private int count = 0;

    private final List<Popup> popups = new ArrayList<>();
    private final List<Shockwave> waves = new ArrayList<>();

    private float shake = 0f;
    private float shakeDirX = 0f, shakeDirY = 0f;
    public float shakeX = 0f, shakeY = 0f;

    private int flashColor = 0;
    private float flashRemaining = 0f, flashTotal = 0f;
    private int flashAlpha = 0;

    private int vignetteColor = 0;
    private float vignetteRemaining = 0f, vignetteTotal = 0f;
    private int vignetteAlpha = 0;

    private boolean lowQuality = false;

    public Fx() {
        capacity = Cfg.MAX_PARTICLES;
        px = new float[capacity];
        py = new float[capacity];
        pvx = new float[capacity];
        pvy = new float[capacity];
        plife = new float[capacity];
        pmax = new float[capacity];
        psize = new float[capacity];
        pcolor = new int[capacity];
    }

    public void setLowQuality(boolean value) {
        lowQuality = value;
    }

    public void clear() {
        count = 0;
        popups.clear();
        waves.clear();
        shake = 0f;
        shakeX = shakeY = 0f;
        flashRemaining = 0f;
        vignetteRemaining = 0f;
    }

    // ---------- particles ----------

    public void burst(float x, float y, int color, int amount) {
        burst(x, y, color, amount, Float.NaN, 70f, 330f, 240f, 640f, 2f, 4f, (float) Math.PI);
    }

    /**
     * Spawn particles.
     *
     * <p>{@code direction} biases them along an angle in radians; pass
     * {@link Float#NaN} for an even spray in all directions.
     */
    public void burst(float x, float y, int color, int amount, float direction,
                      float speedLo, float speedHi, float lifeLo, float lifeHi,
                      float sizeLo, float sizeHi, float spread) {
        if (amount <= 0) {
            return;
        }
        if (lowQuality) {
            amount = Math.max(1, amount / 2);
        }
        for (int i = 0; i < amount; i++) {
            if (count >= capacity) {
                // Drop the oldest half rather than refusing newer, more relevant
                // effects -- the explosion you just caused matters more than the
                // tail of one from two seconds ago.
                compactOldest();
                if (count >= capacity) {
                    return;
                }
            }
            float angle = Float.isNaN(direction)
                    ? Rng.uniform(0f, Patterns.TAU)
                    : Rng.uniform(direction - spread * 0.5f, direction + spread * 0.5f);
            float speed = Rng.uniform(speedLo, speedHi);
            float life = Rng.uniform(lifeLo, lifeHi);
            px[count] = x;
            py[count] = y;
            pvx[count] = (float) Math.cos(angle) * speed;
            pvy[count] = (float) Math.sin(angle) * speed;
            plife[count] = life;
            pmax[count] = life;
            psize[count] = Rng.uniform(sizeLo, sizeHi);
            pcolor[count] = color;
            count++;
        }
    }

    private void compactOldest() {
        int keep = capacity / 2;
        int from = count - keep;
        if (from <= 0) {
            return;
        }
        System.arraycopy(px, from, px, 0, keep);
        System.arraycopy(py, from, py, 0, keep);
        System.arraycopy(pvx, from, pvx, 0, keep);
        System.arraycopy(pvy, from, pvy, 0, keep);
        System.arraycopy(plife, from, plife, 0, keep);
        System.arraycopy(pmax, from, pmax, 0, keep);
        System.arraycopy(psize, from, psize, 0, keep);
        System.arraycopy(pcolor, from, pcolor, 0, keep);
        count = keep;
    }

    // ---------- one-shot effects ----------

    public void popup(float x, float y, String text, int color, float size) {
        if (popups.size() > 24) {
            popups.remove(0);
        }
        popups.add(new Popup(x, y, text, color, size));
    }

    public void shockwave(float x, float y, float r0, float r1, float ms, int color, float width) {
        if (lowQuality && waves.size() > 4) {
            return;
        }
        waves.add(new Shockwave(x, y, r0, r1, ms, color, width));
    }

    public void shake(float power) {
        shake(power, Float.NaN, Float.NaN);
    }

    /**
     * Shake the screen. When a direction is supplied the shake is biased along
     * it, so a hit from the left throws the view right and the source of the
     * damage is legible even at a glance.
     */
    public void shake(float power, float dirX, float dirY) {
        shake = Math.min(Cfg.SHAKE_MAX, Math.max(shake, power));
        if (!Float.isNaN(dirX) && !Float.isNaN(dirY)) {
            float length = (float) Math.hypot(dirX, dirY);
            if (length > 0.001f) {
                shakeDirX = dirX / length;
                shakeDirY = dirY / length;
                return;
            }
        }
        shakeDirX = shakeDirY = 0f;
    }

    public void flash(int color, float ms, int alpha) {
        flashColor = color;
        flashTotal = ms;
        flashRemaining = ms;
        flashAlpha = alpha;
    }

    public void vignette(int color, float ms, int alpha) {
        vignetteColor = color;
        vignetteTotal = ms;
        vignetteRemaining = Math.max(vignetteRemaining, ms);
        vignetteAlpha = alpha;
    }

    // ---------- frame ----------

    /**
     * @param gameDt gameplay milliseconds, zero during a hitstop
     * @param realDt wall-clock milliseconds, never zero
     */
    public void update(float gameDt, float realDt) {
        updateParticles(gameDt);

        for (int i = popups.size() - 1; i >= 0; i--) {
            if (!popups.get(i).update(gameDt)) {
                popups.remove(i);
            }
        }
        for (int i = waves.size() - 1; i >= 0; i--) {
            if (!waves.get(i).update(gameDt)) {
                waves.remove(i);
            }
        }

        if (shake > 0f) {
            float amount = shake;
            if (shakeDirX != 0f || shakeDirY != 0f) {
                shakeX = shakeDirX * amount + Rng.uniform(-amount, amount) * 0.4f;
                shakeY = shakeDirY * amount + Rng.uniform(-amount, amount) * 0.4f;
            } else {
                shakeX = Rng.uniform(-amount, amount);
                shakeY = Rng.uniform(-amount, amount);
            }
            shake = Math.max(0f, shake - Cfg.SHAKE_DECAY_PER_SEC * amount * (realDt / 1000f)
                    - realDt * 0.02f);
        } else {
            shakeX = shakeY = 0f;
        }

        flashRemaining = Math.max(0f, flashRemaining - realDt);
        vignetteRemaining = Math.max(0f, vignetteRemaining - realDt);
    }

    private void updateParticles(float dt) {
        if (count == 0 || dt <= 0f) {
            return;
        }
        float dts = dt / 1000f;
        float drag = (float) Math.pow(0.94, dts * 60f);
        int write = 0;
        for (int i = 0; i < count; i++) {
            float life = plife[i] - dt;
            if (life <= 0f) {
                continue;
            }
            float x = px[i] + pvx[i] * dts;
            float y = py[i] + pvy[i] * dts;
            px[write] = x;
            py[write] = y;
            pvx[write] = pvx[i] * drag;
            pvy[write] = pvy[i] * drag;
            plife[write] = life;
            pmax[write] = pmax[i];
            psize[write] = psize[i];
            pcolor[write] = pcolor[i];
            write++;
        }
        count = write;
    }

    // ---------- draw ----------

    /** Particles, shockwaves and floating text -- drawn above the world. */
    public void draw(Draw d) {
        for (int i = 0; i < count; i++) {
            float fade = plife[i] / pmax[i];
            int alpha = (int) (255 * Math.min(1f, fade * 1.4f));
            d.rect(px[i] - psize[i] * 0.5f, py[i] - psize[i] * 0.5f,
                    psize[i], psize[i], Theme.withAlpha(pcolor[i], alpha));
        }
        for (Shockwave w : waves) {
            w.draw(d);
        }
        for (Popup p : popups) {
            p.draw(d);
        }
    }

    /** Full-screen flash and edge vignette -- drawn above everything else. */
    public void drawScreen(Draw d, float w, float h) {
        if (vignetteRemaining > 0f) {
            float fade = vignetteRemaining / Math.max(1f, vignetteTotal);
            int alpha = (int) (vignetteAlpha * fade);
            float band = h * 0.16f;
            d.fullRect(0, 0, w, band, vignetteColor, alpha);
            d.fullRect(0, h - band, w, band, vignetteColor, alpha);
            d.fullRect(0, 0, band * 0.6f, h, vignetteColor, alpha);
            d.fullRect(w - band * 0.6f, 0, band * 0.6f, h, vignetteColor, alpha);
        }
        if (flashRemaining > 0f) {
            float fade = flashRemaining / Math.max(1f, flashTotal);
            d.fullRect(0, 0, w, h, flashColor, (int) (flashAlpha * fade));
        }
    }

    public int particleCount() {
        return count;
    }

    // ---------- effect types ----------

    private static final class Popup {
        final float x;
        float y;
        final String text;
        final int color;
        final float size;
        float life = 900f;

        Popup(float x, float y, String text, int color, float size) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
            this.size = size;
        }

        boolean update(float dt) {
            life -= dt;
            y -= dt * 0.032f;
            return life > 0f;
        }

        void draw(Draw d) {
            int alpha = (int) (255 * Math.min(1f, life / 400f));
            d.text(text, size, x, y, color, alpha, d.assets.hud);
        }
    }

    private static final class Shockwave {
        final float x, y, r0, r1, total, width;
        final int color;
        float life;

        Shockwave(float x, float y, float r0, float r1, float ms, int color, float width) {
            this.x = x;
            this.y = y;
            this.r0 = r0;
            this.r1 = r1;
            this.total = Math.max(1f, ms);
            this.life = this.total;
            this.color = color;
            this.width = width;
        }

        boolean update(float dt) {
            life -= dt;
            return life > 0f;
        }

        void draw(Draw d) {
            float progress = 1f - life / total;
            float radius = r0 + (r1 - r0) * progress;
            int alpha = (int) (220 * (1f - progress));
            d.circleOutline(x, y, radius, Theme.withAlpha(color, alpha), width);
        }
    }
}
