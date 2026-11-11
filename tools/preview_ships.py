"""Render the layered sprite treatment offline, before building an APK.

The risk in this change is entirely visual, and an APK build plus an emulator
install is a slow way to look at a colour. This composites exactly what
``Draw.detailSprite`` will composite -- same layer order, same ``shade``/``mix``
formulas as ``Theme.java``, same multiply tint as the Android colour filter --
so the constants at the top of this file can be tuned here and then copied into
the Java with confidence.

Two sheets come out:

* ``_detail_before_after.png`` -- every ship, old flat tint beside the new layers,
  at the size they actually occupy on the phone.
* ``_detail_themes.png`` -- a green, a magenta and an orange ship across all four
  themes, which is where the two real risks show up: whether normal and tough
  enemies stay distinguishable, and whether the near-monochrome Amber theme
  survives having a near-white accent laid over it.

Usage:  py tools/preview_ships.py
"""

import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GFX = os.path.join(ROOT, "android", "assets", "gfx")
OUT = os.path.join(ROOT, "assets_src")

# ---------- tunables: keep these in step with Draw.detailSprite ----------

# Contrast comes from darkening the hull, NOT from whitening the panels. Pushing
# the lit facets far toward white washed the orange "tough" enemies out to cream
# and broke the threat-colour read, because on several of those sprites the lit
# region is 70-95% of the whole ship.
HULL_SHADE = 0.55      # base silhouette, deepened so the panels read as lit
LIT_MIX = 0.22         # how far the lit facets travel toward white
LINE_ALPHA = 185       # linework strength
HALO_SHADE = 0.85
HALO_ALPHA = 60
BLOOM = 1.45

# ---------- Theme.java, mirrored ----------

BLACK, GREEN, CYAN, MAGENTA, YELLOW, ORANGE, RED, WHITE = range(8)

THEMES = {
    "PHOSPHOR": [(5, 5, 10), (57, 255, 20), (0, 255, 255), (255, 0, 200),
                 (255, 255, 0), (255, 140, 0), (255, 40, 40), (230, 230, 230)],
    "CYBERPUNK": [(8, 4, 18), (190, 60, 255), (0, 230, 255), (255, 20, 180),
                  (255, 225, 60), (255, 90, 180), (255, 40, 90), (235, 225, 255)],
    "AMBER": [(10, 6, 0), (255, 176, 0), (255, 210, 120), (255, 90, 0),
              (255, 220, 90), (255, 140, 0), (255, 60, 30), (255, 235, 200)],
    "ICE": [(4, 8, 20), (120, 220, 255), (0, 255, 255), (120, 140, 255),
            (210, 240, 255), (90, 180, 255), (255, 70, 90), (235, 245, 255)],
}
NEBULA = {"PHOSPHOR": (10, 40, 22), "CYBERPUNK": (40, 10, 58),
          "AMBER": (44, 24, 0), "ICE": (8, 26, 60)}


def shade(color, factor):
    return tuple(max(0, min(255, round(c * factor))) for c in color)


def mix(a, b, k):
    return tuple(max(0, min(255, round(x + (y - x) * k))) for x, y in zip(a, b))


# ---------- the sprites, with the colour role and world height the game uses ----------

SHIPS = [
    ("player",      GREEN,  30),
    ("e_drone",     MAGENTA, 24),
    ("e_weaver",    MAGENTA, 24),
    ("e_darter",    MAGENTA, 28),
    ("e_seeker",    MAGENTA, 22),
    ("e_splitter",  MAGENTA, 28),
    ("e_minelayer", ORANGE, 24),
    ("e_tank",      ORANGE, 32),
    ("e_shielder",  ORANGE, 26),
    ("e_turret",    ORANGE, 30),
]

# A 1080-wide phone renders 480 world units across, so a world unit is 2.25
# device pixels. Previewing at the world number alone would flatter the result.
DEVICE_SCALE = 2.25
SMALL_SCALE = 1.5


def load(name):
    path = os.path.join(GFX, name + ".png")
    return Image.open(path).convert("RGBA") if os.path.exists(path) else None


def tinted(sprite, color, alpha, height):
    """A multiply tint plus a layer alpha -- what PorterDuff.MULTIPLY does."""
    if sprite is None:
        return None
    scale = height / sprite.height
    size = (max(1, round(sprite.width * scale)), max(1, round(sprite.height * scale)))
    small = sprite.resize(size, Image.LANCZOS)
    arr = np.asarray(small).astype(np.float32)
    arr[:, :, 0] *= color[0] / 255.0
    arr[:, :, 1] *= color[1] / 255.0
    arr[:, :, 2] *= color[2] / 255.0
    arr[:, :, 3] *= alpha / 255.0
    return Image.fromarray(arr.clip(0, 255).astype(np.uint8))


