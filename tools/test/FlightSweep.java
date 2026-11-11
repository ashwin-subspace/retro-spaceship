package com.ashwin.retrospaceship;

/**
 * Parameter sweep for {@link Flight}.
 *
 * <p>Run by hand while tuning the feel. Prints, for each candidate pair of
 * stiffness and damping, the four numbers that decide whether the ship reads as
 * having mass without becoming imprecise: worst tracking error against a moving
 * finger, how far it carries past a hard stop, how long it takes to settle, and
 * how far it drifts past a reversal.
 *
 * <p>Usage:  java com.ashwin.retrospaceship.FlightSweep
 */
public final class FlightSweep {

    static final float DT = 1f / 60f;
    static final float MIN_X = 17f, MAX_X = 463f, MIN_Y = 255f, MAX_Y = 937f;
    static final float LANE = 700f;

    static Flight make(float k, float c) {
        Flight f = new Flight();
        f.stiffness = k;
        f.damping = c;
        f.reset(40f, LANE);
        f.begin();
        return f;
    }

    static float trackingError(float k, float c, float speed) {
        Flight f = make(k, c);
        float tx = 40f;
        float worst = 0f;
        int frame = 0;
        while (tx + speed * DT < MAX_X - 30f) {
            tx += speed * DT;
            f.setTarget(tx, LANE);
            f.step(DT, MIN_X, MAX_X, MIN_Y, MAX_Y);
            if (++frame > 12) {
                worst = Math.max(worst, Math.abs(tx - f.x));
            }
        }
        return worst;
    }

    static float[] stopBehaviour(float k, float c) {
        Flight f = make(k, c);
        float tx = 40f;
        for (int i = 0; i < 28; i++) {
            tx += 700f * DT;
            f.setTarget(tx, LANE);
            f.step(DT, MIN_X, MAX_X, MIN_Y, MAX_Y);
        }
        float overshoot = 0f;
        int settle = -1;
        for (int i = 0; i < 120; i++) {
            f.setTarget(tx, LANE);
            f.step(DT, MIN_X, MAX_X, MIN_Y, MAX_Y);
            overshoot = Math.max(overshoot, f.x - tx);
            if (settle < 0 && Math.abs(f.x - tx) < 1.5f && f.speed() < 25f) {
                settle = i + 1;
            }
        }
        return new float[]{overshoot, settle};
    }

    static float reversalDrift(float k, float c) {
        Flight f = make(k, c);
        float tx = 40f;
        for (int i = 0; i < 24; i++) {
            tx += 700f * DT;
            f.setTarget(tx, LANE);
            f.step(DT, MIN_X, MAX_X, MIN_Y, MAX_Y);
        }
        float turn = f.x;
        float furthest = f.x;
        for (int i = 0; i < 12; i++) {
            tx -= 700f * DT;
            f.setTarget(tx, LANE);
            f.step(DT, MIN_X, MAX_X, MIN_Y, MAX_Y);
            furthest = Math.max(furthest, f.x);
        }
        return furthest - turn;
    }

    public static void main(String[] args) {
        System.out.printf("%6s %5s | %7s %7s | %8s %7s | %7s%n",
                "stiff", "damp", "lag600", "lag1000", "overshoot", "settle", "reversal");
        for (float k : new float[]{600f, 700f, 800f, 900f}) {
            for (float c : new float[]{26f, 30f, 34f, 38f}) {
                float[] stop = stopBehaviour(k, c);
                System.out.printf("%6.0f %5.0f | %7.1f %7.1f | %8.1f %7.0f | %7.1f%n",
                        k, c, trackingError(k, c, 600f), trackingError(k, c, 1000f),
                        stop[0], stop[1], reversalDrift(k, c));
            }
        }
    }
}
