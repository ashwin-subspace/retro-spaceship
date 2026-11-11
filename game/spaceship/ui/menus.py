"""Menu widgets.

A menu row is a ``MenuItem`` rather than a ``(label, value)`` tuple, because
settings need rows whose label reflects live state and that adjust with
left/right -- a volume slider can't be expressed as a static tuple.
"""

import pygame

from .. import config, theme
from . import text as text_mod


class MenuItem:
    def __init__(self, label, action=None, on_left=None, on_right=None, enabled=True):
        self._label = label
        self.action = action
        self.on_left = on_left
        self.on_right = on_right
        self.enabled = enabled

    @property
    def label(self):
        return self._label() if callable(self._label) else self._label

    @property
    def adjustable(self):
        return self.on_left is not None or self.on_right is not None


class Menu:
    def __init__(self, items):
        self.items = items
        self.index = 0
        # Row rectangles, recorded by draw() so taps can be hit-tested. They are
        # one frame stale, which is irrelevant: menu layout is static.
        self.rects = []
        self._pressed = None

    def reset(self):
        self.index = 0
        self._pressed = None

    @property
    def current(self):
        return self.items[self.index] if self.items else None

    def _step(self, delta):
        if not self.items:
            return
        for _ in range(len(self.items)):
            self.index = (self.index + delta) % len(self.items)
            if self.items[self.index].enabled:
                return

    def handle(self, event, audio=None):
        """Returns the selected action, or None."""
        if event.type != pygame.KEYDOWN or not self.items:
            return None
        key = event.key
        if key in (pygame.K_UP, pygame.K_w):
            self._step(-1)
            if audio:
                audio.play("menu_move")
        elif key in (pygame.K_DOWN, pygame.K_s):
            self._step(1)
            if audio:
                audio.play("menu_move")
        elif key in (pygame.K_LEFT, pygame.K_a):
            item = self.current
            if item is not None and item.on_left:
                item.on_left()
                if audio:
                    audio.play("menu_move")
        elif key in (pygame.K_RIGHT, pygame.K_d):
            item = self.current
            if item is not None and item.on_right:
                item.on_right()
                if audio:
                    audio.play("menu_move")
        elif key in (pygame.K_RETURN, pygame.K_KP_ENTER, pygame.K_SPACE):
            item = self.current
            if item is None:
                return None
            if item.action is None and item.on_right:
                item.on_right()
                if audio:
                    audio.play("menu_move")
                return None
            if audio:
                audio.play("menu_select")
            return item.action
        return None

    ROW_WIDTH = 320

    def draw(self, surface, center_x, start_y, spacing=34, size=19, touch=False):
        self.rects = []
        for i, item in enumerate(self.items):
            selected = i == self.index
            color = theme.color("CYAN") if selected else theme.color("WHITE")
            alpha = 255 if selected else 190
            label = item.label
            # On touch the chevrons are always shown, because they are real tap
            # targets rather than just a hint about the arrow keys.
            if item.adjustable and (selected or touch):
                label = f"< {item.label} >"
            elif selected:
                label = f"> {label}"
            row_y = start_y + i * spacing
            text_mod.draw_text(surface, label, size, center_x, row_y, color, alpha=alpha)

            row = pygame.Rect(0, 0, self.ROW_WIDTH, max(44, spacing))
            row.center = (int(center_x), int(row_y))
            self.rects.append(row)

    # -- pointer ---------------------------------------------------------

    def row_at(self, x, y):
        for i, rect in enumerate(self.rects):
            if rect.collidepoint(x, y) and self.items[i].enabled:
                return i
        return None

    def handle_pointer(self, x, y, phase, audio=None):
        """Tap handling. ``phase`` is 'down' or 'up'; returns an action on tap.

        For an adjustable row the left and right thirds map to on_left/on_right,
        so the drawn ``<`` and ``>`` chevrons are honest touch targets.
        """
        if not self.rects:
            return None
        index = self.row_at(x, y)
        if phase == "down":
            if index is not None:
                if index != self.index and audio:
                    audio.play("menu_move")
                self.index = index
                self._pressed = index
            return None

        pressed, self._pressed = self._pressed, None
        if index is None or index != pressed:
            return None

        item = self.items[index]
        if item.adjustable:
            rect = self.rects[index]
            third = rect.width / 3.0
            if x < rect.left + third and item.on_left:
                item.on_left()
            elif x > rect.right - third and item.on_right:
                item.on_right()
            elif item.on_right:
                item.on_right()
            if audio:
                audio.play("menu_move")
            return None
        if audio:
            audio.play("menu_select")
        return item.action


def volume_bar(value):
    """Compact text meter for a 0-100 setting."""
    filled = int(round(value / 10.0))
    return "[" + "#" * filled + "-" * (10 - filled) + "]"
