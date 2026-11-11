"""Canvas geometry and the single pointer-to-game-coordinate funnel.

Every pointer coordinate in the game passes through ``to_game``. Keeping one
funnel means that if the browser reports coordinates in an unexpected space
(CSS pixels vs canvas pixels, devicePixelRatio scaling), the correction lands in
exactly one function instead of being chased through the input code.

On desktop this is the identity transform.
"""

from . import config, platform_api

PLAY_W = config.WIDTH
PLAY_H = config.HEIGHT


class Viewport:
    def __init__(self, gutter_height=0):
        self.gutter = gutter_height

    @property
    def screen_size(self):
        return (PLAY_W, PLAY_H + self.gutter)

    @property
    def gutter_rect(self):
        import pygame
        return pygame.Rect(0, PLAY_H, PLAY_W, self.gutter)

    def to_game(self, px, py):
        """Map a pointer position to play-field coords.

        Returns None when the point is in the control gutter rather than the
        play field, so callers can route taps to buttons instead of the ship.
        """
        if py >= PLAY_H:
            return None
        return (px, py)

    def in_gutter(self, px, py):
        return self.gutter > 0 and PLAY_H <= py < PLAY_H + self.gutter

    def from_normalised(self, nx, ny):
        """SDL FINGER* events report 0..1, not pixels."""
        width, height = self.screen_size
        return (nx * width, ny * height)


def create():
    return Viewport(platform_api.gutter_height())
