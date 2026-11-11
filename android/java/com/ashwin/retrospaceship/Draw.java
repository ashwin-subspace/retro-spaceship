package com.ashwin.retrospaceship;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * The only thing in the game that touches a {@link Canvas}.
 *
 * <p>Sprites are drawn through a cached multiply colour filter, so a white
 * source bitmap comes out in the active theme colour with its own shading
 * intact. The filters are cached per colour rather than rebuilt per draw:
 * allocating a {@code PorterDuffColorFilter} for every bullet on a bullet-hell
 * screen is the difference between a smooth frame and a stuttering one.
 *
 * <p>Glow is faked by drawing an oversized dim copy under the bright sprite.
 * That reads like a CRT bloom and, unlike a real blur or an additive xfermode,
 * it is supported by every hardware canvas the app will meet.
 */
public final class Draw {

    public Canvas canvas;
    public final Assets assets;

    private final Map<Integer, Paint> tintCache = new HashMap<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF scratch = new RectF();
    private final android.graphics.Path path = new android.graphics.Path();

    public Draw(Assets assets) {
        this.assets = assets;
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
    }

    // ---------- primitives ----------

    public void rect(float x, float y, float w, float h, int color) {
        fill.setColor(color);
        canvas.drawRect(x, y, x + w, y + h, fill);
    }

    public void rectOutline(float x, float y, float w, float h, int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        canvas.drawRect(x, y, x + w, y + h, stroke);
    }

    public void circle(float cx, float cy, float r, int color) {
        fill.setColor(color);
        canvas.drawCircle(cx, cy, r, fill);
    }

    public void circleOutline(float cx, float cy, float r, int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        canvas.drawCircle(cx, cy, r, stroke);
    }

    public void line(float x1, float y1, float x2, float y2, int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        canvas.drawLine(x1, y1, x2, y2, stroke);
    }

    public void arc(float cx, float cy, float r, float startDeg, float sweepDeg,
                    int color, float width) {
        scratch.set(cx - r, cy - r, cx + r, cy + r);
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        canvas.drawArc(scratch, startDeg, sweepDeg, false, stroke);
    }

