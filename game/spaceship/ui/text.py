"""Cached text rendering.

The prototype built a fresh ``pygame.font.Font`` on every ``draw_text`` call,
which is fine for three labels and ruinous for a real HUD. Fonts are cached by
size, and rendered surfaces are cached by (text, size, colour, theme token).
"""

import os
from functools import lru_cache

import pygame

from .. import theme

# A bundled monospace font, if one has been supplied. The HUD leans on
# monospace for aligned readouts (zero-padded scores, volume bars, the high
# score table), and system fonts are NOT available in a WebAssembly build --
# pygame's match_font returns None there and silently falls back to a
# proportional face. Drop a .ttf at this path to fix that.
#
# Deliberately not shipping a system font instead: Courier New and Consolas are
# Microsoft-licensed and can't be redistributed in a published game.
_BUNDLED = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "assets", "mono.ttf")


def bundled_font_available():
    return os.path.exists(_BUNDLED)


def monospace_available():
    """False when we've fallen back to a proportional face."""
    return bundled_font_available() or pygame.font.match_font("couriernew") is not None

_surface_cache = {}
_cache_token = -1
_CACHE_LIMIT = 512


@lru_cache(maxsize=32)
def get_font(size, bold=True):
    """Best available monospace face at this size.

    Falls back through: bundled asset -> system Courier New -> pygame's own
    default (proportional, so columns will not align, but nothing crashes).
    """
    if bundled_font_available():
        try:
            return pygame.font.Font(_BUNDLED, size)
        except Exception:
            pass
    try:
        path = pygame.font.match_font("couriernew", bold=bold)
    except Exception:
        path = None
    return pygame.font.Font(path, size)


def _render(text, size, color, bold=True):
    global _cache_token
    if _cache_token != theme.token:
        _surface_cache.clear()
        _cache_token = theme.token
    key = (text, size, color, bold)
    surface = _surface_cache.get(key)
    if surface is None:
        surface = get_font(size, bold).render(text, True, color)
        if len(_surface_cache) > _CACHE_LIMIT:
            _surface_cache.clear()
        _surface_cache[key] = surface
    return surface


def measure(text, size, bold=True):
    return get_font(size, bold).size(text)


def draw_text(surface, text, size, x, y, color, center=True, alpha=None, bold=True):
    """Blit a string. ``center`` centres on (x, y), otherwise it's the top-left."""
    rendered = _render(text, size, color, bold)
    if alpha is not None and alpha < 255:
        rendered = rendered.copy()
        rendered.set_alpha(int(alpha))
    rect = rendered.get_rect()
    if center:
        rect.center = (int(x), int(y))
    else:
        rect.topleft = (int(x), int(y))
    surface.blit(rendered, rect)
    return rect


def draw_text_right(surface, text, size, right_x, y, color, alpha=None):
    rendered = _render(text, size, color)
    if alpha is not None and alpha < 255:
        rendered = rendered.copy()
        rendered.set_alpha(int(alpha))
    rect = rendered.get_rect()
    rect.topright = (int(right_x), int(y))
    surface.blit(rendered, rect)
    return rect


def clear_cache():
    _surface_cache.clear()
    get_font.cache_clear()
