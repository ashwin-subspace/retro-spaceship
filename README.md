# Retro Spaceship (Android)

A native Android bullet-hell arcade shooter. No web view, no WASM, no game engine —
the whole game is Java drawing to a `SurfaceView` canvas, built straight off the
Android SDK without Gradle.

**Install:** `dist/RetroSpaceship.apk` (1.3 MB)

Copy it to the phone, tap it, allow "install from this source" if asked.
Everything runs offline; the game asks for no permissions at all.

## The game

The playfield is the whole screen -- there are no on-screen buttons. Drag
anywhere to fly; the ship fires by itself, so aiming *is* moving. Your hitbox is
the tiny white dot at the ship's centre, not the sprite, which is what makes
threading a wall of bullets possible.

Both abilities live on a **second finger**, so you never stop steering:

| Gesture | Action |
| --- | --- |
| Drag (one finger) | Fly. Relative to where you touched, so your finger never covers the ship. |
| Second finger, **tap** | **Bomb** — clears the screen and turns every bullet into score. |
| Second finger, **hold** | **Overdrive** — faster, piercing shots and +50% score, once the meter fills. |
| Move your finger **slowly** | Precision. The graze ring appears on its own; there is no focus button. |
| Small icon, top centre | Pause. Deliberately small, and above the ship's ceiling. |

The steering finger deliberately cannot fire a bomb: a short, quick correction of
the ship is indistinguishable from a tap, so allowing it would spend a bomb every
time the player nudged the ship.

