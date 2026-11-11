package com.ashwin.retrospaceship;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * All audio: sampled effects, and music synthesised on the device.
 *
 * <p>The effects are real recorded assets played through a {@link SoundPool}.
 * The music is not sampled at all -- each track is generated as PCM the first
 * time it is asked for and then looped by an {@link AudioTrack}. That keeps the
 * APK small, lets the music follow the fight (menu, two battle themes, a boss
 * theme) without shipping four audio files, and means the loop is seamless by
 * construction rather than by careful trimming.
 */
public final class Sfx {

    private static final int RATE = 22050;
    private static final float THROTTLE_MS = 40f;

    public static final int MENU = 0, BATTLE_A = 1, BATTLE_B = 2, BOSS = 3;

    private static final String[] SOUND_NAMES = {
            "shoot_spread", "shoot_laser", "shoot_homing",
            "enemy_hit", "tank_hit", "enemy_kill_small", "enemy_kill_big",
            "player_hit", "shield_break", "shield_recharge", "bomb",
            "boss_warning", "boss_phase", "boss_death",
            "pickup_weapon", "pickup_bomb", "weapon_swap",
            "overdrive_start", "overdrive_end",
            "wave_start", "wave_clear", "perfect", "stage_banner", "graze",
            "ui_move", "ui_select", "ui_back", "game_over",
    };

    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, Long> lastPlayed = new HashMap<>();

    /**
     * Guards everything to do with music. Track changes are requested from the
     * render thread while the synthesiser fills the cache on its own thread, so
     * both sides take this lock.
     */
    private final Object musicLock = new Object();
    private final short[][] musicCache = new short[4][];

    private AudioTrack music;
    private int currentTrack = -1;
    private volatile boolean released = false;

    public boolean muted = false;
    public int sfxVolume = 80;
    public int musicVolume = 55;

    public Sfx(AssetManager am) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder()
                .setMaxStreams(18)
                .setAudioAttributes(attributes)
                .build();

        for (String name : SOUND_NAMES) {
            AssetFileDescriptor fd = null;
            try {
                fd = am.openFd("sfx/" + name + ".ogg");
                ids.put(name, pool.load(fd, 1));
            } catch (IOException e) {
                // A missing effect silently does nothing rather than crashing a run.
            } finally {
                if (fd != null) {
                    try {
                        fd.close();
                    } catch (IOException ignored) {
                        // Descriptor already handed to the pool; nothing to recover.
                    }
                }
            }
        }

        startSynth();
    }

    /**
     * Synthesise every music track up front, off the game thread.
     *
     * <p>Rendering a track takes long enough to drop frames, and the tracks
     * change at exactly the worst moments -- a stage transition, a boss walking
     * on. Doing the work ahead of time on a background thread means a track
     * change is only ever an {@code AudioTrack} swap. Anything requested before
     * its audio is ready simply starts the moment the render lands.
     */
    private void startSynth() {
        // Ordered by when each is first needed: the menu plays immediately, the
        // boss theme not for several minutes.
        final int[] order = {MENU, BATTLE_A, BATTLE_B, BOSS};
        Thread synth = new Thread(() -> {
            for (int track : order) {
                if (released) {
                    return;
                }
                short[] pcm = Music.render(track, RATE);
                synchronized (musicLock) {
                    if (released) {
                        return;
                    }
                    musicCache[track] = pcm;
                    // If this is the track the game is already waiting on, start it.
                    if (currentTrack == track && music == null) {
                        startLocked();
                    }
                }
            }
        }, "retro-spaceship-synth");
        synth.setDaemon(true);
        synth.setPriority(Thread.MIN_PRIORITY);
        synth.start();
    }

    // ---------- effects ----------

    public void play(String name) {
        play(name, 1f);
    }

    /**
     * Play an effect at a relative volume.
     *
     * <p>Repeats of the same effect inside {@link #THROTTLE_MS} are dropped. A
     * spread weapon at level five fires five bullets in one frame, and five
     * copies of one sample stacking is not five times louder -- it is a click.
     */
    public void play(String name, float gain) {
        if (muted || sfxVolume <= 0) {
            return;
        }
        Integer id = ids.get(name);
        if (id == null) {
            return;
        }
        long now = System.nanoTime() / 1000000L;
        Long last = lastPlayed.get(name);
        if (last != null && now - last < THROTTLE_MS) {
            return;
        }
        lastPlayed.put(name, now);
        float volume = Math.max(0f, Math.min(1f, gain * sfxVolume / 100f));
        pool.play(id, volume, volume, 1, 0, 1f);
    }

    /** A graze tick that rises in pitch with the streak, so a run of them sings. */
    public void grazeTick(int step) {
        if (muted || sfxVolume <= 0) {
            return;
        }
        Integer id = ids.get("graze");
        if (id == null) {
            return;
        }
        float volume = Math.max(0f, Math.min(1f, 0.45f * sfxVolume / 100f));
        float rate = Math.min(2f, 1f + step * 0.08f);
        pool.play(id, volume, volume, 1, 0, rate);
    }

    // ---------- music ----------

    public void playMusic(int track) {
        synchronized (musicLock) {
            if (track == currentTrack && music != null) {
                return;
            }
            currentTrack = track;
            stopLocked();
            if (muted || musicVolume <= 0) {
                return;
            }
            startLocked();
        }
    }

    public void stopMusic() {
        synchronized (musicLock) {
            stopLocked();
        }
    }

    /** Re-apply volume and mute settings to whatever is currently playing. */
    public void refreshMusic() {
        synchronized (musicLock) {
            if (muted || musicVolume <= 0) {
                stopLocked();
                return;
            }
            if (music == null) {
                startLocked();
            } else {
                music.setVolume(musicVolume / 100f * 0.6f);
            }
        }
    }

    private void stopLocked() {
        if (music == null) {
            return;
        }
        try {
            music.pause();
            music.flush();
            music.release();
        } catch (IllegalStateException ignored) {
            // Track already torn down; nothing further to release.
        }
        music = null;
    }

    /**
     * Start the current track if its audio has been synthesised yet.
     *
     * <p>A track whose render has not landed is not an error and not worth
     * blocking for: the synthesiser starts it as soon as it is ready.
     */
    private void startLocked() {
        if (released || currentTrack < 0 || currentTrack >= musicCache.length) {
            return;
        }
        short[] pcm = musicCache[currentTrack];
        if (pcm == null) {
            return;
        }
        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.setLoopPoints(0, pcm.length, -1);
            track.setVolume(musicVolume / 100f * 0.6f);
            track.play();
            music = track;
        } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException e) {
            // Audio focus or buffer allocation can fail on a busy device. Silence
            // is an acceptable outcome; a crash is not.
            music = null;
        }
    }

    public void setMuted(boolean value) {
        muted = value;
        refreshMusic();
    }

    public void release() {
        released = true;
        synchronized (musicLock) {
            stopLocked();
        }
        pool.release();
    }
}
