"""Turn the downloaded CC0 packs into the exact asset set the APK ships.

Sprites are trimmed to their alpha bounding box and normalised to a known
height, so the Java side can size everything in world units without carrying a
per-sprite fudge factor. They stay white/greyscale on purpose: the game tints
each one at load time for whichever of the four themes is active, which is what
lets one art set serve all of them.
"""

import os
import shutil

import numpy as np
from PIL import Image

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets_src")
DST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "android", "assets")

SIMPLE = os.path.join(SRC, "simple-space", "PNG", "Retina")
UFO = os.path.join(SRC, "alien-ufo-pack", "PNG")
EXT = os.path.join(SRC, "space-shooter-extension", "PNG", "Sprites X2", "Ships")
PLANETS = os.path.join(SRC, "planets", "Planets")
SCIFI = os.path.join(SRC, "sci-fi-sounds", "Audio")
IFACE = os.path.join(SRC, "interface-sounds", "Audio")
FONTS = os.path.join(SRC, "kenney-fonts", "Fonts")

# (out name, source file, rotate degrees, target long edge in px)
SPRITES = [
    # -- the player --
    ("player",        f"{SIMPLE}/ship_J.png",        0,   96),
    ("player_wing",   f"{SIMPLE}/ship_L.png",        0,   96),
    # -- enemy archetypes, rotated to face down the screen --
    ("e_drone",       f"{SIMPLE}/ship_A.png",        180, 72),
    ("e_weaver",      f"{SIMPLE}/enemy_B.png",       180, 72),
    ("e_darter",      f"{SIMPLE}/ship_B.png",        180, 72),
    ("e_seeker",      f"{SIMPLE}/enemy_C.png",       180, 72),
    ("e_splitter",    f"{SIMPLE}/ship_sidesD.png",   180, 80),
    ("e_minelayer",   f"{SIMPLE}/satellite_A.png",   180, 80),
    ("e_tank",        f"{SIMPLE}/enemy_D.png",       180, 96),
    ("e_shielder",    f"{SIMPLE}/enemy_A.png",       180, 80),
    ("e_turret",      f"{SIMPLE}/station_A.png",     0,   88),
    # -- bosses --
    # The bosses come from a different pack -- chunky, panelled hulls that read
    # as a different class of thing to the flat enemy silhouettes. They ship
    # coloured, so they are desaturated on the way in to stay tintable.
    ("boss_sentinel", f"{EXT}/spaceShips_007.png",   180, 200),
    ("boss_hive",     f"{EXT}/spaceShips_004.png",   180, 200),
    ("boss_warden",   f"{EXT}/spaceShips_002.png",   180, 200),
    ("boss_arm",      f"{SIMPLE}/satellite_B.png",   0,   72),
    # -- projectiles --
    ("bolt",          f"{UFO}/laserBlue1.png",       180, 34),
    ("beam",          f"{UFO}/laserBlue2.png",       180, 46),
    ("orb",           f"{SIMPLE}/star_medium.png",   0,   40),
    ("orb_small",     f"{SIMPLE}/star_small.png",    0,   30),
    ("mote",          f"{SIMPLE}/star_tiny.png",     0,   18),
    ("burst",         f"{SIMPLE}/star_large.png",    0,   64),
    ("mine",          f"{SIMPLE}/meteor_squareSmall.png", 0, 44),
    # -- pickups --
    ("pk_heal",       f"{SIMPLE}/icon_plusLarge.png",       0, 46),
    ("pk_bomb",       f"{SIMPLE}/icon_exclamationLarge.png", 0, 46),
    ("pk_weapon",     f"{SIMPLE}/icon_crossLarge.png",      0, 46),
    # -- scenery --
    ("rock_a",        f"{SIMPLE}/meteor_large.png",         0, 96),
    ("rock_b",        f"{SIMPLE}/meteor_detailedLarge.png", 0, 88),
    ("rock_c",        f"{SIMPLE}/meteor_squareLarge.png",   0, 80),
    ("rock_d",        f"{SIMPLE}/meteor_small.png",         0, 52),
    ("flame",         f"{SIMPLE}/effect_yellow.png",        0, 64),
]

PLANET_PICKS = ["planet00", "planet04", "planet06", "planet08"]

