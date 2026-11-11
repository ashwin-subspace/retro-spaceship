"""In-game HUD.

Laid out around the ship's dodge lane: status in the top corners, the overdrive
meter pinned to the very bottom edge, nothing in the middle where the player is
actually flying. Everything is drawn dim so it never competes with bullets for
attention.
"""

import math

import pygame

from . import config, platform_api, theme
from .ui import text as text_mod

DIM = 165


def draw(surface, world, director, high_score, t):
    player = world.player
    _draw_health(surface, player)
    _draw_bombs(surface, player)
    _draw_score(surface, world, high_score)
    _draw_weapon(surface, player)
    _draw_overdrive(surface, player, t)
    _draw_graze(surface, player)
    _draw_banner(surface, director, t)
    if world.boss is not None and world.boss.state != "WARNING":
        _draw_boss_bar(surface, world.boss)
    if world.boss is not None and world.boss.state == "WARNING":
        _draw_boss_warning(surface, t)


def _draw_health(surface, player):
    x, y = 16, 16
    for i in range(player.max_health):
        filled = i < player.health
        rect = pygame.Rect(x + i * 16, y, 12, 12)
        if filled:
            pygame.draw.rect(surface, theme.color("GREEN"), rect)
        pygame.draw.rect(surface, theme.color("WHITE"), rect, 1)
    if player.shield:
        pygame.draw.circle(surface, theme.color("CYAN"),
                           (x + player.max_health * 16 + 8, y + 6), 6, 2)


def _draw_bombs(surface, player):
    x, y = 16, 34
    for i in range(player.bombs):
        pygame.draw.circle(surface, theme.color(config.COLOR_PICKUP_BOMB),
                           (x + 6 + i * 15, y + 6), 5)
        pygame.draw.circle(surface, theme.color("WHITE"), (x + 6 + i * 15, y + 6), 5, 1)


def _draw_score(surface, world, high_score):
    text_mod.draw_text_right(surface, f"{world.score:07d}", 22,
                             config.WIDTH - 14, 12, theme.color(config.COLOR_UI_ACCENT))
    text_mod.draw_text_right(surface, f"HI {max(high_score, world.score):07d}", 12,
                             config.WIDTH - 14, 36, theme.color("WHITE"), alpha=DIM)
    if world.combo >= 5:
        text_mod.draw_text_right(surface, f"x{world.combo_multiplier}  {world.combo} CHAIN", 14,
                                 config.WIDTH - 14, 52, theme.color("YELLOW"))


def _draw_weapon(surface, player):
    y = config.HEIGHT - 34
    text_mod.draw_text(surface, player.weapon, 14, 16, y, theme.color("WHITE"),
                       center=False, alpha=DIM)
    for i in range(config.WEAPON_MAX_LEVEL):
        rect = pygame.Rect(16 + i * 9, y + 16, 6, 5)
        if i < player.weapon_level:
            pygame.draw.rect(surface, theme.color(config.COLOR_PICKUP_WEAPON), rect)
        else:
            pygame.draw.rect(surface, theme.color("WHITE"), rect, 1)


def _draw_overdrive(surface, player, t):
    height = 4
    y = config.HEIGHT - height
    fraction = player.overdrive / config.OVERDRIVE_MAX
    if player.overdrive_active:
        pulse = 0.6 + 0.4 * math.sin(t / 90.0)
        pygame.draw.rect(surface, theme.shade(theme.color("CYAN"), pulse),
                         (0, y, config.WIDTH, height))
        text_mod.draw_text(surface, "OVERDRIVE", 12, config.WIDTH * 0.5, y - 10,
                           theme.color("CYAN"))
        return
    pygame.draw.rect(surface, theme.shade(theme.color("WHITE"), 0.18),
                     (0, y, config.WIDTH, height))
    pygame.draw.rect(surface, theme.color("CYAN"),
                     (0, y, int(config.WIDTH * fraction), height))
    if player.overdrive_ready:
        pulse = 0.5 + 0.5 * math.sin(t / 110.0)
        label = ("OVERDRIVE READY" if platform_api.INPUT_MODE == "touch"
                 else "OVERDRIVE READY  [SPACE]")
        text_mod.draw_text(surface, label, 12, config.WIDTH * 0.5,
                           y - 10, theme.shade(theme.color("CYAN"), pulse))


def _draw_graze(surface, player):
    """Graze count floats by the ship, so eyes stay on the hitbox."""
    if player.graze_streak >= 3:
        text_mod.draw_text(surface, f"GRAZE {player.graze_streak}", 12,
                           player.x, player.y + 28, theme.color("CYAN"), alpha=200)


def _draw_banner(surface, director, t):
    if not director.banner:
        return
    remaining = director.banner_until - t
    alpha = 255 if remaining > 500 else max(0, int(255 * remaining / 500.0))
    color = theme.color("RED") if director.banner == "WARNING" else theme.color("YELLOW")
    text_mod.draw_text(surface, director.banner, 24, config.WIDTH * 0.5,
                       config.HEIGHT * 0.30, color, alpha=alpha)


def _draw_boss_warning(surface, t):
    if int(t // 240) % 2 == 0:
        text_mod.draw_text(surface, "!! WARNING !!", 30, config.WIDTH * 0.5,
                           config.HEIGHT * 0.44, theme.color("RED"))
    text_mod.draw_text(surface, "INCOMING", 16, config.WIDTH * 0.5,
                       config.HEIGHT * 0.44 + 30, theme.color("WHITE"), alpha=DIM)


def _draw_boss_bar(surface, boss):
    margin = 40
    width = config.WIDTH - margin * 2
    x, y, height = margin, 68, 9
    pygame.draw.rect(surface, theme.color("BLACK"), (x - 2, y - 2, width + 4, height + 4))
    pygame.draw.rect(surface, theme.color("WHITE"), (x - 2, y - 2, width + 4, height + 4), 1)
    fill = int(width * boss.hp_fraction)
    color = theme.color("RED") if boss.hp_fraction > 0.33 else theme.color("YELLOW")
    pygame.draw.rect(surface, color, (x, y, fill, height))
    # Phase ticks make the fight's structure legible at a glance.
    for i in range(1, boss.phase_count):
        tick = x + int(width * (1.0 - i / boss.phase_count))
        pygame.draw.line(surface, theme.color("BLACK"), (tick, y), (tick, y + height), 2)
    text_mod.draw_text(surface, boss.name, 13, config.WIDTH * 0.5, y - 10,
                       theme.color("WHITE"), alpha=200)
    if boss.arms and not boss.core_vulnerable:
        text_mod.draw_text(surface, "DESTROY ARMS", 11, config.WIDTH * 0.5, y + height + 10,
                           theme.color("YELLOW"), alpha=200)


def apply_low_health_vignette(world, fx):
    """Request a red edge pulse when the player is nearly out.

    This only queues an effect on the bus (it draws nothing itself), so it is
    called from the update step rather than the draw step.
    """
    player = world.player
    if not player.alive:
        return
    if player.health <= 1:
        fx.vignette(theme.color("RED"), 260.0, 70)
    elif player.health <= player.max_health * 0.4:
        fx.vignette(theme.color("RED"), 260.0, 34)
