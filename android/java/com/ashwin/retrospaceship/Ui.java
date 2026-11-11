package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.List;

/**
 * Menus and full-screen states.
 *
 * <p>Every menu is a single vertical list of touch rows. One list widget instead
 * of seven bespoke screens means every screen automatically has the same row
 * height, the same hit area and the same feedback -- and every row is comfortably
 * larger than a fingertip, which a hand-placed layout tends to lose track of.
 */
public final class Ui {

    /** Row height in world units. Generous on purpose: this is a touch target. */
    private static final float ROW = 52f;

    public static final class Item {
        public final String label;
        public final int action;
        public String value;
        public boolean enabled = true;

        public Item(String label, int action) {
            this(label, action, null);
        }

        public Item(String label, int action, String value) {
            this.label = label;
            this.action = action;
            this.value = value;
        }
    }

    public static final class Menu {
        public final List<Item> items = new ArrayList<>();
        public float top;
        public int highlight = -1;

        public Menu(float top) {
            this.top = top;
        }

        public Menu add(Item item) {
            items.add(item);
            return this;
        }

        public Menu add(String label, int action) {
            return add(new Item(label, action));
        }

        public Menu add(String label, int action, String value) {
            return add(new Item(label, action, value));
        }

        /** Which row a tap landed on, or -1. */
        public int hit(float x, float y) {
            if (x < 30f || x > Cfg.W - 30f) {
                return -1;
            }
            int index = (int) Math.floor((y - top) / ROW);
            if (index < 0 || index >= items.size()) {
                return -1;
            }
            return index;
        }

