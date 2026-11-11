"""Full-screen views: title, settings, high scores, pause, results, upgrades."""

import math

import pygame

from .. import config, platform_api, theme
from . import text as text_mod


def _title(surface, label, y=None):
    text_mod.draw_text(surface, label, 30, config.WIDTH * 0.5,
                       y if y is not None else config.HEIGHT * 0.18, theme.color("GREEN"))


def draw_start(surface, menu, high_score, t, audio_note=None):
    pulse = 0.75 + 0.25 * math.sin(t / 420.0)
    text_mod.draw_text(surface, "RETRO", 46, config.WIDTH * 0.5, config.HEIGHT * 0.17,
                       theme.shade(theme.color("GREEN"), pulse))
    text_mod.draw_text(surface, "SPACESHIP", 46, config.WIDTH * 0.5, config.HEIGHT * 0.17 + 44,
                       theme.shade(theme.color("GREEN"), pulse))
    text_mod.draw_text(surface, f"HIGH SCORE  {high_score:07d}", 15, config.WIDTH * 0.5,
                       config.HEIGHT * 0.34, theme.color("YELLOW"))
    touch = platform_api.INPUT_MODE == "touch"
    menu.draw(surface, config.WIDTH * 0.5, config.HEIGHT * 0.52, touch=touch)
    if touch:
        line1, line2 = ("DRAG TO FLY - IT FIRES ITSELF",
                        "TAP FOCUS   BOMB   OVERDRIVE")
    else:
        line1, line2 = ("DRAG OR ARROWS TO FLY - IT FIRES ITSELF",
                        "SHIFT FOCUS   SPACE OVERDRIVE   B BOMB")
    text_mod.draw_text(surface, line1, 11, config.WIDTH * 0.5, config.HEIGHT - 54,
                       theme.color("WHITE"), alpha=150)
    text_mod.draw_text(surface, line2, 11, config.WIDTH * 0.5, config.HEIGHT - 38,
                       theme.color("WHITE"), alpha=150)
    if audio_note:
        text_mod.draw_text(surface, audio_note, 10, config.WIDTH * 0.5, config.HEIGHT - 18,
                           theme.color("ORANGE"), alpha=170)


def draw_settings(surface, menu):
    _title(surface, "SETTINGS")
    touch = platform_api.INPUT_MODE == "touch"
    menu.draw(surface, config.WIDTH * 0.5, config.HEIGHT * 0.36, spacing=36, touch=touch)
    hint = "TAP < > TO ADJUST" if touch else "LEFT/RIGHT ADJUST   ESC BACK"
    text_mod.draw_text(surface, hint, 11, config.WIDTH * 0.5,
                       config.HEIGHT - 40, theme.color("WHITE"), alpha=150)


def draw_high_scores(surface, entries, menu):
    _title(surface, "HIGH SCORES")
    y = config.HEIGHT * 0.30
    if not entries:
        text_mod.draw_text(surface, "NO RUNS YET", 16, config.WIDTH * 0.5, y,
                           theme.color("WHITE"), alpha=170)
    for i, entry in enumerate(entries):
        row_y = y + i * 30
        color = theme.color("YELLOW") if i == 0 else theme.color("WHITE")
        text_mod.draw_text(surface, f"{i + 1}", 16, 70, row_y, color, alpha=200)
        text_mod.draw_text(surface, f"{entry['score']:07d}", 18, config.WIDTH * 0.5 - 20,
                           row_y, color)
        detail = f"ST{entry['stage']}  {entry['weapon'][:1]}"
        text_mod.draw_text_right(surface, detail, 12, config.WIDTH - 60, row_y - 6,
                                 theme.color("WHITE"), alpha=150)
    menu.draw(surface, config.WIDTH * 0.5, config.HEIGHT - 90)


def draw_pause(surface, menu):
    overlay = pygame.Surface(surface.get_size(), pygame.SRCALPHA)
    overlay.fill((0, 0, 0, 150))
    surface.blit(overlay, (0, 0))
    text_mod.draw_text(surface, "PAUSED", 34, config.WIDTH * 0.5, config.HEIGHT * 0.32,
                       theme.color("YELLOW"))
    menu.draw(surface, config.WIDTH * 0.5, config.HEIGHT * 0.46)


