"""Parallax starfield.

Three depth layers moving at different speeds gives the illusion of travel that
a single flat layer never does, and it costs almost nothing.
"""

import pygame

from .. import config, rng, theme

LAYERS = (
    # (count, speed px/sec, size, brightness)
    (34, 26.0, 1, 0.34),
    (26, 62.0, 1, 0.62),
    (16, 118.0, 2, 1.0),
)


class StarField:
    def __init__(self):
        self.layers = []
        for count, speed, size, brightness in LAYERS:
            stars = [[rng.uniform(0, config.WIDTH), rng.uniform(0, config.HEIGHT)]
                     for _ in range(count)]
            self.layers.append({"stars": stars, "speed": speed, "size": size,
                                "brightness": brightness})

    def update(self, dt_ms, boost=1.0):
        dts = dt_ms / 1000.0
        for layer in self.layers:
            speed = layer["speed"] * boost * dts
            for star in layer["stars"]:
                star[1] += speed
                if star[1] > config.HEIGHT:
                    star[1] = -2.0
                    star[0] = rng.uniform(0, config.WIDTH)

    def draw(self, surface):
        base = theme.color("WHITE")
        for layer in self.layers:
            color = theme.shade(base, layer["brightness"])
            size = layer["size"]
            for x, y in layer["stars"]:
                pygame.draw.rect(surface, color, (int(x), int(y), size, size))
