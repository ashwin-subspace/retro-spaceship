"""Headless harness for driving the game in tests.

Sets dummy SDL drivers, points the save system at a scratch file so tests can
never touch the player's real save, and exposes a fixed-step driver so a test
can simulate minutes of play in under a second.
"""

import os

os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
os.environ.setdefault("SDL_AUDIODRIVER", "dummy")

import shutil
import tempfile


class Harness:
    """Context manager owning a Game plus an isolated save file."""

    def __init__(self, seed=1234, copy_real_save=False):
        self.seed = seed
        self.copy_real_save = copy_real_save
        self.game = None
        self._dir = None
        self._saved = None

    def __enter__(self):
        from .. import save, rng

        self._dir = tempfile.mkdtemp(prefix="spaceship_test_")
        self._saved = (save.SAVE_PATH, save.SAVE_DIR)
        scratch = os.path.join(self._dir, "game_save.json")
        if self.copy_real_save and os.path.exists(save.SAVE_PATH):
            shutil.copyfile(save.SAVE_PATH, scratch)
        save.SAVE_PATH = scratch
        save.SAVE_DIR = self._dir
        rng.seed(self.seed)

        from ..app import Game
        self.game = Game()
        return self

    def __exit__(self, *exc):
        from .. import save
        save.SAVE_PATH, save.SAVE_DIR = self._saved
        try:
            shutil.rmtree(self._dir, ignore_errors=True)
        except Exception:
            pass
        return False

    # -- driving ---------------------------------------------------------

    def start_run(self, immortal=False):
        self.game.start_run()
        if immortal:
            self.make_immortal()
        return self.game

    def make_immortal(self):
        """The harness can't dodge; keep the ship alive to exercise pacing."""
        self.game.world.player.take_hit = lambda ctx, from_dir=None: False

    def step(self, frames=1, dt=1000.0 / 60.0):
        """Fixed-step simulation: deterministic and as fast as the CPU allows."""
        for _ in range(frames):
            self.game.update(dt)
            self.game.draw(0)

    def press(self, key):
        import pygame
        self.game.handle_event(pygame.event.Event(pygame.KEYDOWN, key=key))

    @property
    def world(self):
        return self.game.world

    @property
    def director(self):
        return self.game.director
