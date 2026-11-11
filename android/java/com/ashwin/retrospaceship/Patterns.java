package com.ashwin.retrospaceship;

/**
 * Bullet pattern maths. Pure functions, no state, no drawing.
 *
 * <p>Patterns are deliberately chunked into readable lines and arcs. Scattered
 * single bullets read as unfair; grouped shapes read as a puzzle with a route
 * through it, which is the whole appeal of the genre.
 *
 * <p>Velocities are returned in a flat {@code float[]} of {@code vx, vy} pairs
 * so a dense boss pattern does not allocate an object per bullet.
 */
public final class Patterns {
    private Patterns() {}

    public static final float TAU = (float) (Math.PI * 2.0);

    /** One velocity pointed from a source at a target. */
    public static float[] aimed(float fromX, float fromY, float toX, float toY, float speed) {
        float dx = toX - fromX;
        float dy = toY - fromY;
        float d = (float) Math.hypot(dx, dy);
        if (d <= 0f) {
            d = 1f;
        }
        return new float[]{dx / d * speed, dy / d * speed};
    }

    public static float angleTo(float fromX, float fromY, float toX, float toY) {
        return (float) Math.atan2(toY - fromY, toX - fromX);
    }

    public static float[] fromAngle(float angle, float speed) {
        return new float[]{(float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed};
    }

    /** {@code count} velocities spread evenly across {@code arc} radians. */
    public static float[] fan(float centerAngle, int count, float arc, float speed) {
        if (count <= 1) {
            return fromAngle(centerAngle, speed);
        }
        float step = arc / (count - 1);
        float start = centerAngle - arc * 0.5f;
        float[] out = new float[count * 2];
        for (int i = 0; i < count; i++) {
            float a = start + step * i;
            out[i * 2] = (float) Math.cos(a) * speed;
            out[i * 2 + 1] = (float) Math.sin(a) * speed;
        }
        return out;
    }

    /** A full circle of velocities. */
    public static float[] ring(int count, float speed, float offset) {
        if (count <= 0) {
            return new float[0];
        }
        float step = TAU / count;
        float[] out = new float[count * 2];
        for (int i = 0; i < count; i++) {
            float a = offset + step * i;
            out[i * 2] = (float) Math.cos(a) * speed;
            out[i * 2 + 1] = (float) Math.sin(a) * speed;
        }
        return out;
    }

    /** One shot of a continuing spiral; call once per shot with a rising index. */
    public static float[] spiral(int index, int countPerTurn, float speed) {
        float step = TAU / Math.max(1, countPerTurn);
        return fromAngle(step * index, speed);
    }

    /** A fan centred on the player -- the workhorse aimed pattern. */
    public static float[] burstAt(float fromX, float fromY, float toX, float toY,
                                  int count, float arc, float speed) {
        return fan(angleTo(fromX, fromY, toX, toY), count, arc, speed);
    }

    /**
     * Player weapon geometry: angle offsets in radians for each weapon level.
     * The spread widens with level rather than only firing faster, so an
     * upgrade changes how the weapon is aimed, not just how loud it is.
     */
    public static float[] spreadOffsets(int level) {
        switch (Math.max(1, Math.min(5, level))) {
            case 2: return new float[]{-0.08f, 0.08f};
            case 3: return new float[]{-0.16f, 0f, 0.16f};
            case 4: return new float[]{-0.24f, -0.08f, 0.08f, 0.24f};
            case 5: return new float[]{-0.32f, -0.16f, 0f, 0.16f, 0.32f};
            default: return new float[]{0f};
        }
    }

    /** Rotate a velocity toward a target by at most {@code turnRate} radians. */
    public static float[] homingTurn(float vx, float vy, float targetDx, float targetDy,
                                     float speed, float turnRate) {
        float current = (float) Math.atan2(vy, vx);
        float desired = (float) Math.atan2(targetDy, targetDx);
        float delta = desired - current;
        // Wrap into (-pi, pi] so the turn always takes the short way round.
        delta = (float) (((delta + Math.PI) % TAU + TAU) % TAU - Math.PI);
        delta = Math.max(-turnRate, Math.min(turnRate, delta));
        return fromAngle(current + delta, speed);
    }
}
