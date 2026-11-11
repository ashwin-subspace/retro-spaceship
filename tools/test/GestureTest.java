package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop harness for {@link Gestures}.
 *
 * <p>Gestures is the entire control scheme now, and it is the one piece of the
 * game that cannot be exercised through adb, which has no multitouch. It also
 * happens to have no Android imports, so it can be driven directly here with
 * synthetic pointer events.
 */
public final class GestureTest {

    static final List<String> log = new ArrayList<>();
    static int failures = 0;

    static final Gestures.Listener SINK = new Gestures.Listener() {
        public void onDragStart(float x, float y) { log.add("dragStart"); }
        public void onDrag(float x, float y) { log.add("drag"); }
        public void onDragEnd() { log.add("dragEnd"); }
        public void onBomb() { log.add("BOMB"); }
        public void onOverdrive() { log.add("OVERDRIVE"); }
        public void onPrecision(boolean p) { log.add("precise=" + p); }
    };

    static void check(String name, boolean ok, String detail) {
        System.out.printf("  %-46s %s%n", name, ok ? "pass" : "FAIL  " + detail);
        if (!ok) {
            failures++;
        }
    }

    static boolean has(String event) {
        return log.contains(event);
    }

    public static void main(String[] args) {
        System.out.println("Gestures");

        // 1. The steering finger alone never fires an ability, however it moves.
        Gestures g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 100, 0);
        g.move(1, 140, 160, 50);
        g.move(1, 200, 240, 100);
        g.up(1, 200, 240, 150);
        check("drag only: no ability fires", !has("BOMB") && !has("OVERDRIVE"), log.toString());
        check("drag only: steering starts and ends",
                has("dragStart") && has("drag") && has("dragEnd"), log.toString());

        // 2. A short still tap by the STEERING finger must not spend a bomb --
        //    this is exactly what a small ship correction looks like.
        g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 100, 0);
        g.up(1, 104, 103, 90);
        check("lone quick tap: no accidental bomb", !has("BOMB"), log.toString());

        // 3. Second finger taps while the first steers -> bomb.
        g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 400, 0);
        g.move(1, 180, 380, 40);
        g.move(1, 260, 360, 80);
        g.down(2, 60, 700, 100);
        g.up(2, 62, 702, 190);
        check("second-finger tap while steering: BOMB", has("BOMB"), log.toString());
        check("bomb tap does not disturb steering", !has("dragEnd"), log.toString());

        // 4. Second finger held -> overdrive, and releasing it must not also bomb.
        g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 400, 0);
        g.move(1, 180, 380, 40);
        g.down(2, 60, 700, 100);
        g.tick(200);
        check("held second finger, too early: no overdrive", !has("OVERDRIVE"), log.toString());
        g.tick(460);
        check("held second finger past 320ms: OVERDRIVE", has("OVERDRIVE"), log.toString());
        log.clear();
        g.tick(900);
        check("overdrive fires once per hold", !has("OVERDRIVE"), log.toString());
        g.up(2, 61, 701, 950);
        check("releasing a spent hold does not bomb", !has("BOMB"), log.toString());

        // 5. A second finger that drags is neither a tap nor a hold.
        g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 400, 0);
        g.down(2, 60, 700, 50);
        g.move(2, 200, 700, 100);
        g.tick(500);
        g.up(2, 200, 700, 520);
        check("second finger that travels: no ability",
                !has("BOMB") && !has("OVERDRIVE"), log.toString());

        // 6. Precision follows how fast the steering finger is moving.
        g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 400, 0);
        for (int i = 1; i <= 12; i++) {
            g.move(1, 100 + i, 400, i * 16);   // ~62 units/sec: slow
        }
        check("slow steering raises precision", has("precise=true"), log.toString());
        log.clear();
        for (int i = 1; i <= 12; i++) {
            g.move(1, 112 + i * 12, 400, 200 + i * 16);   // ~750 units/sec: fast
        }
        check("fast steering drops precision", has("precise=false"), log.toString());

        // 7. Lifting the steering finger hands control to one still down, so the
        //    ship is never abandoned mid-wave.
        g = new Gestures(SINK);
        log.clear();
        g.down(1, 100, 400, 0);
        g.move(1, 200, 400, 40);
        g.down(2, 300, 500, 60);
        g.move(2, 340, 520, 100);
        log.clear();
        g.up(1, 200, 400, 140);
        check("steering transfers to remaining finger",
                has("dragEnd") && has("dragStart"), log.toString());
        log.clear();
        g.move(2, 380, 540, 180);
        check("transferred finger now steers", has("drag"), log.toString());

        // 8. reset() must leave no finger believed to be down.
        g = new Gestures(SINK);
        g.down(1, 100, 400, 0);
        g.down(2, 200, 500, 20);
        g.reset();
        log.clear();
        g.tick(5000);
        check("reset clears held fingers", !has("OVERDRIVE"), log.toString());
        g.down(3, 10, 10, 5100);
        check("reset lets a new finger steer", has("dragStart"), log.toString());

        System.out.println(failures == 0
                ? "\nall gesture checks passed"
                : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
