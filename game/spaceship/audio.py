"""Procedural audio: every sound in the game is synthesised at runtime.

There are no audio asset files. Sounds are built by *generators* that are pumped
for a few milliseconds per frame (``AudioBus.pump``), so a slow device spreads
the work over more frames instead of hanging. That replaces an earlier
background thread -- threading is unreliable in WebAssembly, and a per-frame
budget self-tunes across a 10x device speed range with no per-platform constant.

A half-built bank is a fully supported state: ``AudioBus.play`` no-ops on a name
it doesn't have yet.

If numpy or the mixer is unavailable the whole subsystem degrades to
``NULL_AUDIO``, a silent no-op -- gameplay code never has to check.
"""

import time

import pygame

from . import config, platform_api

try:
    import numpy as np
except ImportError:  # pragma: no cover - environment dependent
    np = None


# ---------- synth primitives ----------

def _samples_for(duration_ms, sample_rate=None):
    rate = sample_rate or config.SAMPLE_RATE
    return max(1, int(rate * duration_ms / 1000.0))


def adsr(n, attack=0.02, decay=0.10, sustain=0.7, release=0.30):
    """Amplitude envelope. Attack/decay shape is what gives a tone its punch."""
    env = np.ones(n)
    a = max(1, int(n * attack))
    d = max(1, int(n * decay))
    r = max(1, int(n * release))
    a = min(a, n)
    env[:a] = np.linspace(0.0, 1.0, a)
    if a + d <= n:
        env[a:a + d] = np.linspace(1.0, sustain, d)
        env[a + d:] = sustain
    if r <= n:
        env[n - r:] *= np.linspace(1.0, 0.0, r)
    return env


def _wave_samples(freq, n, wave, sample_rate):
    t = np.arange(n, dtype=np.float64) / sample_rate
    if wave == "noise":
        return np.random.uniform(-1.0, 1.0, n)
    phase = 2.0 * np.pi * freq * t
    if wave == "sine":
        return np.sin(phase)
    if wave == "triangle":
        return 2.0 / np.pi * np.arcsin(np.sin(phase))
    if wave == "saw":
        return 2.0 * (freq * t - np.floor(0.5 + freq * t))
    if wave == "detuned":
        # Two slightly detuned squares: a much richer retro tone than one.
        a = np.sign(np.sin(phase))
        b = np.sign(np.sin(2.0 * np.pi * freq * 1.006 * t))
        return (a + b) * 0.5
    return np.sign(np.sin(phase))  # square


def tone(freq, duration_ms, wave="square", volume=0.4, envelope=None, sample_rate=None):
    """A single note as a float array in [-1, 1]."""
    rate = sample_rate or config.SAMPLE_RATE
    n = _samples_for(duration_ms, rate)
    samples = _wave_samples(freq, n, wave, rate)
    env = envelope if envelope is not None else adsr(n)
    return samples * env * volume


def sweep(f0, f1, duration_ms, wave="square", volume=0.4, sample_rate=None):
    """A frequency glide -- the backbone of laser charges and boss sirens."""
    rate = sample_rate or config.SAMPLE_RATE
    n = _samples_for(duration_ms, rate)
    t = np.arange(n, dtype=np.float64) / rate
    freqs = np.linspace(f0, f1, n)
    phase = 2.0 * np.pi * np.cumsum(freqs) / rate
    if wave == "sine":
        samples = np.sin(phase)
    elif wave == "noise":
        samples = np.random.uniform(-1.0, 1.0, n) * np.linspace(1.0, 0.2, n)
    else:
        samples = np.sign(np.sin(phase))
    return samples * adsr(n, 0.02, 0.1, 0.85, 0.2) * volume


def noise_burst(duration_ms, volume=0.5, decay=4.0, sample_rate=None):
    """Explosion texture: white noise under a steep exponential decay."""
    rate = sample_rate or config.SAMPLE_RATE
    n = _samples_for(duration_ms, rate)
    noise = np.random.uniform(-1.0, 1.0, n)
    env = np.exp(-decay * np.linspace(0.0, 1.0, n))
    return noise * env * volume


def sequence(notes, wave="square", volume=0.35, sample_rate=None):
    """Concatenate ``(freq, duration_ms)`` pairs into one buffer."""
    chunks = [tone(f, d, wave=wave, volume=volume, sample_rate=sample_rate) for f, d in notes]
    return np.concatenate(chunks) if chunks else np.zeros(1)


