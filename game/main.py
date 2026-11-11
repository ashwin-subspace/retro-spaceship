"""Async entry point -- required shape for pygbag (WebAssembly) builds.

Also runs natively:  py main.py

Keeping this runnable on the desktop is deliberate. It means the async path is
exercised outside the browser, which is what stops the codebase quietly forking
into separate desktop and web variants.
"""

import asyncio
import os
import sys

# pygbag scans this file's top-level imports to decide which wheels to bundle,
# so numpy must be named here even though this module doesn't use it directly.
import numpy  # noqa: F401

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from spaceship.app import Game


async def main():
    game = Game()
    if game.audio_note:
        print(f"[audio] {game.audio_note}")
    await game.run_async()


# Note: deliberately not routed through spaceship.app.main(), which ends in
# sys.exit() -- that would raise SystemExit through the browser event loop.
asyncio.run(main())
