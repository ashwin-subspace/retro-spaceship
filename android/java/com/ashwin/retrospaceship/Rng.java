package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** One shared random source, so a whole run can be reasoned about together. */
public final class Rng {
    private Rng() {}

    private static final Random R = new Random();

    public static float uniform(float lo, float hi) {
        return lo + R.nextFloat() * (hi - lo);
    }

    public static int randint(int lo, int hi) {
        return lo + R.nextInt(Math.max(1, hi - lo + 1));
    }

    public static boolean chance(float probability) {
        return R.nextFloat() < probability;
    }

    public static float next() {
        return R.nextFloat();
    }

    public static <T> T choice(List<T> items) {
        return items.get(R.nextInt(items.size()));
    }

    public static String choice(String[] items) {
        return items[R.nextInt(items.length)];
    }

    /** Weighted pick; weights run parallel to items and need not sum to one. */
    public static <T> T weighted(List<T> items, List<Float> weights) {
        float total = 0f;
        for (float w : weights) {
            total += Math.max(0f, w);
        }
        if (total <= 0f) {
            return choice(items);
        }
        float roll = R.nextFloat() * total;
        for (int i = 0; i < items.size(); i++) {
            roll -= Math.max(0f, weights.get(i));
            if (roll <= 0f) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }

    public static <T> List<T> shuffled(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, R);
        return copy;
    }
}
