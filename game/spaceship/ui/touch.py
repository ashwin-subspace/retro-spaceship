"""On-screen controls for touch devices.

The buttons live in a gutter *below* the play field, never on top of it. That
is a hard constraint, not a style choice: the ship can reach every horizontal
position and the bottom 70% of the field, so an overlaid button would sit
exactly where the player needs to fly.

Layout follows how a phone is actually held -- the two urgent actions (bomb,
overdrive) sit under the dominant thumb on the right, focus goes far left, and
pause is small and central so it can't be hit by accident mid-boss.
"""

import math

import pygame

from .. import config, theme
from . import text as text_mod

MIN_TOUCH_PX = 80          # comfortably above the ~44px accessibility floor


class TouchButton:
    def __init__(self, action, label, rect, style="round"):
        self.action = action
        self.label = label
        self.rect = rect
        self.style = style
        self.pressed_until = 0.0

    def contains(self, x, y):
        return self.rect.collidepoint(x, y)

    def flash(self, now):
        self.pressed_until = now + 120.0


class TouchPad:
    """The gutter button strip. Inert when there is no gutter (desktop)."""

    def __init__(self, gutter_rect):
        self.rect = gutter_rect
        self.buttons = []
        if gutter_rect.height <= 0:
            return

        gy = gutter_rect.y
        gh = gutter_rect.height
        size = max(MIN_TOUCH_PX, min(112, int(gh * 0.52)))
        pad = 16
        cy = gy + gh // 2

        # Left thumb: focus (a toggle, since a hold is impossible on touch).
        self.buttons.append(TouchButton(
            "focus", "FOCUS",
            pygame.Rect(pad, cy - size // 2, size, size)))

        # Right thumb: the two actions you need in an emergency.
        right = config.WIDTH - pad - size
        self.buttons.append(TouchButton(
            "overdrive", "OD",
            pygame.Rect(right, cy - size // 2, size, size)))
        self.buttons.append(TouchButton(
            "bomb", "BOMB",
            pygame.Rect(right - size - pad, cy - size // 2, size, size)))

        # Centre, deliberately small: an accidental pause mid-boss is maddening.
        pause_size = max(44, size // 2)
        self.buttons.append(TouchButton(
            "pause", "II",
            pygame.Rect(config.WIDTH // 2 - pause_size // 2, gy + 10,
                        pause_size, pause_size),
            style="square"))

    @property
    def active(self):
        return bool(self.buttons)

    def hit(self, x, y):
        """Which action was tapped, if any."""
        for button in self.buttons:
            if button.contains(x, y):
                return button
        return None

    def draw(self, surface, player, now):
        if not self.buttons:
            return
        pygame.draw.rect(surface, theme.color("BLACK"), self.rect)
        pygame.draw.line(surface, theme.shade(theme.color("WHITE"), 0.25),
                         (0, self.rect.y), (config.WIDTH, self.rect.y), 1)

        for button in self.buttons:
            self._draw_button(surface, button, player, now)

    def _draw_button(self, surface, button, player, now):
        rect = button.rect
        held = now < button.pressed_until
        enabled, detail, fill = self._state_for(button, player)

        base = theme.color("CYAN") if enabled else theme.shade(theme.color("WHITE"), 0.30)
        if held:
            base = theme.color("WHITE")

        if button.style == "square":
            pygame.draw.rect(surface, base, rect, 2)
        else:
            centre = rect.center
            radius = rect.width // 2
            # A ring that fills up doubles as the overdrive meter, right where
            # the thumb already is.
            if fill is not None and fill > 0.0:
                pygame.draw.circle(surface, theme.shade(base, 0.35), centre, radius)
                self._draw_arc(surface, centre, radius - 3, fill, base)
            pygame.draw.circle(surface, base, centre, radius, 3)

        text_mod.draw_text(surface, button.label, 13, rect.centerx,
                           rect.centery - (6 if detail else 0), base)
        if detail:
            text_mod.draw_text(surface, detail, 12, rect.centerx, rect.centery + 12,
                               theme.color("WHITE"))

    @staticmethod
    def _draw_arc(surface, centre, radius, fraction, color):
        if radius <= 2:
            return
        start = -math.pi / 2
        end = start + math.tau * max(0.0, min(1.0, fraction))
        box = pygame.Rect(centre[0] - radius, centre[1] - radius, radius * 2, radius * 2)
        try:
            pygame.draw.arc(surface, color, box, start, end, 5)
        except ValueError:
            pass

    @staticmethod
    def _state_for(button, player):
        """(enabled, small label, ring fill) for a button's live state."""
        if player is None:
            return False, "", None
        if button.action == "bomb":
            return player.bombs > 0, f"x{player.bombs}", None
        if button.action == "overdrive":
            if player.overdrive_active:
                return True, "ON", 1.0
            fraction = player.overdrive / config.OVERDRIVE_MAX
            return player.overdrive_ready, f"{int(fraction * 100)}%", fraction
        if button.action == "focus":
            return player.focus, "ON" if player.focus else "OFF", None
        return True, "", None
