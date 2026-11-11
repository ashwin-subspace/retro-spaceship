package com.ashwin.retrospaceship;

/**
 * Turns raw pointer events into the four things the game actually needs:
 * steering, bomb, overdrive, and a precision hint.
 *
 * <p>The playfield is the whole screen now, so there are no buttons. Every
 * gesture had to survive one hard constraint: it must work <em>while a finger is
 * already dragging the ship</em>, because that finger is never free during a
 * wave. That rules out anything requiring the steering hand to hold still, and
 * it rules out double-taps, which mean lifting off mid-dodge.
 *
 * <p>What is left is the second finger, and the only thing that distinguishes
 * two second-finger gestures is how long it stays down:
 *
 * <ul>
 *   <li><b>Tap</b> fires a <b>bomb</b>. Bombs are the panic button, so they get
 *       the fastest gesture -- nothing to hold, nothing to repeat. It must be
 *       the second finger: a short, quick correction with the steering finger
 *       is indistinguishable from a tap, and would spend a bomb by accident.
 *   <li><b>Press and hold</b> triggers <b>overdrive</b>. Spending overdrive is a
 *       deliberate choice about timing rather than a reflex, so it can afford to
 *       cost a third of a second.
 *   <li><b>Moving the steering finger slowly</b> raises the precision flag. What
 *       used to be a "focus" button is now just a consequence of how the player
 *       is already moving; it shows the graze ring, so the mechanic teaches
 *       itself instead of hiding behind a toggle.
 * </ul>
 */
public final class Gestures {

    public interface Listener {
        void onDragStart(float x, float y);

        void onDrag(float x, float y);

        void onDragEnd();

        void onBomb();

        void onOverdrive();

        void onPrecision(boolean precise);
    }

    /** How far a pointer may travel and still count as a tap, in world units. */
    private static final float TAP_SLOP = 26f;
    private static final float TAP_MS = 300f;
    /** How long a second finger must rest before it means overdrive. */
    private static final float HOLD_MS = 320f;

    // Precision has separate on and off thresholds; a single one would flicker
    // the graze ring on and off through the noise of a normal drag.
    private static final float PRECISE_ON = 55f;
    private static final float PRECISE_OFF = 115f;

    private static final int SLOTS = 4;

    private final int[] id = new int[SLOTS];
    private final float[] downX = new float[SLOTS];
    private final float[] downY = new float[SLOTS];
    private final double[] downT = new double[SLOTS];
    private final float[] travel = new float[SLOTS];
    private final float[] lastX = new float[SLOTS];
    private final float[] lastY = new float[SLOTS];
    private final boolean[] heldFired = new boolean[SLOTS];
    private int active = 0;

    private int primary = -1;

    private float speedEma = 0f;
    private double lastMoveT = 0.0;
    private boolean precise = false;

    private final Listener listener;

    public Gestures(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < SLOTS; i++) {
            id[i] = -1;
        }
    }

    /** Forget all touch state -- used whenever the game changes screen. */
    public void reset() {
        for (int i = 0; i < SLOTS; i++) {
            id[i] = -1;
            heldFired[i] = false;
        }
        active = 0;
        primary = -1;
        setPrecise(false);
    }

    private int slotOf(int pointerId) {
        for (int i = 0; i < SLOTS; i++) {
            if (id[i] == pointerId) {
                return i;
            }
        }
        return -1;
    }

    public void down(int pointerId, float x, float y, double now) {
        int slot = slotOf(-1);
        if (slot < 0) {
            return;
        }
        id[slot] = pointerId;
        downX[slot] = lastX[slot] = x;
        downY[slot] = lastY[slot] = y;
        downT[slot] = now;
        travel[slot] = 0f;
        heldFired[slot] = false;
        active++;

        if (primary < 0) {
            primary = pointerId;
            speedEma = 0f;
            lastMoveT = now;
            listener.onDragStart(x, y);
        }
    }

    public void move(int pointerId, float x, float y, double now) {
        int slot = slotOf(pointerId);
        if (slot < 0) {
            return;
        }
        float dx = x - lastX[slot];
        float dy = y - lastY[slot];
        travel[slot] = Math.max(travel[slot], dist(x, y, downX[slot], downY[slot]));
        lastX[slot] = x;
        lastY[slot] = y;

        if (pointerId != primary) {
            return;
        }
        float dtSeconds = (float) Math.max(1.0, now - lastMoveT) / 1000f;
        lastMoveT = now;
        float speed = (float) Math.hypot(dx, dy) / dtSeconds;
        // Smoothed, because a single fast sample between two slow ones should
        // not drop the player out of precision mid-thread.
        speedEma += (speed - speedEma) * 0.28f;
        if (precise && speedEma > PRECISE_OFF) {
            setPrecise(false);
        } else if (!precise && speedEma < PRECISE_ON) {
            setPrecise(true);
        }
        listener.onDrag(x, y);
    }

    /**
     * Called once per frame so a held finger can fire without needing to move.
     *
     * <p>A hold produces no touch events at all, so it can only be noticed by
     * the passage of time.
     */
    public void tick(double now) {
        for (int i = 0; i < SLOTS; i++) {
            if (id[i] < 0 || id[i] == primary || heldFired[i]) {
                continue;
            }
            if (travel[i] <= TAP_SLOP && (now - downT[i]) >= HOLD_MS) {
                heldFired[i] = true;
                listener.onOverdrive();
            }
        }
    }

    public void up(int pointerId, float x, float y, double now) {
        int slot = slotOf(pointerId);
        if (slot < 0) {
            return;
        }
        boolean wasTap = travel[slot] <= TAP_SLOP && (now - downT[slot]) <= TAP_MS;
        // Only a second finger can bomb, and never one that already spent itself
        // on overdrive. Excluding the steering finger is the point: a small quick
        // correction of the ship looks exactly like a tap, and would otherwise
        // throw away a bomb every time the player made one.
        if (wasTap && !heldFired[slot] && pointerId != primary) {
            listener.onBomb();
        }

        id[slot] = -1;
        heldFired[slot] = false;
        active = Math.max(0, active - 1);

        if (pointerId == primary) {
            primary = -1;
            setPrecise(false);
            listener.onDragEnd();
            // Hand steering to whichever finger is still down, so lifting the
            // first of two fingers does not drop control of the ship.
            for (int i = 0; i < SLOTS; i++) {
                if (id[i] >= 0) {
                    primary = id[i];
                    speedEma = 0f;
                    lastMoveT = now;
                    listener.onDragStart(lastX[i], lastY[i]);
                    break;
                }
            }
        }
    }

    private void setPrecise(boolean value) {
        if (precise == value) {
            return;
        }
        precise = value;
        listener.onPrecision(value);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }
}
