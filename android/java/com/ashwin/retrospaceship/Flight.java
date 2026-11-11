package com.ashwin.retrospaceship;

/**
 * How the ship moves: a mass with thrusters, not a cursor.
 *
 * <p>The old model eased the ship's <em>position</em> toward the finger. That is
 * exactly what a cursor does -- stop moving your finger and the ship stops dead,
 * because it never had a velocity to lose. Everything here exists to give the
 * ship momentum without giving up the accuracy a bullet-hell game needs.
 *
 * <p>The core is a damped follower on <em>both</em> position and velocity:
 *
 * <pre>a = (target - p) * STIFFNESS + (targetVelocity - v) * DAMPING</pre>
 *
 * <p>The velocity term is what makes this usable. A plain position spring lags
 * behind a moving target in proportion to how fast it is moving -- at a quick
 * drag that is tens of units of error, which in this game is the difference
 * between threading a gap and dying in it. Matching the finger's velocity as
 * well cancels that lag almost entirely, so steady tracking stays honest while
 * starts, stops and direction changes get real acceleration curves.
 *
 * <p>Deliberately free of Android imports, so the whole feel can be measured on
 * a desktop JVM instead of judged by eye through an emulator.
 */
public final class Flight {

    // Instance fields rather than constants so the desktop harness can sweep them
    // and measure the result, instead of the feel being chosen by guesswork.
    /** Pull toward the finger, in units per second squared per unit of error. */
    public float stiffness = 700f;
    /**
     * Resistance to changing velocity. This is the weight knob: it is the inverse
     * of how long the engines take to reach a new speed, so a high value slaves
     * the ship to the finger and feels like a cursor no matter what else is tuned.
     */
    public float damping = 34f;
    /** How quickly the estimate of the finger's own velocity reacts. */
    public float targetSmooth = 0.5f;
    /** Velocity kept per 60fps frame after release, so the ship coasts to a halt. */
    public float releaseDrag = 0.55f;

    /** Degrees of roll per unit of sideways speed, and the limit. */
    public static final float BANK_PER_SPEED = 0.075f;
    public static final float BANK_MAX = 32f;
    /** How fast the roll catches up; low enough that the ship visibly leans late. */
    public static final float BANK_RESPONSE = 9f;

    /** The spring is integrated in steps no longer than this, for stability. */
    private static final float MAX_STEP = 1f / 120f;
    private static final int MAX_SUBSTEPS = 8;

    public float x, y;
    public float vx, vy;
    public float bank;

    private float targetX, targetY;
    private float lastTargetX, lastTargetY;
    private float targetVx, targetVy;
    private boolean piloting;

    public void reset(float px, float py) {
        x = targetX = lastTargetX = px;
        y = targetY = lastTargetY = py;
        vx = vy = 0f;
        targetVx = targetVy = 0f;
        bank = 0f;
        piloting = false;
    }

    /** Take the controls, starting from wherever the ship currently is. */
    public void begin() {
        targetX = lastTargetX = x;
        targetY = lastTargetY = y;
        targetVx = targetVy = 0f;
        piloting = true;
    }

    public void setTarget(float tx, float ty) {
        targetX = tx;
        targetY = ty;
    }

    public void end() {
        piloting = false;
    }

    public boolean piloting() {
        return piloting;
    }

    public float speed() {
        return (float) Math.hypot(vx, vy);
    }

    /**
     * Advance one frame.
     *
     * <p>Bounds are passed in rather than read from config so this class stays
     * free of anything Android, and therefore testable.
     */
    public void step(float dts, float minX, float maxX, float minY, float maxY) {
        if (dts <= 0f) {
            return;
        }
        if (piloting) {
            // The finger's own velocity, measured across the frame. Smoothed,
            // because one jittery sample should not shove the ship.
            float rawVx = (targetX - lastTargetX) / dts;
            float rawVy = (targetY - lastTargetY) / dts;
            targetVx += (rawVx - targetVx) * targetSmooth;
            targetVy += (rawVy - targetVy) * targetSmooth;
        } else {
            targetVx = targetVy = 0f;
        }
        lastTargetX = targetX;
        lastTargetY = targetY;

        // A stiff spring plus a long frame is how explicit integration explodes.
        // Substepping costs a handful of multiplies and removes the failure mode.
        int steps = Math.max(1, Math.min(MAX_SUBSTEPS, (int) Math.ceil(dts / MAX_STEP)));
        float h = dts / steps;
        for (int i = 0; i < steps; i++) {
            if (piloting) {
                float ax = (targetX - x) * stiffness + (targetVx - vx) * damping;
                float ay = (targetY - y) * stiffness + (targetVy - vy) * damping;
                vx += ax * h;
                vy += ay * h;
            } else {
                // Released: no pull, just drag. The ship glides to a stop instead
                // of freezing the instant the finger leaves the glass.
                float decay = (float) Math.pow(releaseDrag, h * 60f);
                vx *= decay;
                vy *= decay;
            }
            x += vx * h;
            y += vy * h;
            clamp(minX, maxX, minY, maxY);
        }

        // The roll follows real sideways velocity and lags behind it, so the ship
        // leans into a turn and rights itself afterwards rather than snapping.
        float bankTarget = Math.max(-BANK_MAX, Math.min(BANK_MAX, vx * BANK_PER_SPEED));
        bank += (bankTarget - bank) * Math.min(1f, dts * BANK_RESPONSE);
    }

    private void clamp(float minX, float maxX, float minY, float maxY) {
        if (x < minX) {
            x = minX;
            // Kill the velocity into the wall, or the spring would keep pressing
            // and then fling the ship away when the finger finally turns back.
            vx = Math.max(0f, vx);
        } else if (x > maxX) {
            x = maxX;
            vx = Math.min(0f, vx);
        }
        if (y < minY) {
            y = minY;
            vy = Math.max(0f, vy);
        } else if (y > maxY) {
            y = maxY;
            vy = Math.min(0f, vy);
        }
    }
}
