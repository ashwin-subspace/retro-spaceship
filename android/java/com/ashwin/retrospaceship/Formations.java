package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spawn formations: where a wave enters from, and in what order.
 *
 * <p>Spawning every enemy at a random x makes a wave read as noise. A formation
 * makes it read as a deliberate attack, and gives the player a shape to learn
 * and beat. Each builder returns spawn points; the director pairs them with
 * archetypes bought from its threat budget.
 */
public final class Formations {
    private Formations() {}

    private static final float MARGIN = 40f;

    /** One spawn slot: where an enemy appears and how long after the wave starts. */
    public static final class Point {
        public final float x;
        public final float y;
        public final float delayMs;

        Point(float x, float y, float delayMs) {
            this.x = x;
            this.y = y;
            this.delayMs = delayMs;
        }
    }

    private static final Map<String, String[]> FLAVOR_FORMATIONS = new HashMap<>();

    static {
        FLAVOR_FORMATIONS.put("STANDARD", new String[]{"LINE", "WEDGE", "ARC", "TRICKLE"});
        FLAVOR_FORMATIONS.put("SWARM", new String[]{"LINE", "ARC", "WEDGE"});
        FLAVOR_FORMATIONS.put("ARMORED", new String[]{"ESCORT", "LINE"});
        FLAVOR_FORMATIONS.put("BLITZ", new String[]{"COLUMN", "WEDGE", "TRICKLE"});
        FLAVOR_FORMATIONS.put("BARRAGE", new String[]{"LINE", "ARC"});
        FLAVOR_FORMATIONS.put("AMBUSH", new String[]{"PINCER", "COLUMN"});
        FLAVOR_FORMATIONS.put("ELITE", new String[]{"WEDGE", "ESCORT", "ARC"});
    }

    public static String pickFor(String flavor) {
        String[] options = FLAVOR_FORMATIONS.get(flavor);
        if (options == null) {
            options = FLAVOR_FORMATIONS.get("STANDARD");
        }
        return Rng.choice(options);
    }

    /** Evenly spaced x positions across the playfield. */
    private static float[] span(int count) {
        if (count <= 1) {
            return new float[]{Cfg.W * 0.5f};
        }
        float usable = Cfg.W - MARGIN * 2f;
        float step = usable / (count - 1);
        float[] xs = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = MARGIN + step * i;
        }
        return xs;
    }

    public static List<Point> build(String name, int count) {
        List<Point> points;
        switch (name) {
            case "WEDGE": points = wedge(count); break;
            case "COLUMN": points = column(count); break;
            case "ARC": points = arc(count); break;
            case "PINCER": points = pincer(count); break;
            case "TRICKLE": points = trickle(count); break;
            case "ESCORT": points = escort(count); break;
            default: points = line(count); break;
        }
        // Never let a formation strand a spawn far off the field horizontally.
        // Pincer starts off-edge deliberately, so a margin of overshoot is allowed.
        List<Point> cleaned = new ArrayList<>(points.size());
        for (Point p : points) {
            cleaned.add(new Point(
                    Math.max(-40f, Math.min(Cfg.W + 40f, p.x)), p.y, Math.max(0f, p.delayMs)));
        }
        return cleaned;
    }

    /** A horizontal rank entering together. */
    private static List<Point> line(int count) {
        float[] xs = span(count);
        List<Point> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new Point(xs[i], -30f, i * 90f));
        }
        return out;
    }

    /** A V pointing down -- the classic arcade approach. */
    private static List<Point> wedge(int count) {
        List<Point> out = new ArrayList<>(count);
        float center = Cfg.W * 0.5f;
        float spacing = Math.min(56f, (Cfg.W - MARGIN * 2f) / Math.max(2, count));
        for (int i = 0; i < count; i++) {
            float side = (i % 2 == 0) ? -1f : 1f;
            int rank = (i + 1) / 2;
            float x = center + side * rank * spacing;
            out.add(new Point(Math.max(MARGIN, Math.min(Cfg.W - MARGIN, x)),
                    -30f - rank * 26f, i * 70f));
        }
        return out;
    }

    /** Single file down one lane -- reads clearly, great for chargers. */
    private static List<Point> column(int count) {
        float lane = Rng.uniform(MARGIN, Cfg.W - MARGIN);
        List<Point> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new Point(lane, -30f, i * 260f));
        }
        return out;
    }

    /** A shallow bowl shape. */
    private static List<Point> arc(int count) {
        float[] xs = span(count);
        List<Point> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float t = (i / (float) Math.max(1, count - 1)) * 2f - 1f;
            out.add(new Point(xs[i], -30f - (1f - t * t) * 46f, i * 60f));
        }
        return out;
    }

    /** Two groups entering from the left and right edges. */
    private static List<Point> pincer(int count) {
        List<Point> out = new ArrayList<>(count);
        int half = Math.max(1, count / 2);
        for (int i = 0; i < count; i++) {
            boolean left = i % 2 == 0;
            int rank = i / 2;
            float x = left ? -24f : Cfg.W + 24f;
            float y = Cfg.H * 0.14f + rank * Cfg.H * 0.085f;
            out.add(new Point(x, Math.min(y, Cfg.H * 0.45f), i * 120f));
            if (rank >= half) {
                break;
            }
        }
        return out;
    }

    /** Random lanes, rate limited so two never overlap awkwardly. */
    private static List<Point> trickle(int count) {
        List<Point> out = new ArrayList<>(count);
        Float lastX = null;
        for (int i = 0; i < count; i++) {
            float x = Rng.uniform(MARGIN, Cfg.W - MARGIN);
            for (int attempt = 0; attempt < 6; attempt++) {
                if (lastX == null || Math.abs(x - lastX) >= 40f) {
                    break;
                }
                x = Rng.uniform(MARGIN, Cfg.W - MARGIN);
            }
            lastX = x;
            out.add(new Point(x, -30f, i * 340f));
        }
        return out;
    }

    /** A leader with flankers -- pairs a tank with fodder. */
    private static List<Point> escort(int count) {
        List<Point> out = new ArrayList<>(Math.max(1, count));
        out.add(new Point(Cfg.W * 0.5f, -34f, 0f));
        for (int i = 0; i < Math.max(0, count - 1); i++) {
            float side = (i % 2 == 0) ? -1f : 1f;
            int rank = (i / 2) + 1;
            float x = Cfg.W * 0.5f + side * (46f * rank);
            out.add(new Point(Math.max(MARGIN, Math.min(Cfg.W - MARGIN, x)),
                    -30f - rank * 18f, (i + 1) * 80f));
        }
        return out;
    }

    /** Utility for boss adds and ring spawns. */
    public static List<Point> circle(int count, float cx, float cy, float radius) {
        List<Point> out = new ArrayList<>(Math.max(0, count));
        if (count <= 0) {
            return out;
        }
        float step = Patterns.TAU / count;
        for (int i = 0; i < count; i++) {
            out.add(new Point(cx + (float) Math.cos(step * i) * radius,
                    cy + (float) Math.sin(step * i) * radius, 0f));
        }
        return out;
    }

    /** Names of every builder, for anything that wants to enumerate them. */
    public static List<String> names() {
        return Arrays.asList("LINE", "WEDGE", "COLUMN", "ARC", "PINCER", "TRICKLE", "ESCORT");
    }
}
