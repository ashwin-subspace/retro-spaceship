package com.ashwin.retrospaceship;

/**
 * A tiny chiptune engine that renders each music track to PCM on the device.
 *
 * <p>Four tracks, one per situation: the menu is sparse and slow, the two
 * battle themes escalate, and the boss theme drops to a harmonic-minor
 * progression that sounds wrong on purpose. Each is a whole number of bars, so
 * the loop point lands exactly on the downbeat and no seam is audible.
 *
 * <p>Everything is square and triangle waves with linear envelopes. That is the
 * sound the game is dressed as, and it costs a few milliseconds to generate
 * instead of a megabyte of APK per track.
 */
public final class Music {
    private Music() {}

    /** Semitone offsets of each chord root, relative to A2, per bar. */
    private static final int[][] PROGRESSIONS = {
            {0, -4, 3, -2},        // MENU:     Am  F   C   G
            {0, -4, -2, 7},        // BATTLE_A: Am  F   G   Em
            {3, -1, 1, 8},         // BATTLE_B: Cm  Ab  Bb  Gm
            {5, 1, -4, 0},         // BOSS:     Dm  Bb  F   Am
    };

    /** Chord tones above the root, in semitones -- minor triad plus the octave. */
    private static final int[] MINOR = {0, 3, 7, 12};

    private static final int[] BPM = {84, 140, 152, 160};
    private static final boolean[] HAS_DRUMS = {false, true, true, true};
    private static final float[] LEAD_GAIN = {0.16f, 0.20f, 0.22f, 0.26f};

    private static final int BASE_MIDI = 45;   // A2

    /**
     * Render one track as a seamless mono loop.
     *
     * @param track one of the constants on {@link Sfx}
     * @param rate  sample rate in Hz
     */
    public static short[] render(int track, int rate) {
        int index = Math.max(0, Math.min(PROGRESSIONS.length - 1, track));
        int[] roots = PROGRESSIONS[index];
        float beatSeconds = 60f / BPM[index];
        int beatSamples = Math.round(beatSeconds * rate);
        int barSamples = beatSamples * 4;
        // Two passes of the progression per loop: long enough not to feel like a
        // four-second ringtone, short enough to generate instantly.
        int bars = roots.length * 2;
        int total = barSamples * bars;

        float[] mix = new float[total];

        for (int bar = 0; bar < bars; bar++) {
            int root = roots[bar % roots.length];
            int barStart = bar * barSamples;
            boolean secondPass = bar >= roots.length;

            bass(mix, barStart, beatSamples, rate, BASE_MIDI + root);
            arpeggio(mix, barStart, beatSamples, rate, BASE_MIDI + 24 + root,
                    LEAD_GAIN[index], secondPass);
            pad(mix, barStart, barSamples, rate, BASE_MIDI + 12 + root);
            if (HAS_DRUMS[index]) {
                drums(mix, barStart, beatSamples, rate, index == Sfx.BOSS);
            }
        }

        return normalise(mix);
    }

    /** Root-and-fifth eighth notes: the pulse the rest of the track sits on. */
    private static void bass(float[] mix, int start, int beat, int rate, int midi) {
        int step = beat / 2;
        for (int i = 0; i < 8; i++) {
            int note = midi + ((i % 4 == 3) ? 7 : 0);
            square(mix, start + i * step, step, rate, freq(note), 0.34f, 0.35f);
        }
    }

    /** Sixteenth-note arpeggio through the chord -- the melodic content. */
    private static void arpeggio(float[] mix, int start, int beat, int rate, int midi,
                                 float gain, boolean octaveUp) {
        int step = beat / 4;
        for (int i = 0; i < 16; i++) {
            int tone = MINOR[i % MINOR.length];
            // The second pass lifts every fourth note an octave, so the loop
            // develops instead of repeating flat.
            int lift = (octaveUp && i % 4 == 0) ? 12 : 0;
            square(mix, start + i * step, step, rate, freq(midi + tone + lift), gain, 0.55f);
        }
    }