The ship is a mass with thrusters, not a cursor — it accelerates, banks into
turns, carries through a hard stop and glides when you let go. See
[Flight](#flight) below.

- **Graze** — pass close to a bullet without being hit. It scores, and it charges
  the overdrive meter. It is the risk/reward core of the scoring system.
- **Perfect wave** — clear a wave untouched for a bonus and a free shield.

Difficulty is not a setting. A director raises a floor with every stage and rides
a bounded adaptive offset on top of it, reading how well you are actually playing.
It can make the game harder or gentler, but it can never undo the ramp — and it
stops adapting entirely during a boss, so bosses stay fixed benchmarks.

Waves are built by spending a **threat budget** on enemy archetypes, each with its
own cost and unlock intensity, then arranged into formations (line, wedge, pincer,
escort…) chosen to suit the wave's flavour. Every enemy weapon telegraphs before
it fires — that is an invariant, not a preference.

Bosses arrive every five stages: **Sentinel**, **Hive**, and **Warden** (whose core
stays sealed until you destroy both arms). Each has three scripted phases. Clearing
one offers a choice of three upgrade cards, which is what makes runs diverge.

Four themes — Phosphor, Cyberpunk, Amber, Ice — re-skin the entire game, not just
the menus. Every sprite ships as white art and is tinted per theme at draw time,
in four layers rather than one flat tint (see below).

## Assets

All artwork and sound is CC0 from [Kenney](https://kenney.nl), staged into the APK
by `tools/stage_assets.py`:

| Source pack | Used for |
| --- | --- |
| Simple Space | player ship, all nine enemy archetypes, projectiles, pickups, asteroids |
| Space Shooter Extension | the three boss hulls (desaturated on import so they tint) |
| Alien UFO Pack | laser bolt and beam sprites |
| Planets | the four per-theme background planets |
| Sci-Fi Sounds / Interface Sounds | all 28 sound effects |
| Kenney Fonts | Kenney Blocks (titles), Kenney Future Narrow (HUD) |

### Flight

The first version eased the ship's *position* toward the finger. That is the
definition of a cursor: with no velocity of its own, it stopped the instant your
finger did. `Flight.java` replaces it with a damped follower on **both** position
and velocity:

```
a = (target - p) * stiffness + (targetVelocity - v) * damping
```

The velocity term is what makes it playable. A plain position spring lags behind
a moving target in proportion to speed — tens of units at a quick drag, which
here is the difference between threading a gap and dying in it. Matching the
finger's velocity cancels that, so steady tracking stays honest while starts,
stops and reversals get real acceleration curves.

`damping` is the weight knob, and it is the counter-intuitive one: it is the
inverse of how long the engines take to reach a new speed, so a *high* value
slaves the ship to the finger and feels like a cursor no matter what else is
tuned. The shipped values (stiffness 700, damping 34) were chosen from a measured
sweep, not by eye — `py tools/run_tests.py` prints the numbers that matter:

| | |
| --- | --- |
| tracking lag | 4.0 units @300 u/s, 8.1 @600, 13.5 @1000 |
| carry past a hard stop | 19.7 units, settling in 11 frames (184ms) |
| drift past a reversal | 4.2 units |
| glide after release | 16 units in 100ms |

The lag figures have an irreducible floor of about half a frame, because the
finger's velocity can only be measured as a backward difference and is therefore
always one sample old. That the error scales with drag speed is the right
behaviour regardless: a fast flick is loose, slow threading is exact.

Physics runs **once per frame**, not once per touch event — driving it from the
event stream tied the ship's motion to the digitizer's report rate. The spring is
substepped at 120Hz, because a stiff spring plus a 50ms frame is how explicit
integration explodes. Roll follows real sideways velocity and lags it, and the
exhaust length, brightness and trail all answer the throttle.

`tools/test/FlightSweep.java` is the tuning instrument — run it by hand to print
a table of lag, overshoot, settle and reversal across candidate values.

### Layered sprites

The Simple Space art is authored with only **two brightness levels, 208 and 255**.
Those are real panel facets — `e_tank` has five, `e_shielder` three — but a single
multiply tint preserves them as an 18% brightness difference, invisible at thirty
pixels tall. (The boss hulls carry 121 levels, which is exactly why they always
looked detailed and the small ships did not.)

So `tools/stage_assets.py` cuts two extra masks per sprite from the *finished*
bitmap, splitting at the midpoint of its own luma range — which lands exactly
between 208 and 255 on the ships and separates highlights from mid-tones on the
bosses, so one rule serves both:

- `<name>_lit.png` — the lit facets
- `<name>_line.png` — the outer rim, the panel boundaries, and an **inlay**: each
  facet outlined again a few pixels in, which is what puts shapes *inside* shapes

`Draw.detailSprite` then composites halo → deepened hull → lit facets → linework.
Contrast comes from **darkening the hull, not whitening the panels**: on several
sprites the lit region is 70–95% of the ship, so pushing it toward white washed
the orange "tough" enemies out to cream and cost them their threat colour. Elites
get yellow linework instead of white. A hit flash bypasses the layers entirely and
draws flat white — feedback outranks decoration. Projectiles have no masks at all
and stay deliberately flat. The linework layer follows the EFFECTS setting.

Total cost: 44 masks, 49 KB, and two extra draw calls per ship.

`tools/preview_ships.py` renders that exact composite offline in Pillow, using the
same `shade`/`mix` formulas as `Theme.java`, at the size sprites actually occupy
on a phone and across all four themes. Tune the constants there — an APK build and
an emulator install is a slow way to look at a colour.

Music is **not** sampled — the four tracks (menu, two battle themes, boss) are
synthesised as PCM on the device at startup by `Music.java`, on a background
thread so a track change never drops a frame.

## Building

```bash
py tools/build_apk.py
```

Two things the emulator cannot usefully judge live in a desktop harness: the
gesture recogniser, because `adb shell input` has no multitouch, and the flight
model, because "does the ship still go where you put it" is a number rather than
an impression. Neither imports anything Android:

```bash
py tools/run_tests.py
```

Needs the Android SDK (build-tools 35.0.0, platform android-36) and a full JDK.
The script finds the JDK itself and will not trust a stale `JAVA_HOME`. There are
no library dependencies, so the build is just aapt2 → javac → d8 → apksigner, with
everything it writes staying under `build/` and `dist/`.

`dist/signing.keystore` is generated on the first build. Keep it on disk: rebuilding with
a different key means Android will refuse to install over an existing copy. Do not commit
the keystore or `dist/RetroSpaceship.apk`.

To regenerate the art and audio from the source packs in `assets_src/`:

```bash
py tools/stage_assets.py && py tools/make_icon.py
py tools/preview_ships.py   # contact sheets for the sprite layering
```

## Layout

```
android/
  AndroidManifest.xml      minSdk 24, targetSdk 35, portrait, no permissions
  assets/                  gfx, sfx, fonts baked into the APK
  res/                     launcher icons, theme, strings
  java/com/ashwin/retrospaceship/
    MainActivity, GameView    surface, render thread, input pump
    Game, Ui, Hud, Gestures   state machine, interface, touch input
    Flight                    the ship's motion, measured not guessed
    World, Director           entity ownership, collisions, wave construction
    Player, Enemy, Boss,      gameplay entities
      Bullets, Pickup
    Fx, Starfield, Draw       effects, background, all canvas access
    Assets, Sfx, Music        loading, sampled audio, synthesised music
    Cfg, Theme, Patterns,     tuning tables and pure maths
      Formations, Rng, Clock
tools/                     asset staging, sprite preview, icons, APK build, tests
```

### Notes on the shape of it

- **One thread.** Touch events arrive on the UI thread and are queued; the render
  thread drains them at the top of each frame. No gameplay code deals with locks.
- **Two clocks.** The world runs on `Clock.t`, which stops for pauses and hitstops.
  Menus, screen shake and flashes run on wall time, so they keep animating through
  a freeze — that contrast is what makes a hitstop read as impact.
- **Asymmetric collision.** Player shots use inflated boxes against enemies; enemy
  bullets use exact circle maths against a 3px core. Reversing that pair is the
  fastest way to make a bullet-hell game feel cheap.
- **The playfield is the entire screen**, sized from the real display rather than
  letterboxed. Enemy hold positions, the boss hover line and formation entry
  points are fractions of that height rather than pixels, so nothing ends up
  parked harmlessly near the top of a tall phone. Travel speeds scale with the
  height too, but damped: drag moves the ship a fixed number of world units per
  screen pixel whatever the field height, so scaling speeds one-for-one would
  outrun the player's finger.
- **No control overlays the playfield.** The abilities are gestures, and the only
  permanent chrome is the pause target, which sits in the band above the ship's
  ceiling — the one part of the field the player can never fly into.