        public void draw(Draw d, double wallMs) {
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                float y = top + i * ROW;
                boolean active = i == highlight;
                int color = item.enabled
                        ? Theme.color(active ? Theme.CYAN : Theme.WHITE)
                        : Theme.shade(Theme.color(Theme.WHITE), 0.35f);

                if (active) {
                    float pulse = 0.35f + 0.25f * (float) Math.sin(wallMs / 220.0);
                    d.rect(30f, y + 4f, Cfg.W - 60f, ROW - 8f,
                            Theme.withAlpha(Theme.color(Theme.CYAN), (int) (52 * pulse * 2f)));
                    d.rectOutline(30f, y + 4f, Cfg.W - 60f, ROW - 8f,
                            Theme.color(Theme.CYAN), 1.5f);
                }
                if (item.value == null) {
                    d.text(item.label, 20f, Cfg.W * 0.5f, y + ROW * 0.5f, color, 255,
                            d.assets.hud);
                } else {
                    d.textLeft(item.label, 18f, 48f, y + ROW * 0.5f, color, 255);
                    d.textRight(item.value, 18f, Cfg.W - 48f, y + ROW * 0.5f,
                            Theme.color(Theme.YELLOW), 255);
                }
            }
        }
    }

    // ---------- shared chrome ----------

    /** A dimming sheet, so a menu over live gameplay stays readable. */
    public static void scrim(Draw d, float worldH, int alpha) {
        d.fullRect(0, 0, Cfg.W, worldH, Theme.color(Theme.BLACK), alpha);
    }

    public static void title(Draw d, double wallMs) {
        float y = Cfg.H * 0.15f;
        int accent = Theme.color(Theme.CYAN);
        // The drop shadow is offset rather than blurred: it costs one extra draw
        // and gives the title the chunky arcade weight the game is dressed in.
        d.text("RETRO", 54f, Cfg.W * 0.5f + 3f, y + 3f,
                Theme.shade(Theme.color(Theme.BLACK), 2f), 255, d.assets.title);
        d.text("RETRO", 54f, Cfg.W * 0.5f, y, Theme.color(Theme.GREEN), 255, d.assets.title);
        d.text("SPACESHIP", 34f, Cfg.W * 0.5f + 2f, y + 44f,
                Theme.shade(Theme.color(Theme.BLACK), 2f), 255, d.assets.title);
        d.text("SPACESHIP", 34f, Cfg.W * 0.5f, y + 42f, accent, 255, d.assets.title);

        float pulse = 0.55f + 0.45f * (float) Math.sin(wallMs / 400.0);
        d.text("DIFFICULTY RISES AS YOU SURVIVE", 12f, Cfg.W * 0.5f, y + 88f,
                Theme.shade(Theme.color(Theme.WHITE), pulse), 210, d.assets.hud);
    }

    public static void heading(Draw d, String text) {
        d.text(text, 30f, Cfg.W * 0.5f, Cfg.H * 0.12f, Theme.color(Theme.YELLOW), 255,
                d.assets.title);
    }

    // ---------- screens ----------

    public static void scores(Draw d, Save save) {
        heading(d, "HIGH SCORES");
        float y = Cfg.H * 0.22f;
        if (save.scores.isEmpty()) {
            d.text("NO RUNS YET", 18f, Cfg.W * 0.5f, y + 40f,
                    Theme.shade(Theme.color(Theme.WHITE), 0.6f), 255, d.assets.hud);
        }
        for (int i = 0; i < save.scores.size(); i++) {
            Save.Entry entry = save.scores.get(i);
            float row = y + i * 40f;
            int color = i == 0 ? Theme.color(Theme.YELLOW) : Theme.color(Theme.WHITE);
            d.textLeft((i + 1) + ".", 18f, 40f, row, color, 255);
            d.textLeft(Integer.toString(entry.score), 20f, 70f, row, color, 255);
            d.textRight("ST " + entry.stage + "  " + entry.weapon, 13f, Cfg.W - 40f, row - 6f,
                    Theme.color(Theme.WHITE), 150);
            d.textRight(entry.date + "   " + time(entry.timeMs), 12f, Cfg.W - 40f, row + 10f,
                    Theme.color(Theme.WHITE), 110);
        }

        float totalsY = y + 5 * 40f + 24f;
        d.line(40f, totalsY - 14f, Cfg.W - 40f, totalsY - 14f,
                Theme.shade(Theme.color(Theme.WHITE), 0.25f), 1f);
        d.textLeft("RUNS " + save.totalRuns, 13f, 40f, totalsY, Theme.color(Theme.WHITE), 170);
        d.textRight("KILLS " + save.totalKills, 13f, Cfg.W - 40f, totalsY,
                Theme.color(Theme.WHITE), 170);
        d.textLeft("BOSSES " + save.totalBosses, 13f, 40f, totalsY + 18f,
                Theme.color(Theme.WHITE), 170);
        d.textRight("PLAYED " + time(save.totalPlaytimeMs), 13f, Cfg.W - 40f, totalsY + 18f,
                Theme.color(Theme.WHITE), 170);
    }

    public static void help(Draw d) {
        heading(d, "HOW TO PLAY");
        String[] lines = {
                "DRAG anywhere to fly. The ship fires",
                "by itself -- aiming is moving.",
                "",
                "Your hitbox is the tiny white dot, not",
                "the whole ship. Threading a wall of",
                "bullets is the point.",
                "",
                "Keep steering with one finger and use",
                "a second finger for both abilities:",
                "",
                "TAP  =  BOMB",
                "Clears the screen and turns every",
                "bullet into score.",
                "",
                "HOLD  =  OVERDRIVE",
                "Once the meter fills: faster, piercing",
                "shots and +50% score.",
                "",
                "Move your finger slowly and the GRAZE",
                "ring appears. Passing that close to a",
                "bullet scores and charges overdrive.",
                "",
                "Clear a wave untouched for a PERFECT",
                "bonus and a free shield.",
                "",
                "There is no difficulty setting. The game",
                "reads how you play and answers.",
        };
        float y = Cfg.H * 0.20f;
        // The back row sits at worldH - 108. A fixed 19px step runs through it
        // on the shortest playfield the game allows (H = 640).
        float room = (Cfg.H - 120f) - y;
        float gap = 19f;
        if (lines.length > 0 && room > 0 && lines.length * gap > room) {
            gap = room / lines.length;
        }
        for (int i = 0; i < lines.length; i++) {
            d.text(lines[i], 14f, Cfg.W * 0.5f, y + i * gap,
                    Theme.color(Theme.WHITE), 210, d.assets.hud);
        }
    }

    public static void upgrade(Draw d, List<Upgrades.Card> cards, int highlight, double wallMs) {
        heading(d, "BOSS DOWN");
        d.text("CHOOSE AN UPGRADE", 15f, Cfg.W * 0.5f, Cfg.H * 0.19f,
                Theme.color(Theme.WHITE), 210, d.assets.hud);
        for (int i = 0; i < cards.size(); i++) {
            Upgrades.Card card = cards.get(i);
            float y = cardTop(i);
            boolean active = i == highlight;
            int accent = Theme.color(active ? Theme.CYAN : Theme.WHITE);
            if (active) {
                float pulse = 0.4f + 0.3f * (float) Math.sin(wallMs / 200.0);
                d.rect(40f, y, Cfg.W - 80f, CARD_H,
                        Theme.withAlpha(Theme.color(Theme.CYAN), (int) (60 * pulse)));
            }
            d.rectOutline(40f, y, Cfg.W - 80f, CARD_H, accent, 2f);
            d.text(card.title, 22f, Cfg.W * 0.5f, y + 26f, Theme.color(Theme.YELLOW), 255,
                    d.assets.hud);
            d.text(card.description, 13f, Cfg.W * 0.5f, y + 52f,
                    Theme.color(Theme.WHITE), 200, d.assets.hud);
        }
    }

    private static final float CARD_H = 74f;

    public static float cardTop(int index) {
        return Cfg.H * 0.26f + index * (CARD_H + 14f);
    }

    public static int cardHit(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            float top = cardTop(i);
            if (x >= 40f && x <= Cfg.W - 40f && y >= top && y <= top + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    public static void results(Draw d, World world, int rank, double wallMs) {
        World.Stats stats = world.stats;
        heading(d, "RUN OVER");

        String grade = stats.rank(world.score);
        float pulse = 0.7f + 0.3f * (float) Math.sin(wallMs / 300.0);
        d.text(grade, 76f, Cfg.W * 0.5f, Cfg.H * 0.26f,
                Theme.shade(Theme.color(Theme.YELLOW), pulse), 255, d.assets.title);
        d.text("RANK", 12f, Cfg.W * 0.5f, Cfg.H * 0.26f + 46f,
                Theme.color(Theme.WHITE), 170, d.assets.hud);

        d.text(Integer.toString(world.score), 34f, Cfg.W * 0.5f, Cfg.H * 0.38f,
                Theme.color(Theme.CYAN), 255, d.assets.hud);
        if (rank > 0) {
            d.text("NEW HIGH SCORE  #" + rank, 14f, Cfg.W * 0.5f, Cfg.H * 0.38f + 28f,
                    Theme.color(Theme.YELLOW), 255, d.assets.hud);
        }

        String[][] rows = {
                {"STAGE", stats.stage + " - " + stats.wave},
                {"KILLS", Integer.toString(stats.kills)},
                {"ACCURACY", Math.round(stats.accuracy() * 100) + "%"},
                {"BEST CHAIN", Integer.toString(stats.bestCombo)},
                {"GRAZES", Integer.toString(stats.grazeTotal)},
                {"BOSSES", Integer.toString(stats.bossesKilled)},
                {"PERFECT WAVES", Integer.toString(stats.perfectWaves)},
                {"HITS TAKEN", Integer.toString(stats.hitsTaken)},
                {"TIME", time((long) stats.timeMs)},
        };
        float y = Cfg.H * 0.47f;
        for (int i = 0; i < rows.length; i++) {
            d.textLeft(rows[i][0], 14f, 56f, y + i * 20f, Theme.color(Theme.WHITE), 170);
            d.textRight(rows[i][1], 14f, Cfg.W - 56f, y + i * 20f,
                    Theme.color(Theme.WHITE), 235);
        }
    }

    public static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000L;
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
