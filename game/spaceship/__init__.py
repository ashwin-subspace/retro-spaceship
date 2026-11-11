"""Retro Spaceship -- a procedural arcade shooter built on pygame.

Modules are strictly layered; an import that points "up" this list is a bug:

    config, theme, rng, clock          (no internal imports)
    save, audio, fx, patterns, formations, ui.text
    entities.*
    world, director, hud
    ui.menus, ui.screens, scenes
    app

Entities never import ``world``/``fx``/``audio`` directly -- they receive a
per-frame context object instead (see ``world.Ctx``).
"""

__version__ = "2.0"