# Kenney's sound packs are named by material, not by game event, so the mapping
# from event to file is the design decision -- it is recorded here rather than
# scattered through the Java.
SOUNDS = {
    "shoot_spread":     f"{SCIFI}/laserSmall_001.ogg",
    "shoot_laser":      f"{SCIFI}/laserLarge_000.ogg",
    "shoot_homing":     f"{SCIFI}/laserRetro_002.ogg",
    "enemy_hit":        f"{SCIFI}/impactMetal_003.ogg",
    "tank_hit":         f"{SCIFI}/impactMetal_000.ogg",
    "enemy_kill_small": f"{SCIFI}/explosionCrunch_000.ogg",
    "enemy_kill_big":   f"{SCIFI}/explosionCrunch_003.ogg",
    "player_hit":       f"{SCIFI}/lowFrequency_explosion_000.ogg",
    "shield_break":     f"{SCIFI}/forceField_003.ogg",
    "shield_recharge":  f"{SCIFI}/forceField_000.ogg",
    "bomb":             f"{SCIFI}/explosionCrunch_004.ogg",
    "boss_warning":     f"{IFACE}/error_006.ogg",
    "boss_phase":       f"{SCIFI}/forceField_001.ogg",
    "boss_death":       f"{SCIFI}/lowFrequency_explosion_001.ogg",
    "pickup_weapon":    f"{IFACE}/confirmation_001.ogg",
    "pickup_bomb":      f"{IFACE}/confirmation_003.ogg",
    "weapon_swap":      f"{IFACE}/switch_004.ogg",
    "overdrive_start":  f"{SCIFI}/spaceEngineLarge_000.ogg",
    "overdrive_end":    f"{SCIFI}/spaceEngineLow_002.ogg",
    "wave_start":       f"{IFACE}/maximize_006.ogg",
    "wave_clear":       f"{IFACE}/confirmation_002.ogg",
    "perfect":          f"{IFACE}/bong_001.ogg",
    "stage_banner":     f"{IFACE}/maximize_003.ogg",
    "graze":            f"{IFACE}/tick_002.ogg",
    "ui_move":          f"{IFACE}/click_003.ogg",
    "ui_select":        f"{IFACE}/select_006.ogg",
    "ui_back":          f"{IFACE}/back_002.ogg",
    "game_over":        f"{SCIFI}/spaceEngineLow_004.ogg",
}

FONT_FILES = {
    "title.ttf": f"{FONTS}/Kenney Blocks.ttf",
    "hud.ttf": f"{FONTS}/Kenney Future Narrow.ttf",
    "body.ttf": f"{FONTS}/Kenney Future.ttf",
}


DESATURATE = {"boss_sentinel", "boss_hive", "boss_warden"}


def desaturate(image):
    """Flatten a coloured sprite to bright greyscale, keeping its alpha.

    Tinting multiplies, so any residual hue in the source would fight the
    theme colour. Luma is stretched upward as well, because a mid-grey hull
    tinted phosphor green comes out muddy against the starfield.
    """
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            luma = int(0.299 * r + 0.587 * g + 0.114 * b)
            luma = min(255, 60 + int(luma * 0.86))
            pixels[x, y] = (luma, luma, luma, a)
    return image


# Sprites that stay a single flat shape. Projectiles are deliberately excluded
# from the detail treatment: a bullet has to be an unmistakable blob at a glance,
# and linework on one would trade readability for decoration. Planets carry their
# own colour and are never tinted, so they are excluded too.
FLAT = {"bolt", "beam", "orb", "orb_small", "mote", "burst", "mine", "flame"}


def _erode(mask):
    """One step of 4-connected binary erosion."""
    out = mask.copy()
    out[1:, :] &= mask[:-1, :]
    out[:-1, :] &= mask[1:, :]
    out[:, 1:] &= mask[:, :-1]
    out[:, :-1] &= mask[:, 1:]
    # The shifts leave the outer row and column untouched; nothing on the very
    # edge can survive an erosion, so clear them explicitly.
    out[0, :] = False
    out[-1, :] = False
    out[:, 0] = False
    out[:, -1] = False
    return out


def _outline(mask):
    """The one-pixel border just inside a mask."""
    return mask & ~_erode(mask)


