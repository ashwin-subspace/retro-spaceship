"""Spawn formations. Pure maths -- no pygame, deterministic given the RNG.

The prototype spawned every enemy at a random x. Formations are what make a
wave read as a deliberate attack rather than as noise, and they give the player
shapes to learn.

Each builder returns a list of ``(x, y, delay_ms)`` spawn points; the director
pairs them with archetypes.
"""

import math

from . import config, rng

MARGIN = 40.0


def _span(count, margin=MARGIN):
    """Evenly spaced x positions across the playfield."""
    if count <= 1:
        return [config.WIDTH * 0.5]
    usable = config.WIDTH - margin * 2
    step = usable / (count - 1)
    return [margin + step * i for i in range(count)]


def line(count, stagger_ms=90.0):
    """A horizontal rank entering together."""
    return [(x, -30.0, i * stagger_ms) for i, x in enumerate(_span(count))]


def wedge(count, stagger_ms=70.0):
    """A V pointing down -- the classic arcade approach."""
    points = []
    center = config.WIDTH * 0.5
    spacing = min(56.0, (config.WIDTH - MARGIN * 2) / max(2, count))
    for i in range(count):
        side = -1 if i % 2 == 0 else 1
        rank = (i + 1) // 2
        x = center + side * rank * spacing
        y = -30.0 - rank * 26.0
        points.append((max(MARGIN, min(config.WIDTH - MARGIN, x)), y, i * stagger_ms))
    return points


def column(count, stagger_ms=260.0, x=None):
    """Single file down one lane -- reads clearly, great for chargers."""
    lane = x if x is not None else rng.uniform(MARGIN, config.WIDTH - MARGIN)
    return [(lane, -30.0, i * stagger_ms) for i in range(count)]


def arc(count, stagger_ms=60.0):
    """A shallow bowl shape."""
    points = []
    xs = _span(count)
    for i, x in enumerate(xs):
        t = (i / max(1, count - 1)) * 2.0 - 1.0
        y = -30.0 - (1.0 - t * t) * 46.0
        points.append((x, y, i * stagger_ms))
    return points


def pincer(count, stagger_ms=120.0):
    """Two groups entering from the left and right edges."""
    points = []
    half = max(1, count // 2)
    for i in range(count):
        left = i % 2 == 0
        rank = i // 2
        x = -24.0 if left else config.WIDTH + 24.0
        y = 90.0 + rank * 54.0
        points.append((x, min(y, config.HEIGHT * 0.45), i * stagger_ms))
        if rank >= half:
            break
    return points


def trickle(count, stagger_ms=340.0):
    """Random lanes, but rate limited so two never overlap awkwardly."""
    points = []
    last_x = None
    for i in range(count):
        for _ in range(6):
            x = rng.uniform(MARGIN, config.WIDTH - MARGIN)
            if last_x is None or abs(x - last_x) >= 40.0:
                break
        last_x = x
        points.append((x, -30.0, i * stagger_ms))
    return points


def escort(count, stagger_ms=80.0):
    """A leader with flankers -- pairs a tank with fodder."""
    points = [(config.WIDTH * 0.5, -34.0, 0.0)]
    for i in range(max(0, count - 1)):
        side = -1 if i % 2 == 0 else 1
        rank = (i // 2) + 1
        x = config.WIDTH * 0.5 + side * (46.0 * rank)
        points.append((max(MARGIN, min(config.WIDTH - MARGIN, x)), -30.0 - rank * 18.0,
                       (i + 1) * stagger_ms))
    return points


BUILDERS = {
    "LINE": line,
    "WEDGE": wedge,
    "COLUMN": column,
    "ARC": arc,
    "PINCER": pincer,
    "TRICKLE": trickle,
    "ESCORT": escort,
}

# Formations that suit a given flavour; the director picks within these.
FLAVOR_FORMATIONS = {
    "STANDARD": ("LINE", "WEDGE", "ARC", "TRICKLE"),
    "SWARM": ("LINE", "ARC", "WEDGE"),
    "ARMORED": ("ESCORT", "LINE"),
    "BLITZ": ("COLUMN", "WEDGE", "TRICKLE"),
    "BARRAGE": ("LINE", "ARC"),
    "AMBUSH": ("PINCER", "COLUMN"),
    "ELITE": ("WEDGE", "ESCORT", "ARC"),
}


def build(name, count):
    """Build a formation by name, falling back to a safe default."""
    builder = BUILDERS.get(name, line)
    points = builder(count)
    # Never let a formation put a spawn outside the playfield horizontally
    # (pincer intentionally starts off-edge, so allow a margin of overshoot).
    cleaned = []
    for x, y, delay in points:
        cleaned.append((max(-40.0, min(config.WIDTH + 40.0, x)), y, max(0.0, delay)))
    return cleaned


def pick_for(flavor):
    options = FLAVOR_FORMATIONS.get(flavor, FLAVOR_FORMATIONS["STANDARD"])
    return rng.choice(options)


def circle_points(count, cx, cy, radius):
    """Utility for boss adds and ring spawns."""
    if count <= 0:
        return []
    step = math.tau / count
    return [(cx + math.cos(step * i) * radius, cy + math.sin(step * i) * radius, 0.0)
            for i in range(count)]