def to_sound(samples):
    """Normalise a float array and hand it to pygame as a stereo Sound.

    ``Sound(buffer=...)`` is tried first on web: it just hands raw bytes in the
    mixer's declared format, which is a much narrower surface than sndarray and
    is the more likely of the two to work in the WebAssembly mixer.
    """
    peak = float(np.max(np.abs(samples))) if len(samples) else 0.0
    if peak > 1.0:
        samples = samples / peak
    ints = np.int16(np.clip(samples, -1.0, 1.0) * 32767)
    stereo = np.ascontiguousarray(np.column_stack([ints, ints]))

    if platform_api.IS_WEB:
        try:
            return pygame.mixer.Sound(buffer=stereo.tobytes())
        except Exception:
            pass
    try:
        return pygame.sndarray.make_sound(stereo)
    except Exception:
        try:
            return pygame.mixer.Sound(buffer=stereo.tobytes())
        except Exception:
            return None


# ---------- sfx bank ----------

NOTE_C4 = 261.63


def _iter_sfx():
    """Yield ``(name, Sound)`` one at a time so the build can be spread out.

    Menu sounds come first so the title screen is responsive immediately.
    The ORDER OF THESE STATEMENTS IS LOAD-BEARING: the noise-based sounds draw
    from ``np.random``, so reordering them changes the resulting waveforms (and
    the golden audio test will catch it).
    """
    yield "menu_move", to_sound(tone(520, 22, "square", 0.13))
    yield "menu_select", to_sound(sequence([(700, 40), (1050, 70)], "square", 0.20))
    yield "menu_back", to_sound(sequence([(700, 40), (460, 70)], "square", 0.18))

    yield "shoot_spread", to_sound(tone(880, 38, "square", 0.16, adsr(_samples_for(38), 0.01, 0.2, 0.4, 0.5)))
    yield "shoot_laser", to_sound(tone(1320, 52, "saw", 0.14, adsr(_samples_for(52), 0.05, 0.2, 0.6, 0.4)))
    yield "shoot_homing", to_sound(sweep(700, 980, 60, "square", 0.13))

    yield "enemy_hit", to_sound(tone(240, 34, "square", 0.22, adsr(_samples_for(34), 0.01, 0.3, 0.2, 0.6)))
    yield "tank_hit", to_sound(np.concatenate([
        tone(180, 26, "square", 0.20), tone(300, 22, "triangle", 0.14)]))
    yield "enemy_kill_small", to_sound(noise_burst(110, 0.34, 6.0))
    yield "enemy_kill_big", to_sound(
        noise_burst(260, 0.42, 3.2) + sweep(300, 60, 260, "sine", 0.22))

    yield "player_hit", to_sound(
        noise_burst(200, 0.40, 3.0) + sweep(320, 90, 200, "square", 0.28))
    yield "shield_break", to_sound(sweep(900, 220, 180, "square", 0.28))
    yield "shield_recharge", to_sound(sequence([(520, 60), (780, 90)], "sine", 0.22))

    # Eight pre-pitched graze ticks: the rising streak is the reward signal.
    for step in range(8):
        freq = 900 * (2 ** (step / 12.0))
        yield f"graze_{step}", to_sound(
            tone(freq, 26, "sine", 0.16, adsr(_samples_for(26), 0.02, 0.25, 0.3, 0.6)))

    yield "pickup_weapon", to_sound(sequence([(620, 44), (880, 46), (1170, 70)], "square", 0.22))
    yield "pickup_bomb", to_sound(sequence([(400, 60), (600, 80)], "triangle", 0.26))
    yield "weapon_swap", to_sound(sequence([(980, 40), (700, 60)], "square", 0.20))

    yield "overdrive_start", to_sound(sweep(300, 1200, 420, "detuned", 0.30))
    yield "overdrive_end", to_sound(sweep(900, 300, 300, "square", 0.20))
    yield "bomb", to_sound(
        noise_burst(560, 0.50, 2.0) + sweep(220, 40, 560, "sine", 0.34))

    yield "wave_start", to_sound(sequence([(520, 70), (660, 90)], "square", 0.20))
    yield "wave_clear", to_sound(sequence([(660, 70), (880, 70), (1170, 120)], "square", 0.22))
    yield "perfect", to_sound(sequence([(880, 60), (1170, 60), (1560, 60), (2090, 110)], "sine", 0.22))

    yield "boss_warning", to_sound(np.concatenate([
        sweep(400, 900, 380, "square", 0.30), sweep(900, 400, 380, "square", 0.30)]))
    yield "boss_phase", to_sound(sweep(180, 760, 420, "detuned", 0.34))
    yield "boss_death", to_sound(
        noise_burst(900, 0.52, 1.4) + sweep(420, 40, 900, "sine", 0.34))

    yield "upgrade_show", to_sound(sequence([(520, 70), (780, 70), (1040, 130)], "triangle", 0.22))
    yield "upgrade_pick", to_sound(sequence([(880, 60), (1320, 120)], "square", 0.24))
    yield "stage_banner", to_sound(sequence([(440, 90), (660, 90), (880, 150)], "detuned", 0.24))

    yield "game_over", to_sound(sequence([(400, 190), (300, 190), (200, 320)], "sine", 0.30))
    yield "new_high_score", to_sound(sequence(
        [(660, 90), (880, 90), (1170, 90), (1560, 90), (2090, 200)], "square", 0.24))


