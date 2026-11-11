"""Drops: weapon upgrades, bombs, repairs, and score motes from cleared bullets."""

import math

import pygame

from .. import config, rng, theme
from .base import Entity

WEAPON_KINDS = ("SPREAD", "LASER", "HOMING")


class Pickup(Entity):
    def __init__(self, x, y, kind, vx=0.0, vy=95.0, value=0):
        super().__init__(x, y, vx, vy, radius=8.0)
        self.kind = kind
        self.value = value
        self.age = 0.0

    @property
    def is_weapon(self):
        return self.kind in WEAPON_KINDS

    def update(self, ctx):
        self.age += ctx.clock.dt
        dts = ctx.clock.dts
        if self.kind == "SCORE" and ctx.player is not None:
            # Score motes home in, so a bomb's payout always lands.
            dx, dy = ctx.player.x - self.x, ctx.player.y - self.y
            distance = math.hypot(dx, dy) or 1.0
            pull = 420.0
            self.vx += dx / distance * pull * dts
            self.vy += dy / distance * pull * dts
            self.vx *= 0.92
            self.vy *= 0.92
        self.integrate(dts)
        if self.y > config.HEIGHT + 30:
            self.kill()

    def apply(self, player, ctx):
        if self.is_weapon:
            player.give_weapon(self.kind, ctx)
        elif self.kind == "BOMB":
            player.give_bomb(ctx)
        elif self.kind == "HEAL":
            player.heal(1)
            ctx.audio.play("shield_recharge")
            ctx.fx.popup(player.x, player.y - 40, "+HP", theme.color("GREEN"), 15)
        ctx.fx.burst(self.x, self.y, theme.color(config.COLOR_PICKUP_WEAPON), 10)

    def draw(self, surface):
        x, y = int(self.x), int(self.y)
        pulse = 1.0 + 0.12 * math.sin(self.age / 130.0)
        radius = int(self.radius * pulse)
        if self.kind == "SCORE":
            pygame.draw.circle(surface, theme.color("YELLOW"), (x, y), 3)
            return
        if self.kind == "BOMB":
            color = theme.color(config.COLOR_PICKUP_BOMB)
            pygame.draw.circle(surface, color, (x, y), radius)
            pygame.draw.circle(surface, theme.color("WHITE"), (x, y), radius, 1)
            pygame.draw.line(surface, theme.color("BLACK"), (x - 3, y), (x + 3, y), 2)
            pygame.draw.line(surface, theme.color("BLACK"), (x, y - 3), (x, y + 3), 2)
            return
        if self.kind == "HEAL":
            color = theme.color("GREEN")
            pygame.draw.circle(surface, color, (x, y), radius)
            pygame.draw.line(surface, theme.color("BLACK"), (x - 4, y), (x + 4, y), 2)
            pygame.draw.line(surface, theme.color("BLACK"), (x, y - 4), (x, y + 4), 2)
            return

        color = theme.color(config.COLOR_PICKUP_WEAPON)
        pygame.draw.circle(surface, color, (x, y), radius)
        pygame.draw.circle(surface, theme.color("WHITE"), (x, y), radius, 1)
        letter = {"SPREAD": "S", "LASER": "L", "HOMING": "H"}[self.kind]
        from ..ui import text as text_mod
        text_mod.draw_text(surface, letter, 12, x, y, theme.color("BLACK"))


def roll_drop(x, y, low_health=False):
    """Decide what a dead enemy leaves behind, if anything."""
    chance = config.PICKUP_CHANCE * (config.LOW_HP_PICKUP_BOOST if low_health else 1.0)
    if not rng.chance(chance):
        return None
    roll = rng.rng.random()
    if low_health and roll < 0.28:
        return Pickup(x, y, "HEAL")
    if roll < 0.16:
        return Pickup(x, y, "BOMB")
    if roll < 0.24:
        return Pickup(x, y, "HEAL")
    return Pickup(x, y, rng.choice(WEAPON_KINDS))
