"""Retro Spaceship -- a procedural arcade shooter.

Run it with:  py retro_spaceship_game.py

Controls
    Drag with the mouse, or arrows / WASD   fly (the ship fires by itself)
    Shift                                    focus mode: slow, precise, shows the hitbox
    Space                                    overdrive, once the meter is full
    B or right mouse                         bomb: clears the screen
    Esc                                      pause / back
    Up-Down + Enter                          menus

Difficulty is not a setting. It rises automatically as you survive, then starts
rotating randomised wave flavours and bosses so it keeps changing shape.

The game itself lives in the ``spaceship`` package; this file is just the entry
point so the save file and launch command stay where they always were.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from spaceship.app import main

if __name__ == "__main__":
    main()