def paste_centre(canvas, layer, cx, cy):
    if layer is None:
        return
    canvas.alpha_composite(layer, (int(cx - layer.width / 2), int(cy - layer.height / 2)))


def draw_flat(canvas, name, palette, role, height, cx, cy):
    """The current renderer: one halo plus one flat multiply tint."""
    base = load(name)
    color = palette[role]
    paste_centre(canvas, tinted(base, shade(color, HALO_SHADE), HALO_ALPHA, height * BLOOM), cx, cy)
    paste_centre(canvas, tinted(base, color, 255, height), cx, cy)


def draw_detail(canvas, name, palette, role, height, cx, cy, accent_role=WHITE):
    """The layered treatment: halo, deepened hull, lit facets, linework."""
    base, lit, line = load(name), load(name + "_lit"), load(name + "_line")
    color = palette[role]
    if lit is None or line is None:
        draw_flat(canvas, name, palette, role, height, cx, cy)
        return
    paste_centre(canvas, tinted(base, shade(color, HALO_SHADE), HALO_ALPHA, height * BLOOM), cx, cy)
    paste_centre(canvas, tinted(base, shade(color, HULL_SHADE), 255, height), cx, cy)
    paste_centre(canvas, tinted(lit, mix(color, palette[WHITE], LIT_MIX), 255, height), cx, cy)
    paste_centre(canvas, tinted(line, palette[accent_role], LINE_ALPHA, height), cx, cy)


def background(width, height, theme):
    canvas = Image.new("RGBA", (width, height), THEMES[theme][BLACK] + (255,))
    wash = Image.new("RGBA", (width, height), NEBULA[theme] + (40,))
    canvas.alpha_composite(wash)
    return canvas


def label(canvas, text, x, y, color=(150, 210, 255, 255)):
    ImageDraw.Draw(canvas).text((x, y), text, fill=color)


def sheet_before_after():
    theme = "PHOSPHOR"
    palette = THEMES[theme]
    cols = [("flat @phone", DEVICE_SCALE, draw_flat),
            ("detail @phone", DEVICE_SCALE, draw_detail),
            ("detail @720p", SMALL_SCALE, draw_detail),
            ("detail large", None, draw_detail)]
    cell_w, cell_h = 300, 200
    canvas = background(cell_w * len(cols), cell_h * len(SHIPS) + 24, theme)
    label(canvas, f"{theme}   hull={HULL_SHADE}  lit_mix={LIT_MIX}  line_a={LINE_ALPHA}", 8, 6)
    for row, (name, role, world_h) in enumerate(SHIPS):
        y = 24 + row * cell_h
        label(canvas, name, 8, y + 4, (120, 230, 140, 255))
        for col, (title, scale, fn) in enumerate(cols):
            x = col * cell_w
            if row == 0:
                label(canvas, title, x + 150, y + 4, (240, 220, 120, 255))
            height = 140 if scale is None else world_h * scale
            fn(canvas, name, palette, role, height, x + 150, y + cell_h / 2 + 10)
    out = os.path.join(OUT, "_detail_before_after.png")
    canvas.convert("RGB").save(out)
    print("wrote", out, canvas.size)


def sheet_themes():
    picks = [("player", GREEN, 30), ("e_drone", MAGENTA, 24), ("e_tank", ORANGE, 32)]
    names = list(THEMES)
    cell_w, cell_h = 210, 150
    canvas = Image.new("RGBA", (cell_w * len(names), cell_h * len(picks) + 24), (0, 0, 0, 255))
    for col, theme in enumerate(names):
        strip = background(cell_w, cell_h * len(picks) + 24, theme)
        canvas.alpha_composite(strip, (col * cell_w, 0))
        label(canvas, theme, col * cell_w + 8, 6)
        for row, (name, role, world_h) in enumerate(picks):
            y = 24 + row * cell_h
            draw_detail(canvas, name, THEMES[theme], role, world_h * DEVICE_SCALE,
                        col * cell_w + 70, y + cell_h / 2)
            draw_detail(canvas, name, THEMES[theme], role, world_h * SMALL_SCALE,
                        col * cell_w + 165, y + cell_h / 2)
    out = os.path.join(OUT, "_detail_themes.png")
    canvas.convert("RGB").save(out)
    print("wrote", out, canvas.size)


if __name__ == "__main__":
    sheet_before_after()
    sheet_themes()
