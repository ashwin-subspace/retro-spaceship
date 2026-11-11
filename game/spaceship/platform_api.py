"""Platform differences, isolated to one layer-0 module.

Nothing else in the codebase branches on "am I on web?" -- everything reads the
values published here. That is what keeps a single code path working on both
desktop and in the browser instead of forking into two variants.
"""

import sys

from . import config

IS_WEB = sys.platform == "emscripten"

# Frame pacing. On web we let the browser's requestAnimationFrame set the
# cadence (via the await in the async loop) rather than having pygame spin-wait
# against the compositor, and we target 30fps -- which is free, because the
# whole game is delta-time driven.
FPS_CAP = 30 if IS_WEB else 60

# Visual budget. LOW trims particles, bullet trails and the CRT overlay.
# Exposed as a setting so the levers can be A/B tested on desktop rather than
# only ever exercised on a phone.
QUALITY = "LOW" if IS_WEB else "HIGH"

_QUALITY_BUDGETS = {
    # (max particles, bullet trail samples, crt on by default)
    "HIGH": (config.MAX_PARTICLES, 8, True),
    "LOW": (220, 3, False),
}


def set_quality(level):
    global QUALITY
    QUALITY = level if level in _QUALITY_BUDGETS else "HIGH"
    return QUALITY


def max_particles():
    return _QUALITY_BUDGETS[QUALITY][0]


def trail_length():
    return _QUALITY_BUDGETS[QUALITY][1]


def crt_default():
    return _QUALITY_BUDGETS[QUALITY][2]

# Drives on-screen hint text and whether the touch gutter is drawn.
INPUT_MODE = "touch" if IS_WEB else "keyboard"

# Absolute drag puts your fingertip on top of the 3px hitbox you need to see,
# so on touch the ship tracks finger *motion* from a captured anchor instead.
DRAG_RELATIVE = IS_WEB

# Milliseconds per frame the audio system may spend building sounds.
AUDIO_BUDGET_MS = 4.0 if IS_WEB else 12.0

SAVE_KEY = "retro_spaceship:game_save"

_memory_store = {}
_storage_note = None


def _window():
    """The browser window object under pygbag, else None."""
    if not IS_WEB:
        return None
    try:
        import platform as _p
        return _p.window
    except Exception:
        return None


def storage_note():
    """A one-line warning to surface at startup, or None."""
    return _storage_note


def read_text(key, path=None):
    """Read a persisted string. Returns None when absent.

    ``path`` is the desktop file location; it is ignored on web, where the
    browser's localStorage is keyed by ``key`` instead.
    """
    global _storage_note
    if IS_WEB:
        window = _window()
        if window is not None:
            try:
                return window.localStorage.getItem(key)
            except Exception:
                # iOS Safari private mode throws even on tiny payloads.
                _storage_note = "browser storage blocked - progress won't be saved"
        return _memory_store.get(key)
    return _read_file(path)


def write_text(key, text, path=None):
    global _storage_note
    if IS_WEB:
        window = _window()
        if window is not None:
            try:
                window.localStorage.setItem(key, text)
                return True
            except Exception:
                _storage_note = "browser storage blocked - progress won't be saved"
        _memory_store[key] = text
        return False
    return _write_file(path, text)


# ---------- desktop file backend ----------
# Deliberately identical to the original implementation, including the atomic
# temp-file-then-replace dance, so desktop saves stay byte-for-byte the same.

def _read_file(path):
    try:
        with open(path, "r") as handle:
            return handle.read()
    except Exception:
        return None


def _write_file(path, text):
    import os
    import tempfile

    handle = None
    try:
        handle = tempfile.NamedTemporaryFile(
            "w", delete=False, dir=os.path.dirname(path),
            prefix=".game_save_", suffix=".tmp")
        try:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        finally:
            handle.close()
        os.replace(handle.name, path)
        return True
    except Exception:
        if handle is not None:
            try:
                os.unlink(handle.name)
            except Exception:
                pass
        return False


# ---------- layout ----------

def gutter_height(window_w=None, window_h=None):
    """Height of the touch control strip below the play field (0 on desktop).

    Buttons never overlay the play field: the ship can reach every horizontal
    position and the bottom 70% of it, so there is no free space inside.
    """
    if not IS_WEB:
        return 0
    try:
        window = _window()
        win_w = window_w or window.innerWidth
        win_h = window_h or window.innerHeight
        target = round(config.HEIGHT * (win_h / max(1, win_w)))
        return max(150, min(420, target - config.HEIGHT))
    except Exception:
        return 260
