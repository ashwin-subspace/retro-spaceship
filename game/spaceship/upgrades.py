"""Post-boss upgrade cards.

Three are offered after each boss. Picking one is what gives runs different
shapes -- a graze build plays very differently from a bomb-and-tank build.
"""

from . import config, rng


class Upgrade:
    def __init__(self, key, title, description, apply_fn, repeatable=True):
        self.key = key
        self.title = title
        self.description = description
        self.apply_fn = apply_fn
        self.repeatable = repeatable

    def apply(self, world):
        self.apply_fn(world)


def _add_max_health(world):
    world.player.max_health += 1
    world.player.health = min(world.player.max_health, world.player.health + 1)


def _add_bomb(world):
    world.player.max_bombs += 1
    world.player.bombs = min(world.player.max_bombs, world.player.bombs + 1)


def _faster_shield(world):
    # Shrink the recharge delay by 25% of the base each time.
    world.player.shield_recharge_scale = getattr(world.player, "shield_recharge_scale", 1.0) * 0.75
    world.player.shield = True
    world.player.shield_ready_at = 0.0


def _wider_graze(world):
    world.player.graze_radius += 8.0


def _weapon_level(world):
    world.player.weapon_level = min(config.WEAPON_MAX_LEVEL, world.player.weapon_level + 1)


def _longer_overdrive(world):
    world.player.overdrive_bonus_ms = getattr(world.player, "overdrive_bonus_ms", 0.0) + 2000.0


def _score_bonus(world):
    world.player.score_bonus += 0.10


def _heal_full(world):
    world.player.health = world.player.max_health
    world.player.shield = True
    world.player.shield_ready_at = 0.0


POOL = [
    Upgrade("HULL", "+1 MAX HP", "Reinforce the hull.", _add_max_health),
    Upgrade("BOMB", "+1 MAX BOMB", "Carry another bomb.", _add_bomb),
    Upgrade("SHIELD", "FAST SHIELD", "Shield recharges 25% sooner.", _faster_shield),
    Upgrade("GRAZE", "WIDE GRAZE", "+8px graze radius.", _wider_graze),
    Upgrade("WEAPON", "+1 WEAPON LV", "Upgrade the current weapon.", _weapon_level),
    Upgrade("OVERDRIVE", "LONG OVERDRIVE", "Overdrive lasts 2s longer.", _longer_overdrive),
    Upgrade("SCORE", "+10% SCORE", "All score gains increased.", _score_bonus),
    Upgrade("REPAIR", "FULL REPAIR", "Restore all HP and shield.", _heal_full),
]


def draw_three():
    """Offer three distinct cards."""
    return rng.shuffled(POOL)[:3]
