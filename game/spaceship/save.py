"""Persistent settings, high scores and lifetime totals.

The save file lives next to the entry script (the package *parent*), not inside
the package, so splitting the code into modules doesn't orphan an existing save.

Loading is corruption-proof: any failure falls back to defaults rather than
crashing, and a versioned migration chain upgrades older files in place.
"""

import json
import os
import time

from . import platform_api, theme

SCHEMA_VERSION = 2

# The package parent, i.e. the directory holding retro_spaceship_game.py.
SAVE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAVE_PATH = os.path.join(SAVE_DIR, "game_save.json")

MAX_HIGH_SCORES = 5

DEFAULT_SAVE = {
    "version": SCHEMA_VERSION,
    "theme": "phosphor",
    "muted": False,
    "sfx_volume": 80,
    "music_volume": 55,
    "crt_enabled": True,
    "quality": "",          # "" = follow the platform default
    "high_score": 0,
    "high_scores": [],
    "totals": {
        "runs": 0,
        "kills": 0,
        "bosses_killed": 0,
        "playtime_ms": 0,
        "best_combo": 0,
        "best_graze": 0,
    },
}


# ---------- migrations ----------

def _migrate_1_to_2(data):
    """v1 had a single scalar high_score; seed the table from it."""
    data["version"] = 2
    score = int(data.get("high_score", 0) or 0)
    if score > 0 and not data.get("high_scores"):
        data["high_scores"] = [{
            "score": score,
            "stage": 1,
            "wave": 1,
            "time_ms": 0,
            "weapon": "SPREAD",
            "date": "legacy",
        }]
    return data


MIGRATIONS = {1: _migrate_1_to_2}


def _deep_defaults(data):
    """Fill in any missing keys (including nested totals) from defaults."""
    merged = {**DEFAULT_SAVE, **data}
    totals = dict(DEFAULT_SAVE["totals"])
    totals.update(merged.get("totals") or {})
    merged["totals"] = totals
    if not isinstance(merged.get("high_scores"), list):
        merged["high_scores"] = []
    return merged


def load():
    try:
        raw = platform_api.read_text(platform_api.SAVE_KEY, SAVE_PATH)
        data = json.loads(raw) if raw else {}
        if not isinstance(data, dict):
            raise ValueError("save file is not an object")
    except Exception:
        data = {}

    version = data.get("version", 1)
    if not isinstance(version, int) or version < 1:
        version = 1
        data["version"] = 1
    # Apply migrations in order until current.
    guard = 0
    while data.get("version", 1) < SCHEMA_VERSION and guard < 16:
        migrate = MIGRATIONS.get(data.get("version", 1))
        if migrate is None:
            break
        data = migrate(data)
        guard += 1
    data["version"] = SCHEMA_VERSION

    data = _deep_defaults(data)
    if data.get("theme") not in theme.THEMES:
        data["theme"] = "phosphor"
    if data.get("quality") not in ("", "HIGH", "LOW"):
        data["quality"] = ""
    data["sfx_volume"] = _clamp_volume(data.get("sfx_volume", 80))
    data["music_volume"] = _clamp_volume(data.get("music_volume", 55))
    data["high_scores"] = _sorted_scores(data["high_scores"])
    data["high_score"] = data["high_scores"][0]["score"] if data["high_scores"] else int(data.get("high_score", 0) or 0)
    return data


def _clamp_volume(value):
    try:
        return max(0, min(100, int(value)))
    except (TypeError, ValueError):
        return 80


def _sorted_scores(entries):
    clean = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        try:
            clean.append({
                "score": int(entry.get("score", 0)),
                "stage": int(entry.get("stage", 1)),
                "wave": int(entry.get("wave", 1)),
                "time_ms": int(entry.get("time_ms", 0)),
                "weapon": str(entry.get("weapon", "SPREAD")),
                "date": str(entry.get("date", "")),
            })
        except (TypeError, ValueError):
            continue
    clean.sort(key=lambda e: e["score"], reverse=True)
    return clean[:MAX_HIGH_SCORES]


def save(data):
    """Persist the save, preserving keys we don't know about.

    The storage backend is chosen by ``platform_api``: an atomic file write on
    desktop, browser localStorage on web. The serialised form is identical in
    both, so a save can be copied between platforms by hand.
    """
    payload = dict(data)
    payload["version"] = SCHEMA_VERSION
    # Strip transient runtime-only fields that share the dict.
    for transient in ("menu_index", "settings_return_mode", "pause_started_at"):
        payload.pop(transient, None)
    try:
        text = json.dumps(payload, indent=1)
    except Exception:
        return
    # A failed write must never break the game (read-only dir, blocked storage).
    platform_api.write_text(platform_api.SAVE_KEY, text, SAVE_PATH)


def qualifies(data, score):
    """Would this score make the table?"""
    if score <= 0:
        return False
    table = data.get("high_scores") or []
    if len(table) < MAX_HIGH_SCORES:
        return True
    return score > table[-1]["score"]


def add_high_score(data, score, stage, wave, time_ms, weapon):
    """Insert a run into the table. Returns its 1-based rank, or None."""
    if score <= 0:
        return None
    entry = {
        "score": int(score),
        "stage": int(stage),
        "wave": int(wave),
        "time_ms": int(time_ms),
        "weapon": str(weapon),
        "date": time.strftime("%Y-%m-%d"),
    }
    table = list(data.get("high_scores") or [])
    table.append(entry)
    table = _sorted_scores(table)
    data["high_scores"] = table
    data["high_score"] = table[0]["score"] if table else 0
    save(data)
    for index, row in enumerate(table):
        if row is entry or (row["score"] == entry["score"] and row["date"] == entry["date"]
                            and row["time_ms"] == entry["time_ms"]):
            return index + 1
    return None


def record_run(data, stats):
    """Fold a finished run's stats into lifetime totals."""
    totals = data.setdefault("totals", dict(DEFAULT_SAVE["totals"]))
    totals["runs"] = totals.get("runs", 0) + 1
    totals["kills"] = totals.get("kills", 0) + stats.kills
    totals["bosses_killed"] = totals.get("bosses_killed", 0) + stats.bosses_killed
    totals["playtime_ms"] = totals.get("playtime_ms", 0) + int(stats.time_ms)
    totals["best_combo"] = max(totals.get("best_combo", 0), stats.best_combo)
    totals["best_graze"] = max(totals.get("best_graze", 0), stats.max_graze_streak)