    public void polygon(float[] points, int color) {
        if (points.length < 6) {
            return;
        }
        // Reused rather than allocated: this runs once per telegraphing enemy per
        // frame, and a fresh Path each time is pure GC pressure during a wave.
        path.rewind();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) {
            path.lineTo(points[i], points[i + 1]);
        }
        path.close();
        fill.setColor(color);
        canvas.drawPath(path, fill);
    }

    // ---------- sprites ----------

    private Paint tint(int color) {
        int key = color | 0xFF000000;
        Paint paint = tintCache.get(key);
        if (paint == null) {
            paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new PorterDuffColorFilter(key, PorterDuff.Mode.MULTIPLY));
            tintCache.put(key, paint);
        }
        return paint;
    }

    /** Draw a sprite centred on a point, tinted and scaled to a target height. */
    public void sprite(Bitmap bitmap, float cx, float cy, float targetH,
                       float rotationDeg, int color, int alpha) {
        if (bitmap == null) {
            return;
        }
        float scale = targetH / bitmap.getHeight();
        Paint paint = tint(color);
        paint.setAlpha(alpha);
        canvas.save();
        canvas.translate(cx, cy);
        if (rotationDeg != 0f) {
            canvas.rotate(rotationDeg);
        }
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, -bitmap.getWidth() * 0.5f, -bitmap.getHeight() * 0.5f, paint);
        canvas.restore();
        paint.setAlpha(255);
    }

    public void sprite(String name, float cx, float cy, float targetH, int color) {
        sprite(assets.get(name), cx, cy, targetH, 0f, color, 255);
    }

    /**
     * A sprite with a soft halo behind it.
     *
     * <p>{@code bloom} is how much larger the halo is than the sprite; a value
     * around 1.8 reads as a hot neon object, lower values as a solid one.
     */
    public void glowSprite(Bitmap bitmap, float cx, float cy, float targetH,
                           float rotationDeg, int color, float bloom, int coreAlpha) {
        if (bitmap == null) {
            return;
        }
        sprite(bitmap, cx, cy, targetH * bloom, rotationDeg, Theme.shade(color, 0.85f), 60);
        sprite(bitmap, cx, cy, targetH, rotationDeg, color, coreAlpha);
    }

    // ---------- layered sprites ----------

    // Tuned against tools/preview_ships.py, which composites these exact layers
    // offline. Contrast comes from DARKENING the hull, not from whitening the
    // panels: on several sprites the lit region is 70-95% of the whole ship, so
    // pushing it toward white washed the orange "tough" enemies out to cream and
    // cost them their threat colour.
    private static final float HALO_SHADE = 0.85f;
    private static final float HULL_SHADE = 0.55f;
    private static final float LIT_MIX = 0.22f;
    private static final int LINE_ALPHA = 185;

    /** Set false to drop the linework layer, following the EFFECTS setting. */
    public boolean detailLines = true;

    /**
     * Draw a sprite as four layers rather than one flat tint.
     *
     * <p>halo, then a deepened hull, then the lit facets, then the rim and panel
     * lines. All four share a {@code targetH} and rotation, and the masks are cut
     * from the finished bitmap at staging time, so they register exactly.
     *
     * <p>Falls back to {@link #glowSprite} when a sprite has no detail layers,
     * which is how projectiles keep their deliberately flat, unmistakable shapes.
     */
    public void detailSprite(String name, float cx, float cy, float targetH,
                             float rotationDeg, int color, int accent,
                             float bloom, int alpha) {
        Bitmap base = assets.get(name);
        if (base == null) {
            return;
        }
        Bitmap lit = assets.lit(name);
        if (lit == null) {
            glowSprite(base, cx, cy, targetH, rotationDeg, color, bloom, alpha);
            return;
        }
        sprite(base, cx, cy, targetH * bloom, rotationDeg, Theme.shade(color, HALO_SHADE), 60);
        sprite(base, cx, cy, targetH, rotationDeg, Theme.shade(color, HULL_SHADE), alpha);
        sprite(lit, cx, cy, targetH, rotationDeg,
                Theme.mix(color, Theme.color(Theme.WHITE), LIT_MIX), alpha);
        Bitmap line = assets.line(name);
        if (detailLines && line != null) {
            sprite(line, cx, cy, targetH, rotationDeg, accent, Math.min(alpha, LINE_ALPHA));
        }
    }

    /** Untinted draw, for artwork that already carries its own colour. */
    public void raw(Bitmap bitmap, float cx, float cy, float targetH, int alpha) {
        if (bitmap == null) {
            return;
        }
        float scale = targetH / bitmap.getHeight();
        plain.setAlpha(alpha);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, -bitmap.getWidth() * 0.5f, -bitmap.getHeight() * 0.5f, plain);
        canvas.restore();
        plain.setAlpha(255);
    }

    // ---------- text ----------

    public void text(String value, float size, float cx, float cy, int color, int alpha,
                     Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setColor(Theme.withAlpha(color, alpha));
        text.setTextAlign(Paint.Align.CENTER);
        // drawText puts the baseline at cy; shifting by 0.36em centres the glyph
        // body, which is what callers mean by "at this point".
        canvas.drawText(value, cx, cy + size * 0.36f, text);
    }

    public void text(String value, float size, float cx, float cy, int color) {
        text(value, size, cx, cy, color, 255, assets.hud);
    }

    public void textLeft(String value, float size, float x, float cy, int color, int alpha) {
        text.setTypeface(assets.hud);
        text.setTextSize(size);
        text.setColor(Theme.withAlpha(color, alpha));
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, cy + size * 0.36f, text);
    }

    public void textRight(String value, float size, float x, float cy, int color, int alpha) {
        text.setTypeface(assets.hud);
        text.setTextSize(size);
        text.setColor(Theme.withAlpha(color, alpha));
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(value, x, cy + size * 0.36f, text);
    }

    public float textWidth(String value, float size, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        return text.measureText(value);
    }

    public void clear(int color) {
        canvas.drawColor(color, PorterDuff.Mode.SRC);
    }

    public void fullRect(float x, float y, float w, float h, int color, int alpha) {
        fill.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawRect(x, y, x + w, y + h, fill);
    }
}
