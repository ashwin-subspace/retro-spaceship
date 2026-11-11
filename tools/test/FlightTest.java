package com.ashwin.retrospaceship;

/**
 * Desktop harness for {@link Flight}.
 *
 * <p>"Feels like a ship rather than a cursor" is a subjective goal with a very
 * objective failure mode: if the hull lags behind the finger, the player dies in
 * gaps they thought they had cleared. This measures the numbers that decide that
 * -- tracking error while dragging, overshoot and settle time on a hard stop,
 * behaviour on a reversal -- so the feel can be pushed as far as it will go
 * without quietly making the game unfair.
 *
 * <p>Every run keeps the simulated finger inside the playfield. A target dragged
 * through a wall pins the ship against it, and then every measurement afterwards
 * is really measuring the wall.
 */
public final class FlightTest {

    static final float DT = 1f / 60f;
    // The real playfield bounds for a 480x960 world.
    static final float MIN_X = 17f, MAX_X = 463f, MIN_Y = 255f, MAX_Y = 937f;
    static final float LANE_Y = 700f;
    /** Frames of launch transient to ignore before judging tracking. */
    static final int SETTLE_IN = 12;

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.printf("  %-42s %-4s %s%n", name, ok ? "pass" : "FAIL", detail);
        if (!ok) {
            failures++;
        }
    }

    static Flight fresh(float startX) {
        Flight f = new Flight();
        f.reset(startX, LANE_Y);
        f.begin();
        return f;
    }

    static void step(Flight f, float dt) {
        f.step(dt, MIN_X, MAX_X, MIN_Y, MAX_Y);
    }

    /** Drag the target right at a fixed speed for a number of frames. */
    static float drag(Flight f, float fromX, float speed, int frames) {
        float tx = fromX;
        for (int i = 0; i < frames; i++) {
            tx += speed * DT;
            f.setTarget(tx, LANE_Y);
            step(f, DT);
        }
        return tx;
    }

    public static void main(String[] args) {
        System.out.println("Flight");

        // 1. Tracking a finger moving at a realistic drag speed. This is the check
        //    that decides whether the ship still goes where you put it.
        for (float speed : new float[]{300f, 600f, 1000f}) {
            float startX = 30f;
            Flight f = fresh(startX);
            float tx = startX;
            float worst = 0f;
            int frame = 0;
            // Stop before the target reaches the wall: past that point the error
            // being measured is the clamp, not the flight model.
            while (tx + speed * DT < MAX_X - 30f) {
                tx += speed * DT;
                f.setTarget(tx, LANE_Y);
                step(f, DT);
                frame++;
                if (frame > SETTLE_IN) {
                    worst = Math.max(worst, Math.abs(tx - f.x));
                }
            }
            // The floor here is a half-frame of input: the finger's velocity can only
            // be measured as a backward difference, so it is always one sample old.
            // The error scaling with drag speed is the right behaviour anyway --
            // a fast flick is loose, and slow threading stays exact.
            float allowed = 4f + speed / 95f;
            check("tracking lag @" + (int) speed + " u/s", worst < allowed,
                    String.format("worst %.1f units over %d frames", worst, frame - SETTLE_IN));
        }

        // 2. A hard stop: the ship should carry through a little, then settle
        //    quickly. Too little and it is a cursor again; too much and you
        //    cannot park in a gap.
        Flight f = fresh(60f);
        float tx = drag(f, 60f, 700f, 28);
        float overshoot = 0f;
        int settleFrames = -1;
        for (int i = 0; i < 90; i++) {
            f.setTarget(tx, LANE_Y);                 // finger stops dead
            step(f, DT);
            overshoot = Math.max(overshoot, f.x - tx);
            if (settleFrames < 0 && Math.abs(f.x - tx) < 1.5f && f.speed() < 25f) {
                settleFrames = i + 1;
            }
        }
        check("stop overshoot", overshoot > 8f && overshoot < 26f,
                String.format("%.1f units", overshoot));
        check("stop settle time", settleFrames > 0 && settleFrames < 16,
                settleFrames + " frames (" + Math.round(settleFrames * 16.7f) + "ms)");

        // 3. A reversal has to cost something, or there is no weight to the ship.
        f = fresh(60f);
        tx = drag(f, 60f, 700f, 24);
        float turnPoint = f.x;
        float furthest = f.x;
        for (int i = 0; i < 12; i++) {
            tx -= 700f * DT;
            f.setTarget(tx, LANE_Y);
            step(f, DT);
            furthest = Math.max(furthest, f.x);
        }
        check("reversal carries momentum", furthest - turnPoint > 2.5f,
                String.format("drifted %.1f units past the turn", furthest - turnPoint));

        // 4. Releasing should glide, not freeze.
        f = fresh(60f);
        drag(f, 60f, 700f, 28);
        float atRelease = f.x;
        f.end();
        for (int i = 0; i < 6; i++) {
            step(f, DT);
        }
        float glide = f.x - atRelease;
        for (int i = 0; i < 60; i++) {
            step(f, DT);
        }
        check("release glides", glide > 3f && glide < 35f,
                String.format("%.1f units in 100ms", glide));
        check("release comes to rest", f.speed() < 5f,
                String.format("%.1f u/s after 1.1s", f.speed()));

        // 5. Walls absorb the ship instead of storing energy and flinging it back.
        f = fresh(240f);
        for (int i = 0; i < 60; i++) {
            f.setTarget(MAX_X + 300f, LANE_Y);
            step(f, DT);
        }
        check("clamps to the wall", Math.abs(f.x - MAX_X) < 0.01f,
                String.format("x=%.2f", f.x));
        check("no stored energy at the wall", Math.abs(f.vx) < 1f,
                String.format("vx=%.2f", f.vx));

        // 6. A long frame must not detonate a stiff spring.
        f = fresh(240f);
        for (int i = 0; i < 20; i++) {
            f.setTarget(400f, 400f);
            step(f, 0.05f);                          // the Clock's worst-case frame
        }
        boolean sane = !Float.isNaN(f.x) && f.x >= MIN_X && f.x <= MAX_X
                && f.y >= MIN_Y && f.y <= MAX_Y;
        check("stable at a 50ms frame", sane, String.format("x=%.1f y=%.1f", f.x, f.y));

        // 7. The roll should follow real sideways speed, and lag behind it.
        f = fresh(60f);
        tx = drag(f, 60f, 700f, 20);
        float rightBank = f.bank;
        for (int i = 0; i < 20; i++) {
            tx -= 700f * DT;
            f.setTarget(tx, LANE_Y);
            step(f, DT);
        }
        check("banks into a right turn", rightBank > 8f, String.format("%.1f deg", rightBank));
        check("banks the other way on reversal", f.bank < -8f,
                String.format("%.1f deg", f.bank));

        System.out.println(failures == 0
                ? "\nall flight checks passed"
                : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
