"""The single random source for all gameplay.

Gameplay code must use this module rather than bare ``random.*`` calls, so a run
is reproducible from a seed. A test asserts the package contains no stray
``random.`` usage outside this file.
"""

import random as _random

rng = _random.Random()


def seed(value=None):
    """Reseed the shared generator. ``None`` reseeds from system entropy."""
    rng.seed(value)
    return value


def chance(probability):
    """True with the given probability (0..1)."""
    return rng.random() < probability


def uniform(lo, hi):
    return rng.uniform(lo, hi)


def randint(lo, hi):
    return rng.randint(lo, hi)


def choice(seq):
    return rng.choice(seq)


def shuffled(seq):
    items = list(seq)
    rng.shuffle(items)
    return items


def weighted_choice(items, weights):
    """Pick one item using the matching weight list. Falls back to uniform."""
    total = sum(weights)
    if total <= 0:
        return rng.choice(items)
    roll = rng.random() * total
    upto = 0.0
    for item, weight in zip(items, weights):
        upto += weight
        if roll <= upto:
            return item
    return items[-1]


def spread(center, amount):
    """A value within +/- amount of center."""
    return center + rng.uniform(-amount, amount)
