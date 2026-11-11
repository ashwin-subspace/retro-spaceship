"""All tunable constants and balance tables.

Gameplay code should import this module (``from . import config``) and read
values at the use site (``config.WAVE_DURATION_MS``) rather than name-importing
them, so tests and the headless harness can monkeypatch timings.

Speeds and rates are expressed per *second*, not per frame -- the game runs on
a delta-time clock (see ``clock.py``).
"""

# ---------- Display ----------

WIDTH, HEIGHT = 480, 640
FPS = 60
TITLE = "RETRO SPACESHIP"

# ---------- Player ----------

PLAYER_SPEED = 360.0            # px/sec, keyboard movement
PLAYER_DRAG_LERP = 0.35         # fraction of remaining distance per 60fps frame
PLAYER_FOCUS_FACTOR = 0.45      # movement multiplier while focus is held
PLAYER_MAX_HEALTH = 3
PLAYER_HITBOX_RADIUS = 3.0      # the real collision circle: tiny, bullet-hell style
PLAYER_SPRITE_W, PLAYER_SPRITE_H = 30, 24
PLAYER_START_BOMBS = 2
PLAYER_MAX_BOMBS = 4
INVINCIBILITY_MS = 1400
BOMB_INVINCIBILITY_MS = 1500

SHIELD_RECHARGE_MS = 12000      # time without damage before the shield returns

# ---------- Graze / overdrive ----------

GRAZE_RADIUS = 22.0
GRAZE_SCORE = 2
GRAZE_OVERDRIVE = 1.4           # overdrive units per graze (meter is 0..100)
KILL_OVERDRIVE = 2.2            # overdrive units per kill
OVERDRIVE_MAX = 100.0
OVERDRIVE_DURATION_MS = 6000
OVERDRIVE_FIRE_SCALE = 0.5      # fire interval multiplier while active
OVERDRIVE_SCORE_BONUS = 0.5     # +50% score

# ---------- Weapons ----------

BULLET_SPEED = 540.0            # px/sec
FIRE_RATE_MS_BASE = 300
FIRE_RATE_MS_MIN = 95
WEAPON_TYPES = ("SPREAD", "LASER", "HOMING")
WEAPON_MAX_LEVEL = 5
PLAYER_BULLET_INFLATE = 4       # generous player-shot hitbox
ENEMY_HURTBOX_INFLATE = 3       # generous enemy hurtbox

# ---------- Enemies ----------

ENEMY_BASE_SPEED = 120.0        # px/sec
ENEMY_BULLET_SPEED = 190.0      # px/sec
ELITE_HP_BONUS = 0.6            # +60% hp
ELITE_SCORE_BONUS = 2.0

# ---------- Difficulty director ----------

INTENSITY_MIN = 1.0
INTENSITY_MAX = 12.0
RAMP_PER_STAGE = 0.55
ADAPT_OFFSET_MIN = -1.5
ADAPT_OFFSET_MAX = 2.5
ADAPT_EMA_PER_SEC = 0.05        # how fast the adaptive offset follows skill
MERCY_WINDOW_MS = 5000          # no intensity increases right after taking a hit
LOW_HP_MERCY_MS = 15000

WAVES_PER_STAGE_MIN = 4
WAVES_PER_STAGE_MAX = 6
WAVE_MAX_MS = 32000             # a wave times out even if stragglers survive
WAVE_GAP_MS = 1400              # breather between waves
STAGE_BANNER_MS = 2200
BOSS_EVERY_N_STAGES = 5
BOSS_CALM_MS = 4500             # quiet beat after a boss dies

THREAT_BUDGET_BASE = 8.0
THREAT_BUDGET_PER_INTENSITY = 6.0

PERFECT_WAVE_BONUS = 250

WAVE_FLAVORS = ("STANDARD", "SWARM", "ARMORED", "BLITZ", "BARRAGE", "AMBUSH", "ELITE")
FORCED_STANDARD_EVERY = 4       # every Nth wave is a breather

# ---------- Scoring ----------

COMBO_TIMEOUT_MS = 1600
PICKUP_CHANCE = 0.16
LOW_HP_PICKUP_BOOST = 2.0

# ---------- Feel / VFX ----------

SHAKE_ON_KILL = 2.5
SHAKE_ON_BIG_KILL = 6.0
SHAKE_ON_HIT = 14.0
SHAKE_ON_BOMB = 20.0
SHAKE_MAX = 18.0
SHAKE_DECAY_PER_SEC = 6.0

HITSTOP_BIG_KILL_MS = 40
HITSTOP_PLAYER_HIT_MS = 90
HITSTOP_BOSS_PHASE_MS = 120
HITSTOP_MAX_MS = 120

SLOWMO_DEATH_SCALE = 0.35
SLOWMO_DEATH_MS = 900
SLOWMO_BOMB_SCALE = 0.6
SLOWMO_BOMB_MS = 400

ENEMY_HIT_FLASH_MS = 60
MAX_PARTICLES = 700
MAX_ENEMY_BULLETS = 220

# ---------- Colour law ----------
# Role names resolved against the active theme at draw time. Never reuse the
# player-shot colour for enemy fire: the player must never misread a bullet.

COLOR_PLAYER = "GREEN"
COLOR_PLAYER_BULLET = "YELLOW"
COLOR_ENEMY_AIMED = "RED"
COLOR_ENEMY_SPRAY = "MAGENTA"
COLOR_ENEMY_LASER = "CYAN"
COLOR_ENEMY_BODY = "MAGENTA"
COLOR_ENEMY_TOUGH = "ORANGE"
COLOR_PICKUP_WEAPON = "CYAN"
COLOR_PICKUP_BOMB = "ORANGE"
COLOR_UI = "WHITE"
COLOR_UI_ACCENT = "CYAN"

ENEMY_BULLET_MIN_RADIUS = 5.0   # readable against the starfield

# ---------- Telegraphs ----------
# Every enemy weapon must warn before it fires. No exceptions.

TELEGRAPH_MIN_MS = 300
LASER_TELEGRAPH_MS = 500

# ---------- Audio ----------

SAMPLE_RATE = 44100
SFX_THROTTLE_MS = 40            # min gap between repeats of the same sfx
MIXER_CHANNELS = 24
MUSIC_CHANNELS = 2
MUSIC_FADE_MS = 600
