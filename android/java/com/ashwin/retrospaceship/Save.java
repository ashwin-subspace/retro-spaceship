package com.ashwin.retrospaceship;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings, the high-score table and lifetime totals.
 *
 * <p>Loading is corruption-proof by construction: any parse failure falls back
 * to defaults rather than taking the app down, because a save file is the one
 * piece of state a player cannot re-create.
 */
public final class Save {

    private static final String PREFS = "retro_spaceship";
    private static final String KEY = "save";
    private static final int MAX_SCORES = 5;

    public static final class Entry {
        public final int score;
        public final int stage;
        public final int wave;
        public final long timeMs;
        public final String weapon;
        public final String date;

        Entry(int score, int stage, int wave, long timeMs, String weapon, String date) {
            this.score = score;
            this.stage = stage;
            this.wave = wave;
            this.timeMs = timeMs;
            this.weapon = weapon;
            this.date = date;
        }
    }

    private final SharedPreferences prefs;

    public int theme = 0;
    public boolean muted = false;
    public int sfxVolume = 80;
    public int musicVolume = 55;
    public boolean crt = true;
    public boolean lowQuality = false;
    public int highScore = 0;
    public final List<Entry> scores = new ArrayList<>();

    public int totalRuns = 0;
    public int totalKills = 0;
    public int totalBosses = 0;
    public long totalPlaytimeMs = 0;
    public int bestCombo = 0;
    public int bestGraze = 0;

    public Save(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        String raw = prefs.getString(KEY, null);
        if (raw == null) {
            return;
        }
        try {
            JSONObject data = new JSONObject(raw);
            theme = clamp(data.optInt("theme", 0), 0, Theme.NAMES.length - 1);
            muted = data.optBoolean("muted", false);
            sfxVolume = clamp(data.optInt("sfxVolume", 80), 0, 100);
            musicVolume = clamp(data.optInt("musicVolume", 55), 0, 100);
            crt = data.optBoolean("crt", true);
            lowQuality = data.optBoolean("lowQuality", false);

            JSONArray table = data.optJSONArray("scores");
            if (table != null) {
                for (int i = 0; i < table.length(); i++) {
                    JSONObject row = table.optJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    scores.add(new Entry(
                            row.optInt("score", 0),
                            row.optInt("stage", 1),
                            row.optInt("wave", 1),
                            row.optLong("timeMs", 0L),
                            row.optString("weapon", "SPREAD"),
                            row.optString("date", "")));
                }
            }
            sortScores();

            JSONObject totals = data.optJSONObject("totals");
            if (totals != null) {
                totalRuns = totals.optInt("runs", 0);
                totalKills = totals.optInt("kills", 0);
                totalBosses = totals.optInt("bosses", 0);
                totalPlaytimeMs = totals.optLong("playtimeMs", 0L);
                bestCombo = totals.optInt("bestCombo", 0);
                bestGraze = totals.optInt("bestGraze", 0);
            }
        } catch (JSONException e) {
            // A corrupt save is replaced by defaults rather than blocking play.
            scores.clear();
        }
    }

    public void save() {
        try {
            JSONObject data = new JSONObject();
            data.put("theme", theme);
            data.put("muted", muted);
            data.put("sfxVolume", sfxVolume);
            data.put("musicVolume", musicVolume);
            data.put("crt", crt);
            data.put("lowQuality", lowQuality);

            JSONArray table = new JSONArray();
            for (Entry entry : scores) {
                JSONObject row = new JSONObject();
                row.put("score", entry.score);
                row.put("stage", entry.stage);
                row.put("wave", entry.wave);
                row.put("timeMs", entry.timeMs);
                row.put("weapon", entry.weapon);
                row.put("date", entry.date);
                table.put(row);
            }
            data.put("scores", table);

            JSONObject totals = new JSONObject();
            totals.put("runs", totalRuns);
            totals.put("kills", totalKills);
            totals.put("bosses", totalBosses);
            totals.put("playtimeMs", totalPlaytimeMs);
            totals.put("bestCombo", bestCombo);
            totals.put("bestGraze", bestGraze);
            data.put("totals", totals);

            prefs.edit().putString(KEY, data.toString()).apply();
        } catch (JSONException e) {
            // Nothing recoverable; the in-memory state stays correct for this session.
        }
    }

    private void sortScores() {
        Collections.sort(scores, (a, b) -> Integer.compare(b.score, a.score));
        while (scores.size() > MAX_SCORES) {
            scores.remove(scores.size() - 1);
        }
        highScore = scores.isEmpty() ? 0 : scores.get(0).score;
    }

    /** Insert a run into the table. Returns its 1-based rank, or 0 if it missed. */
    public int addScore(int score, int stage, int wave, long timeMs, String weapon) {
        if (score <= 0) {
            return 0;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Entry entry = new Entry(score, stage, wave, timeMs, weapon, date);
        scores.add(entry);
        sortScores();
        save();
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i) == entry) {
                return i + 1;
            }
        }
        return 0;
    }

    public void recordRun(World.Stats stats) {
        totalRuns++;
        totalKills += stats.kills;
        totalBosses += stats.bossesKilled;
        totalPlaytimeMs += (long) stats.timeMs;
        bestCombo = Math.max(bestCombo, stats.bestCombo);
        bestGraze = Math.max(bestGraze, stats.maxGrazeStreak);
        save();
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
