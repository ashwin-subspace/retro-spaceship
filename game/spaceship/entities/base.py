"""Shared entity base and collision helpers."""

import math

import pygame

from .. import config


class Entity:
    """Position, velocity and liveness. Subclasses add behaviour."""

    def __init__(self, x, y, vx=0.0, vy=0.0, radius=8.0):
        self.x = float(x)
        self.y = float(y)
        self.vx = float(vx)
        self.vy = float(vy)
        self.radius = float(radius)
        self.alive = True

    def integrate(self, dts):
        self.x += self.vx * dts
        self.y += self.vy * dts

    @property
    def pos(self):
        return (self.x, self.y)

    @property
    def rect(self):
        r = self.radius
        return pygame.Rect(int(self.x - r), int(self.y - r), int(r * 2), int(r * 2))

    def offscreen(self, margin=70.0):
        return (self.y > config.HEIGHT + margin or self.y < -margin * 4
                or self.x < -margin * 2 or self.x > config.WIDTH + margin * 2)

    def kill(self):
        self.alive = False

    def update(self, ctx):  # pragma: no cover - overridden
        pass

    def draw(self, surface):  # pragma: no cover - overridden
        pass


def circle_hit(ax, ay, ar, bx, by, br):
    """Circle-vs-circle overlap test."""
    dx, dy = ax - bx, ay - by
    limit = ar + br
    return (dx * dx + dy * dy) <= (limit * limit)


def distance(ax, ay, bx, by):
    return math.hypot(ax - bx, ay - by)


def clamp(value, lo, hi):
    return max(lo, min(hi, value))


def inflated(rect, amount):
    return rect.inflate(amount * 2, amount * 2)


def hurt_rect(entity):
    """An enemy's hurtbox: deliberately more generous than its sprite."""
    return inflated(entity.rect, config.ENEMY_HURTBOX_INFLATE)
