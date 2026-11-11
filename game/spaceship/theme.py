"""Colour themes.

``THEME`` is a mutable namespace whose attributes are swapped in place by
``apply_theme``. Draw code reads ``THEME.GREEN`` etc. directly; because the
object is never rebound, no module ever needs a ``global`` declaration.

``token`` increments on every theme change so caches of pre-baked surfaces
(particle dots, CRT overlay, rendered text) know to invalidate.
"""

THEMES = {
    "phosphor": {"BLACK": (5, 5, 10), "GREEN": (57, 255, 20), "CYAN": (0, 255, 255), "MAGENTA": (255, 0, 200),
                 "YELLOW": (255, 255, 0), "ORANGE": (255, 140, 0), "RED": (255, 40, 40), "WHITE": (230, 230, 230)},
    "cyberpunk": {"BLACK": (8, 4, 18), "GREEN": (190, 60, 255), "CYAN": (0, 230, 255), "MAGENTA": (255, 20, 180),
                  "YELLOW": (255, 225, 60), "ORANGE": (255, 90, 180), "RED": (255, 40, 90), "WHITE": (235, 225, 255)},
    "amber": {"BLACK": (10, 6, 0), "GREEN": (255, 176, 0), "CYAN": (255, 210, 120), "MAGENTA": (255, 90, 0),
              "YELLOW": (255, 220, 90), "ORANGE": (255, 140, 0), "RED": (255, 60, 30), "WHITE": (255, 235, 200)},
    "ice": {"BLACK": (4, 8, 20), "GREEN": (120, 220, 255), "CYAN": (0, 255, 255), "MAGENTA": (120, 140, 255),
            "YELLOW": (210, 240, 255), "ORANGE": (90, 180, 255), "RED": (255, 70, 90), "WHITE": (235, 245, 255)},
}
THEME_ORDER = ["phosphor", "cyberpunk", "amber", "ice"]

ROLES = ("BLACK", "GREEN", "CYAN", "MAGENTA", "YELLOW", "ORANGE", "RED", "WHITE")


class _ThemeNamespace:
    """Attribute bag holding the active palette."""

    name = "phosphor"


THEME = _ThemeNamespace()
token = 0


def apply_theme(name):
    """Swap the active palette in place and bump the invalidation token."""
    global token
    if name not in THEMES:
        name = "phosphor"
    for key, value in THEMES[name].items():
        setattr(THEME, key, value)
    THEME.name = name
    token += 1
    return name


def color(role):
    """Resolve a colour-law role name (see ``config.COLOR_*``) to an RGB tuple."""
    return getattr(THEME, role, THEME.WHITE)


def mix(role_a, role_b, amount):
    """Blend two theme colours; ``amount`` 0.0 -> a, 1.0 -> b."""
    ca, cb = color(role_a), color(role_b)
    return tuple(int(a + (b - a) * amount) for a, b in zip(ca, cb))


def shade(rgb, factor):
    """Scale an RGB tuple's brightness, clamped to valid channel range."""
    return tuple(max(0, min(255, int(c * factor))) for c in rgb)


apply_theme("phosphor")