    /** A soft sustained triad underneath, on triangle waves. */
    private static void pad(float[] mix, int start, int length, int rate, int midi) {
        for (int tone : new int[]{0, 3, 7}) {
            triangle(mix, start, length, rate, freq(midi + tone), 0.09f);
        }
    }

    private static void drums(float[] mix, int start, int beat, int rate, boolean heavy) {
        for (int i = 0; i < 4; i++) {
            kick(mix, start + i * beat, rate, heavy ? 0.55f : 0.42f);
        }
        int step = beat / 2;
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 1 || heavy) {
                hat(mix, start + i * step, rate, i % 2 == 1 ? 0.16f : 0.08f);
            }
        }
    }

    // ---------- oscillators ----------

    private static float freq(int midi) {
        return (float) (440.0 * Math.pow(2.0, (midi - 69) / 12.0));
    }

    private static void square(float[] mix, int start, int length, int rate, float hz,
                               float gain, float decay) {
        int end = Math.min(mix.length, start + length);
        float period = rate / hz;
        for (int i = start; i < end; i++) {
            float phase = ((i - start) % period) / period;
            float env = envelope((i - start) / (float) length, decay);
            mix[i] += (phase < 0.5f ? 1f : -1f) * gain * env;
        }
    }

    private static void triangle(float[] mix, int start, int length, int rate, float hz,
                                 float gain) {
        int end = Math.min(mix.length, start + length);
        float period = rate / hz;
        for (int i = start; i < end; i++) {
            float phase = ((i - start) % period) / period;
            float value = phase < 0.5f ? (phase * 4f - 1f) : (3f - phase * 4f);
            float progress = (i - start) / (float) length;
            // Slow swell in and out so the pad never clicks at a bar boundary.
            float env = Math.min(1f, progress * 8f) * Math.min(1f, (1f - progress) * 8f);
            mix[i] += value * gain * env;
        }
    }

    /** A pitch-swept sine: the whole of a chiptune kick drum. */
    private static void kick(float[] mix, int start, int rate, float gain) {
        int length = rate / 8;
        int end = Math.min(mix.length, start + length);
        double phase = 0.0;
        for (int i = start; i < end; i++) {
            float progress = (i - start) / (float) length;
            float hz = 120f - 78f * progress;
            phase += 2.0 * Math.PI * hz / rate;
            mix[i] += (float) Math.sin(phase) * gain * (1f - progress) * (1f - progress);
        }
    }

    /** Short filtered noise for the hi-hat. */
    private static void hat(float[] mix, int start, int rate, float gain) {
        int length = rate / 40;
        int end = Math.min(mix.length, start + length);
        // A fixed seed keeps the loop bit-identical each pass, so looping it does
        // not produce an audible change in the noise texture.
        long seed = 0x5DEECE66DL + start;
        float previous = 0f;
        for (int i = start; i < end; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float noise = ((seed >> 40) & 0xFFFF) / 32768f - 1f;
            // One-pole high pass, so it sits above the bass instead of muddying it.
            float value = noise - previous * 0.7f;
            previous = noise;
            float progress = (i - start) / (float) length;
            mix[i] += value * gain * (1f - progress);
        }
    }

    private static float envelope(float progress, float decay) {
        float attack = Math.min(1f, progress * 30f);
        float body = 1f - progress * decay;
        float release = Math.min(1f, (1f - progress) * 12f);
        return attack * Math.max(0f, body) * release;
    }

    /** Scale the mix to a fixed headroom, so no track is louder than another. */
    private static short[] normalise(float[] mix) {
        float peak = 0f;
        for (float value : mix) {
            peak = Math.max(peak, Math.abs(value));
        }
        float scale = peak > 0f ? (0.82f * Short.MAX_VALUE / peak) : 0f;
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++) {
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i] * scale));
        }
        return out;
    }
}