def _build_sfx():
    """Drain the generator into a dict. Used on desktop and by the golden test."""
    return dict(_iter_sfx())


# ---------- procedural music ----------

MINOR = [0, 2, 3, 5, 7, 8, 10]
STEPS_PER_BAR = 8

# Shorter loops on web: halves both build time and resident memory, which
# matters on a phone's heap.
WEB_BARS = 4

TRACKS = {
    "MENU": {
        "bpm": 92, "bars": 8, "root": 174.61,  # F3
        "layers": [
            {"wave": "triangle", "octave": -1, "volume": 0.20,
             "pattern": [0, None, None, None, 4, None, None, None]},
            {"wave": "sine", "octave": 1, "volume": 0.13,
             "pattern": [None, None, 7, None, None, 5, None, 4]},
        ],
    },
    "BATTLE_A": {
        "bpm": 132, "bars": 8, "root": 164.81,  # E3
        "layers": [
            {"wave": "square", "octave": -1, "volume": 0.22,
             "pattern": [0, 0, None, 0, 3, None, 0, None]},
            {"wave": "square", "octave": 1, "volume": 0.11,
             "pattern": [7, None, 5, None, 4, None, 5, 7]},
            {"wave": "noise", "octave": 0, "volume": 0.05,
             "pattern": [0, None, 0, None, 0, None, 0, 0]},
        ],
    },
    "BATTLE_B": {
        "bpm": 146, "bars": 8, "root": 146.83,  # D3
        "layers": [
            {"wave": "detuned", "octave": -1, "volume": 0.22,
             "pattern": [0, 0, 3, 0, 5, 0, 3, 2]},
            {"wave": "square", "octave": 1, "volume": 0.12,
             "pattern": [7, 9, 7, 5, 4, 5, 7, None]},
            {"wave": "noise", "octave": 0, "volume": 0.06,
             "pattern": [0, 0, None, 0, 0, None, 0, 0]},
        ],
    },
    "BOSS": {
        "bpm": 158, "bars": 8, "root": 130.81,  # C3
        "layers": [
            {"wave": "detuned", "octave": -1, "volume": 0.24,
             "pattern": [0, 1, 0, 1, 0, 1, 0, 1]},
            {"wave": "saw", "octave": 0, "volume": 0.11,
             "pattern": [7, None, 8, None, 7, None, 5, 4]},
            {"wave": "noise", "octave": 0, "volume": 0.07,
             "pattern": [0, 0, 0, 0, 0, 0, 0, 0]},
        ],
    },
}

# Built in this order so menu music is available almost immediately and battle
# music long before a boss can plausibly be reached.
TRACK_ORDER = ("MENU", "BATTLE_A", "BOSS", "BATTLE_B")


def _track_units(spec):
    """Number of notes a track renders -- one pump unit each."""
    bars = WEB_BARS if platform_api.IS_WEB else spec["bars"]
    total_steps = bars * STEPS_PER_BAR
    return sum(
        sum(1 for step in range(total_steps)
            if layer["pattern"][step % len(layer["pattern"])] is not None)
        for layer in spec["layers"]
    )


def _estimate_units():
    """Total pump units for the whole build, for an honest progress bar."""
    sfx_units = 30 + 8          # named sounds plus the eight graze ticks
    return sfx_units + sum(_track_units(spec) for spec in TRACKS.values())