def _mask_image(mask, alpha):
    """White wherever the mask is set, carrying the source alpha for smooth edges."""
    height, width = mask.shape
    out = np.zeros((height, width, 4), dtype=np.uint8)
    out[:, :, 0:3] = 255
    out[:, :, 3] = np.where(mask, alpha, 0).astype(np.uint8)
    return Image.fromarray(out)


def derive_masks(image):
    """Split a finished sprite into its lit facets and its linework.

    The source art is authored with two brightness levels -- 208 and 255 on the
    Simple Space ships -- which a single multiply tint renders as an invisible
    18% difference. Separating them here lets the renderer give each genuinely
    different colours, so the panels the artist actually drew show up at thirty
    pixels tall instead of collapsing into one flat silhouette.

    Returns ``(lit, line)`` images the same size as the input.
    """
    arr = np.asarray(image).astype(np.int16)
    alpha = arr[:, :, 3]
    opaque = alpha > 128
    if not opaque.any():
        return None, None

    luma = 0.299 * arr[:, :, 0] + 0.587 * arr[:, :, 1] + 0.114 * arr[:, :, 2]
    lo = luma[opaque].min()
    hi = luma[opaque].max()
    if hi - lo < 8:
        # A genuinely flat sprite. Treat the whole silhouette as lit so it still
        # gets a rim, rather than inventing facets that were never drawn.
        lit = opaque.copy()
    else:
        # The midpoint splits 208/255 exactly on the ships, and separates
        # highlights from mid-tones on the multi-tone boss hulls, so the same
        # rule serves both without a per-sprite table.
        lit = opaque & (luma >= (lo + hi) * 0.5)

    depth = max(2, int(round(max(image.width, image.height) * 0.045)))
    inner = lit.copy()
    for _ in range(depth):
        inner = _erode(inner)

    line = _outline(opaque) | _outline(lit)
    if inner.any():
        # The inlay: every panel outlined again a few pixels in. This is what
        # puts visible shapes *inside* the shapes, rather than only around them.
        line |= _outline(inner)

    return _mask_image(lit, alpha), _mask_image(line, alpha)


def trim_and_scale(path, rotate, long_edge):
    image = Image.open(path).convert("RGBA")
    if rotate:
        image = image.rotate(rotate, expand=True, resample=Image.BICUBIC)
    box = image.getbbox()
    if box:
        image = image.crop(box)
    scale = long_edge / max(image.width, image.height)
    if scale < 1.0:
        size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        image = image.resize(size, Image.LANCZOS)
    return image


def main():
    for sub in ("gfx", "sfx", "font"):
        target = os.path.join(DST, sub)
        os.makedirs(target, exist_ok=True)

    masks = 0
    for name, path, rotate, long_edge in SPRITES:
        out = os.path.join(DST, "gfx", name + ".png")
        image = trim_and_scale(path, rotate, long_edge)
        if name in DESATURATE:
            image = desaturate(image)
        image.save(out, optimize=True)
        if name not in FLAT:
            # Derived from the finished image, not the source, so the masks are
            # pixel-aligned with what actually ships no matter how it was
            # rotated, trimmed, scaled or desaturated on the way here.
            lit, line = derive_masks(image)
            if lit is not None:
                lit.save(os.path.join(DST, "gfx", name + "_lit.png"), optimize=True)
                line.save(os.path.join(DST, "gfx", name + "_line.png"), optimize=True)
                masks += 2

    for index, planet in enumerate(PLANET_PICKS):
        image = Image.open(os.path.join(PLANETS, planet + ".png")).convert("RGBA")
        box = image.getbbox()
        if box:
            image = image.crop(box)
        image = image.resize((256, 256), Image.LANCZOS)
        image.save(os.path.join(DST, "gfx", f"planet{index}.png"), optimize=True)

    for name, path in SOUNDS.items():
        shutil.copyfile(path, os.path.join(DST, "sfx", name + ".ogg"))

    for name, path in FONT_FILES.items():
        shutil.copyfile(path, os.path.join(DST, "font", name))

    total = 0
    for root, _dirs, files in os.walk(DST):
        for f in files:
            total += os.path.getsize(os.path.join(root, f))
    print(f"staged {len(SPRITES)} sprites (+{masks} detail masks), "
          f"{len(PLANET_PICKS)} planets, {len(SOUNDS)} sounds, "
          f"{len(FONT_FILES)} fonts -> {total/1024:.0f} KB")


if __name__ == "__main__":
    main()
