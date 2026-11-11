"""Build the launcher icon from the game's own player sprite.

Drawing a separate icon would let it drift away from what the game looks like.
This composites the actual ship bitmap, tinted with the default phosphor theme,
over the same dark ground and starfield the game opens on -- so the icon is a
screenshot of the game in miniature rather than an illustration of it.
"""

import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHIP = os.path.join(ROOT, "android", "assets", "gfx", "player.png")
RES = os.path.join(ROOT, "android", "res")

# Phosphor theme: the palette the game starts on.
BACKGROUND = (5, 5, 10)
GREEN = (57, 255, 20)
CYAN = (0, 255, 255)

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

MASTER = 512


def tint(image, color):
    """Multiply a white sprite by a colour, preserving its shading and alpha."""
    r, g, b, a = image.split()
    solid = Image.new("RGB", image.size, color)
    shaded = Image.composite(solid, Image.new("RGB", image.size, (0, 0, 0)),
                             r.point(lambda v: v))
    out = shaded.convert("RGBA")
    out.putalpha(a)
    return out


def build_master():
    size = MASTER
    icon = Image.new("RGBA", (size, size), BACKGROUND + (255,))
    draw = ImageDraw.Draw(icon)

    # A vertical wash, matching the nebula band the starfield paints.
    for i in range(size):
        k = 1.0 - i / size
        draw.line([(0, i), (size, i)],
                  fill=(int(10 * k) + 5, int(40 * k) + 5, int(22 * k) + 10, 255))

    rng = random.Random(7)
    for _ in range(90):
        x, y = rng.uniform(0, size), rng.uniform(0, size)
        r = rng.choice([1, 1, 2, 3])
        v = rng.randint(90, 235)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=(v, v, v, 255))

    # An engine glow under the ship, blurred so it reads as light not as a shape.
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        [size * 0.30, size * 0.60, size * 0.70, size * 0.98],
        fill=CYAN + (110,))
    icon.alpha_composite(glow.filter(ImageFilter.GaussianBlur(size * 0.07)))

    ship = Image.open(SHIP).convert("RGBA")
    target = int(size * 0.62)
    scale = target / max(ship.width, ship.height)
    ship = ship.resize((max(1, round(ship.width * scale)),
                        max(1, round(ship.height * scale))), Image.LANCZOS)
    ship = tint(ship, GREEN)

    bloom = ship.resize((int(ship.width * 1.5), int(ship.height * 1.5)), Image.LANCZOS)
    bloom.putalpha(bloom.getchannel("A").point(lambda v: int(v * 0.35)))
    bloom = bloom.filter(ImageFilter.GaussianBlur(size * 0.02))
    icon.alpha_composite(bloom, ((size - bloom.width) // 2,
                                 int(size * 0.46) - bloom.height // 2))
    icon.alpha_composite(ship, ((size - ship.width) // 2,
                                int(size * 0.46) - ship.height // 2))
    return icon


def rounded(image, radius_fraction=0.22):
    """Round the corners, so the icon looks deliberate on launchers that do not mask."""
    size = image.width
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_fraction), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(image, (0, 0), mask)
    return out


def main():
    master = rounded(build_master())
    for folder, size in DENSITIES.items():
        target = os.path.join(RES, folder)
        os.makedirs(target, exist_ok=True)
        master.resize((size, size), Image.LANCZOS).save(
            os.path.join(target, "ic_launcher.png"), optimize=True)
    preview = os.path.join(ROOT, "assets_src", "_icon_preview.png")
    master.resize((192, 192), Image.LANCZOS).save(preview)
    print("icons written for", ", ".join(sorted(DENSITIES)), "| preview:", preview)


if __name__ == "__main__":
    main()