def _degree_to_freq(root, degree, octave):
    semitone = MINOR[degree % len(MINOR)] + 12 * (degree // len(MINOR)) + 12 * octave
    return root * (2.0 ** (semitone / 12.0))


def render_track_steps(spec, sample_rate=None):
    """Generator rendering one track, yielding between notes.

    Iteration order (layer, then step) is preserved exactly from the original
    one-shot implementation, because the noise layers consume ``np.random`` and
    reordering would change the output.

    Returns the finished buffer via ``StopIteration.value``.
    """
    rate = sample_rate or config.SAMPLE_RATE
    bars = WEB_BARS if platform_api.IS_WEB else spec["bars"]
    step_ms = (60000.0 / spec["bpm"]) / 2.0     # eighth notes
    total_steps = bars * STEPS_PER_BAR
    step_n = _samples_for(step_ms, rate)
    total_n = step_n * total_steps
    buffer = np.zeros(total_n)

    for layer in spec["layers"]:
        pattern = layer["pattern"]
        for step in range(total_steps):
            degree = pattern[step % len(pattern)]
            if degree is None:
                continue
            if layer["wave"] == "noise":
                note = noise_burst(step_ms * 0.5, layer["volume"], 9.0, rate)
            else:
                freq = _degree_to_freq(spec["root"], degree, layer["octave"])
                note = tone(freq, step_ms * 0.92, layer["wave"], layer["volume"],
                            adsr(_samples_for(step_ms * 0.92, rate), 0.02, 0.25, 0.55, 0.35), rate)
            start = step * step_n
            end = min(total_n, start + len(note))
            buffer[start:end] += note[:end - start]
            yield

    peak = float(np.max(np.abs(buffer))) or 1.0
    return buffer / peak * 0.85


def render_track(spec, sample_rate=None):
    """Drain the step generator. Used on desktop and by the golden test."""
    generator = render_track_steps(spec, sample_rate)
    while True:
        try:
            next(generator)
        except StopIteration as stop:
            return stop.value


class MusicPlayer:
    """Crossfading looped playback across two reserved channels.

    Tracks become available one at a time as the build progresses; asking for
    one that isn't ready yet stores it as pending and starts it on arrival.
    """

    def __init__(self, volume=0.55):
        self.volume = volume
        self.enabled = True
        self._sounds = {}
        self._current = None
        self._channels = []
        self._active = 0
        self._fade_ms = 0.0
        self._fade_elapsed = 0.0

    def attach_channels(self, channels):
        self._channels = channels

    def add_track(self, name, sound):
        if sound is None:
            return
        self._sounds[name] = sound
        # If this is the track someone already asked for, start it now.
        if self._current == name:
            self._current = None
            self.play(name, fade_ms=0)

    @property
    def ready(self):
        return len(self._sounds) >= len(TRACKS)

    def has(self, name):
        return name in self._sounds

    def play(self, name, fade_ms=None):
        if not self.enabled or name == self._current:
            return
        sound = self._sounds.get(name)
        if sound is None:
            self._current = name       # pending; add_track will start it
            return
        if not self._channels:
            return
        self._current = name
        nxt = 1 - self._active
        channel = self._channels[nxt]
        try:
            channel.set_volume(0.0)
            channel.play(sound, loops=-1)
        except pygame.error:
            return
        self._active = nxt
        self._fade_ms = float(config.MUSIC_FADE_MS if fade_ms is None else fade_ms)
        self._fade_elapsed = 0.0
        if self._fade_ms <= 0:
            self._apply(1.0)

    def update(self, real_dt_ms):
        if not self._channels:
            return
        if self._fade_ms > 0.0:
            self._fade_elapsed += real_dt_ms
            self._apply(min(1.0, self._fade_elapsed / self._fade_ms))
            if self._fade_elapsed >= self._fade_ms:
                self._fade_ms = 0.0

    def _apply(self, progress):
        for index, channel in enumerate(self._channels):
            try:
                if index == self._active:
                    channel.set_volume(self.volume * progress)
                else:
                    channel.set_volume(self.volume * (1.0 - progress))
                    if progress >= 1.0:
                        channel.stop()
            except pygame.error:
                pass

    def set_volume(self, volume):
        self.volume = max(0.0, min(1.0, volume))
        if self._channels and self._fade_ms <= 0.0:
            self._apply(1.0)

    def stop(self):
        self._current = None
        for channel in self._channels:
            try:
                channel.stop()
            except pygame.error:
                pass


# ---------- the bus ----------

class AudioBus:
    """What gameplay talks to. Owns the SFX bank, music and volume state."""

    def __init__(self, sfx=None, music=None, available=True):
        self.sfx = sfx or {}
        self.music = music
        self.available = available
        self.muted = False
        self.sfx_volume = 0.8
        self._last_played = {}
        self._clock_ms = 0.0
        self._work = None
        self._built = 0
        self._total = 0
        self.build_error = None

    @property
    def enabled(self):
        return self.available and not self.muted

    @property
    def build_progress(self):
        """0.0..1.0. Only ever reaches exactly 1.0 once the build is finished.

        The unit count is an estimate, so the value is capped below 1.0 while
        work remains -- otherwise a caller could believe audio was ready while
        the last track was still being rendered.
        """
        if self._work is None:
            return 1.0
        return min(0.99, self._built / max(1, self._total))

    # -- incremental build ----------------------------------------------

    def begin_build(self):
        """Queue all synthesis work; ``pump`` does it a few ms at a time."""
        self._work = self._build_all()
        self._total = _estimate_units()

    def _build_all(self):
        for name, sound in _iter_sfx():
            if sound is not None:
                self.sfx[name] = sound
            self._built += 1
            yield
        if self.music is None:
            return
        for name in TRACK_ORDER:
            spec = TRACKS.get(name)
            if spec is None:
                continue
            generator = render_track_steps(spec)
            while True:
                try:
                    next(generator)
                except StopIteration as stop:
                    self.music.add_track(name, to_sound(stop.value))
                    break
                self._built += 1
                yield

    def pump(self, budget_ms):
        """Spend up to ``budget_ms`` advancing the build. Called once a frame."""
        if self._work is None:
            return
        deadline = time.perf_counter() + max(0.0, budget_ms) / 1000.0
        while time.perf_counter() < deadline:
            try:
                next(self._work)
            except StopIteration:
                self._work = None
                return
            except Exception as exc:
                # A failed build must not kill the game, but it must not vanish
                # silently either -- the reason is kept for the debug overlay.
                self.build_error = f"{type(exc).__name__}: {exc}"
                self._work = None
                return

    # -- playback --------------------------------------------------------

    def update(self, real_dt_ms):
        self._clock_ms += real_dt_ms
        if self.music is not None:
            self.music.update(real_dt_ms)

    def play(self, name, volume=1.0):
        if not self.enabled:
            return
        sound = self.sfx.get(name)
        if sound is None:
            return              # not built yet, or failed to build: silent
        # Throttle repeats so a dozen simultaneous graze ticks don't shred the mix.
        last = self._last_played.get(name, -9999.0)
        if self._clock_ms - last < config.SFX_THROTTLE_MS:
            return
        self._last_played[name] = self._clock_ms
        try:
            sound.set_volume(self.sfx_volume * volume)
            sound.play()
        except pygame.error:
            pass

    def graze_tick(self, streak):
        self.play(f"graze_{min(7, max(0, streak))}")

    def play_music(self, name, fade_ms=None):
        if self.music is not None and not self.muted:
            self.music.play(name, fade_ms)

    def stop_music(self):
        if self.music is not None:
            self.music.stop()

    def set_sfx_volume(self, percent):
        self.sfx_volume = max(0.0, min(1.0, percent / 100.0))

    def set_music_volume(self, percent):
        if self.music is not None:
            self.music.set_volume(max(0.0, min(1.0, percent / 100.0)))

    def set_muted(self, muted):
        self.muted = bool(muted)
        if self.music is not None and self.muted:
            self.music.stop()


class NullAudio(AudioBus):
    """Silent stand-in used when audio is unavailable, and in tests."""

    def __init__(self):
        super().__init__(sfx={}, music=None, available=False)

    def play(self, name, volume=1.0):
        return

    def play_music(self, name, fade_ms=None):
        return

    def begin_build(self):
        return

    def pump(self, budget_ms):
        return


NULL_AUDIO = NullAudio()


def create(muted=False, sfx_volume=80, music_volume=55):
    """Initialise the mixer and queue audio synthesis.

    Nothing is synthesised here -- ``begin_build`` only queues the work, which
    ``AudioBus.pump`` then does a few milliseconds at a time. That keeps the
    window responsive from the very first frame.
    """
    if np is None:
        return NULL_AUDIO, "numpy is not installed - running without audio"
    try:
        pygame.mixer.init(frequency=config.SAMPLE_RATE, size=-16, channels=2)
    except Exception as exc:  # pragma: no cover - depends on audio device
        return NULL_AUDIO, f"audio unavailable ({exc}) - running silent"

    try:
        pygame.mixer.set_num_channels(config.MIXER_CHANNELS)
        pygame.mixer.set_reserved(config.MUSIC_CHANNELS)
    except Exception:
        # A capped channel count must not cost us all audio.
        pass

    music = MusicPlayer(volume=music_volume / 100.0)
    try:
        music.attach_channels([pygame.mixer.Channel(i) for i in range(config.MUSIC_CHANNELS)])
    except Exception:  # pragma: no cover
        music = None

    bus = AudioBus(sfx={}, music=music, available=True)
    bus.set_sfx_volume(sfx_volume)
    bus.set_muted(muted)
    bus.begin_build()
    return bus, None