def _fmt_time(ms):
    seconds = int(ms // 1000)
    return f"{seconds // 60}:{seconds % 60:02d}"


def draw_results(surface, world, rank, high_rank, menu, t):
    overlay = pygame.Surface(surface.get_size(), pygame.SRCALPHA)
    overlay.fill((0, 0, 0, 190))
    surface.blit(overlay, (0, 0))

    stats = world.stats
    text_mod.draw_text(surface, "GAME OVER", 38, config.WIDTH * 0.5, 70,
                       theme.color("MAGENTA"))
    if high_rank and int(t // 220) % 2 == 0:
        text_mod.draw_text(surface, f"NEW HIGH SCORE  #{high_rank}", 18, config.WIDTH * 0.5,
                           108, theme.color("YELLOW"))
    text_mod.draw_text(surface, f"{world.score:07d}", 34, config.WIDTH * 0.5, 146,
                       theme.color("CYAN"))

    text_mod.draw_text(surface, "RANK", 14, config.WIDTH * 0.5 - 74, 186,
                       theme.color("WHITE"), alpha=180)
    text_mod.draw_text(surface, rank, 44, config.WIDTH * 0.5 + 10, 190,
                       theme.color("YELLOW"))

    rows = [
        ("STAGE", f"{stats.stage}-{stats.wave}"),
        ("TIME", _fmt_time(stats.time_ms)),
        ("KILLS", str(stats.kills)),
        ("ACCURACY", f"{stats.accuracy * 100:.0f}%"),
        ("BEST CHAIN", str(stats.best_combo)),
        ("GRAZE", str(stats.graze_total)),
        ("PERFECT WAVES", str(stats.perfect_waves)),
        ("BOSSES", str(stats.bosses_killed)),
        ("HITS TAKEN", str(stats.hits_taken)),
    ]
    y = 236
    for label, value in rows:
        text_mod.draw_text(surface, label, 13, 84, y, theme.color("WHITE"),
                           center=False, alpha=165)
        text_mod.draw_text_right(surface, value, 13, config.WIDTH - 84, y,
                                 theme.color("CYAN"))
        y += 21

    menu.draw(surface, config.WIDTH * 0.5, config.HEIGHT - 74, spacing=28, size=17)


def draw_upgrade(surface, cards, index, t):
    """Draws the three cards and returns their rects for hit-testing."""
    overlay = pygame.Surface(surface.get_size(), pygame.SRCALPHA)
    overlay.fill((0, 0, 0, 175))
    surface.blit(overlay, (0, 0))

    text_mod.draw_text(surface, "STAGE CLEAR", 26, config.WIDTH * 0.5, 92,
                       theme.color("YELLOW"))
    text_mod.draw_text(surface, "CHOOSE AN UPGRADE", 15, config.WIDTH * 0.5, 124,
                       theme.color("WHITE"), alpha=190)

    card_w, card_h = config.WIDTH - 110, 84
    top = 168
    rects = []
    for i, card in enumerate(cards):
        y = top + i * (card_h + 16)
        rect = pygame.Rect((config.WIDTH - card_w) // 2, y, card_w, card_h)
        rects.append(rect)
        selected = i == index
        if selected:
            pulse = 0.6 + 0.4 * math.sin(t / 160.0)
            pygame.draw.rect(surface, theme.shade(theme.color("CYAN"), pulse * 0.35), rect)
            pygame.draw.rect(surface, theme.color("CYAN"), rect, 3)
        else:
            pygame.draw.rect(surface, theme.color("WHITE"), rect, 1)
        text_mod.draw_text(surface, card.title, 19, rect.centerx, rect.y + 30,
                           theme.color("CYAN") if selected else theme.color("WHITE"))
        text_mod.draw_text(surface, card.description, 12, rect.centerx, rect.y + 56,
                           theme.color("WHITE"), alpha=180)

    hint = ("TAP TO CHOOSE - TAP AGAIN TO CONFIRM"
            if platform_api.INPUT_MODE == "touch" else "UP/DOWN CHOOSE   ENTER CONFIRM")
    text_mod.draw_text(surface, hint, 11, config.WIDTH * 0.5,
                       config.HEIGHT - 40, theme.color("WHITE"), alpha=150)
    return rects
