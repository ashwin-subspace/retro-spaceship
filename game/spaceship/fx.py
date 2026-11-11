"""Visual effects: particles, shockwaves, floating text and the screen bus.

All juice goes through ``ScreenFX``. Gameplay code calls verbs -- ``fx.shake()``,
``fx.hitstop()``, ``fx.burst()`` -- and never touches effect internals, so the
effects can be retuned or disabled in one place.

Screen-level effects (shake, flash, vignette) advance on *real* time so they
keep animating during a hitstop freeze; particles advance on *game* time so they
freeze with the world. That contrast is what makes a hitstop read as impact
rather than as a dropped frame.
"""

import math

import pygame

from . import config, platform_api, theme
from .ui import text as text_mod

try:
    import numpy as np
except ImportError:  # pragma: no cover - environment dependent
    np = None


# ---------- particles ----------

class ParticleSystem:
    """Structure-of-arrays particles, updated vectorised and drawn as rects.

    Rects beat blitting pre-baked surfaces here (measured), so the draw path is
    deliberately a plain loop over ``pygame.draw.rect``.
    """

    def __init__(self, capacity=None):
        self.capacity = int(capacity or platform_api.max_particles())
        self.count = 0
        self._np = np is not None
        if self._np:
            n = self.capacity
            self.x = np.zeros(n)
            self.y = np.zeros(n)
            self.vx = np.zeros(n)
            self.vy = np.zeros(n)
            self.life = np.zeros(n)
            self.max_life = np.ones(n)
            self.size = np.zeros(n)
            self.col = np.zeros((n, 3), dtype=np.int16)
        else:
            self.items = []

    def clear(self):
        self.count = 0
        if not self._np:
            self.items = []

    def burst(self, x, y, color, count=12, direction=None, speed=(70.0, 330.0),
              life=(240.0, 640.0), size=(2, 4), spread=math.pi):
        """Spawn particles. ``direction`` biases them along an angle (radians)."""
        if count <= 0:
            return
        if self._np:
            self._burst_np(x, y, color, count, direction, speed, life, size, spread)
        else:
            self._burst_py(x, y, color, count, direction, speed, life, size, spread)

    def _angles(self, count, direction, spread):
        if direction is None:
            return np.random.uniform(0.0, math.tau, count)
        return np.random.uniform(direction - spread * 0.5, direction + spread * 0.5, count)

    def _burst_np(self, x, y, color, count, direction, speed, life, size, spread):
        free = self.capacity - self.count
        if free <= 0:
            # Drop the oldest half rather than refusing new, more relevant effects.
            keep = self.capacity // 2
            self._compact_to(np.arange(self.count - keep, self.count))
            free = self.capacity - self.count
        count = int(min(count, free))
        if count <= 0:
            return
        start, end = self.count, self.count + count
        angles = self._angles(count, direction, spread)
        speeds = np.random.uniform(speed[0], speed[1], count)
        self.x[start:end] = x
        self.y[start:end] = y
        self.vx[start:end] = np.cos(angles) * speeds
        self.vy[start:end] = np.sin(angles) * speeds
        lifetimes = np.random.uniform(life[0], life[1], count)
        self.life[start:end] = lifetimes
        self.max_life[start:end] = lifetimes
        self.size[start:end] = np.random.randint(size[0], size[1] + 1, count)
        self.col[start:end] = color
        self.count = end

    def _burst_py(self, x, y, color, count, direction, speed, life, size, spread):
        import random as _r
        for _ in range(int(count)):
            if len(self.items) >= self.capacity:
                break
            angle = (_r.uniform(0.0, math.tau) if direction is None
                     else _r.uniform(direction - spread * 0.5, direction + spread * 0.5))
            velocity = _r.uniform(*speed)
            lifetime = _r.uniform(*life)
            self.items.append([x, y, math.cos(angle) * velocity, math.sin(angle) * velocity,
                               lifetime, lifetime, _r.randint(*size), color])
        self.count = len(self.items)

    def _compact_to(self, indices):
        k = len(indices)
        for array in (self.x, self.y, self.vx, self.vy, self.life, self.max_life, self.size):
            array[:k] = array[indices]
        self.col[:k] = self.col[indices]
        self.count = k

    def update(self, dt_ms):
        if self.count == 0 or dt_ms <= 0.0:
            return
        dts = dt_ms / 1000.0
        drag = 0.94 ** (dts * 60.0)
        if self._np:
            c = self.count
            self.x[:c] += self.vx[:c] * dts
            self.y[:c] += self.vy[:c] * dts
            self.vx[:c] *= drag
            self.vy[:c] *= drag
            self.life[:c] -= dt_ms
            alive = self.life[:c] > 0.0
            if not bool(alive.all()):
                self._compact_to(np.nonzero(alive)[0])
        else:
            keep = []
            for p in self.items:
                p[0] += p[2] * dts
                p[1] += p[3] * dts
                p[2] *= drag
                p[3] *= drag
                p[4] -= dt_ms
                if p[4] > 0.0:
                    keep.append(p)
            self.items = keep
            self.count = len(keep)

    def draw(self, surface):
        if self.count == 0:
            return
        rect = pygame.draw.rect
        if self._np:
            fade = self.life[:self.count] / self.max_life[:self.count]
            sizes = np.maximum(1, (self.size[:self.count] * fade).astype(np.int32))
            xs = self.x[:self.count].astype(np.int32)
            ys = self.y[:self.count].astype(np.int32)
            cols = self.col[:self.count]
            for i in range(self.count):
                f = fade[i]
                colour = (int(cols[i][0] * f), int(cols[i][1] * f), int(cols[i][2] * f))
                s = int(sizes[i])
                rect(surface, colour, (int(xs[i]), int(ys[i]), s, s))
        else:
            for p in self.items:
                f = p[4] / p[5]
                colour = (int(p[7][0] * f), int(p[7][1] * f), int(p[7][2] * f))
                s = max(1, int(p[6] * f))
                rect(surface, colour, (int(p[0]), int(p[1]), s, s))


# ---------- transient shapes ----------

class Shockwave:
    __slots__ = ("x", "y", "r0", "r1", "duration", "elapsed", "color", "width")

    def __init__(self, x, y, r0, r1, duration, color, width=2):
        self.x, self.y = x, y
        self.r0, self.r1 = r0, r1
        self.duration = max(1.0, duration)
        self.elapsed = 0.0
        self.color = color
        self.width = width

    @property
    def dead(self):
        return self.elapsed >= self.duration

    def update(self, dt_ms):
        self.elapsed += dt_ms

    def draw(self, surface):
        progress = min(1.0, self.elapsed / self.duration)
        radius = int(self.r0 + (self.r1 - self.r0) * progress)
        if radius <= 0:
            return
        fade = 1.0 - progress
        colour = theme.shade(self.color, fade)
        width = max(1, int(self.width * fade) + 1)
        pygame.draw.circle(surface, colour, (int(self.x), int(self.y)), radius, width)


class Popup:
    __slots__ = ("x", "y", "text", "color", "size", "life", "max_life", "rise")

    def __init__(self, x, y, text, color, size=15, life=780.0, rise=42.0):
        self.x, self.y = x, y
        self.text = text
        self.color = color
        self.size = size
        self.life = life
        self.max_life = life
        self.rise = rise

    @property
    def dead(self):
        return self.life <= 0.0

    def update(self, dt_ms):
        self.life -= dt_ms
        self.y -= self.rise * (dt_ms / 1000.0)

    def draw(self, surface):
        fade = max(0.0, self.life / self.max_life)
        text_mod.draw_text(surface, self.text, self.size, self.x, self.y,
                           self.color, alpha=int(255 * fade))


# ---------- the bus ----------

class ScreenFX:
    def __init__(self, clock, enable_crt=True):
        self.clock = clock
        self.particles = ParticleSystem()
        self.shockwaves = []
        self.popups = []
        self.shake_power = 0.0
        self.shake_dir = (0.0, 0.0)
        self.flash_color = None
        self.flash_left = 0.0
        self.flash_span = 1.0
        self.flash_alpha = 110
        self.vignette_color = None
        self.vignette_left = 0.0
        self.vignette_span = 1.0
        self.vignette_alpha = 0
        self.enable_crt = enable_crt
        self._overlay = None
        self._crt = None
        self._crt_token = -1
        self._vignette_cache = {}

    # -- verbs -----------------------------------------------------------

    def shake(self, power, direction=None):
        """Kick the screen. ``direction`` is a unit-ish (dx, dy) impulse."""
        self.shake_power = min(config.SHAKE_MAX, self.shake_power + power)
        if direction is not None:
            length = math.hypot(direction[0], direction[1]) or 1.0
            self.shake_dir = (direction[0] / length, direction[1] / length)
        else:
            self.shake_dir = (0.0, 0.0)

    def flash(self, color, ms=120.0, alpha=110):
        self.flash_color = color
        self.flash_left = ms
        self.flash_span = max(1.0, ms)
        self.flash_alpha = alpha

    def vignette(self, color, ms=600.0, alpha=120):
        self.vignette_color = color
        self.vignette_left = ms
        self.vignette_span = max(1.0, ms)
        self.vignette_alpha = alpha

    def hitstop(self, ms):
        self.clock.hitstop(ms)

    def slowmo(self, scale, ms):
        self.clock.slowmo(scale, ms)

    def burst(self, x, y, color, count=12, direction=None, **kwargs):
        self.particles.burst(x, y, color, count, direction, **kwargs)

    def shockwave(self, x, y, r0, r1, ms, color, width=2):
        self.shockwaves.append(Shockwave(x, y, r0, r1, ms, color, width))

    def popup(self, x, y, text, color, size=15):
        self.popups.append(Popup(x, y, text, color, size))

    def clear_transients(self):
        self.particles.clear()
        self.shockwaves.clear()
        self.popups.clear()
        self.shake_power = 0.0
        self.flash_left = 0.0
        self.vignette_left = 0.0

    # -- update ----------------------------------------------------------

    def update(self, clock):
        real = clock.real_dt
        game = clock.dt

        self.particles.update(game)
        for wave in self.shockwaves:
            wave.update(real)
        if self.shockwaves:
            self.shockwaves = [w for w in self.shockwaves if not w.dead]
        for popup in self.popups:
            popup.update(real)
        if self.popups:
            self.popups = [p for p in self.popups if not p.dead]

        if self.shake_power > 0.0:
            self.shake_power = max(
                0.0, self.shake_power - config.SHAKE_DECAY_PER_SEC * (real / 1000.0) * 6.0)
            if self.shake_power < 0.15:
                self.shake_power = 0.0
        if self.flash_left > 0.0:
            self.flash_left = max(0.0, self.flash_left - real)
        if self.vignette_left > 0.0:
            self.vignette_left = max(0.0, self.vignette_left - real)

    @property
    def offset(self):
        """Current screen displacement: a directional push plus small jitter."""
        if self.shake_power <= 0.0:
            return (0.0, 0.0)
        import random as _r
        power = self.shake_power
        dx = self.shake_dir[0] * power
        dy = self.shake_dir[1] * power
        jitter = power * 0.55
        return (dx + _r.uniform(-jitter, jitter), dy + _r.uniform(-jitter, jitter))

    # -- draw ------------------------------------------------------------

    def draw_world(self, surface):
        self.particles.draw(surface)
        for wave in self.shockwaves:
            wave.draw(surface)

    def draw_popups(self, surface):
        for popup in self.popups:
            popup.draw(surface)

    def draw_overlay(self, surface):
        if self.vignette_left > 0.0 and self.vignette_color is not None:
            fade = self.vignette_left / self.vignette_span
            self._blit_vignette(surface, self.vignette_color, int(self.vignette_alpha * fade))
        if self.flash_left > 0.0 and self.flash_color is not None:
            fade = self.flash_left / self.flash_span
            overlay = self._get_overlay(surface.get_size())
            overlay.fill((*self.flash_color, int(self.flash_alpha * fade)))
            surface.blit(overlay, (0, 0))
        if self.enable_crt:
            surface.blit(self._get_crt(surface.get_size()), (0, 0))

    def _get_overlay(self, size):
        if self._overlay is None or self._overlay.get_size() != size:
            self._overlay = pygame.Surface(size, pygame.SRCALPHA)
        return self._overlay

    def _get_crt(self, size):
        """Pre-baked scanline sheet -- generated once, reused every frame."""
        if self._crt is not None and self._crt.get_size() == size and self._crt_token == theme.token:
            return self._crt
        surface = pygame.Surface(size, pygame.SRCALPHA)
        for y in range(0, size[1], 3):
            pygame.draw.line(surface, (0, 0, 0, 46), (0, y), (size[0], y))
        self._crt = surface
        self._crt_token = theme.token
        return surface

    def _blit_vignette(self, surface, color, alpha):
        if alpha <= 0:
            return
        size = surface.get_size()
        key = (color, size)
        mask = self._vignette_cache.get(key)
        if mask is None:
            mask = pygame.Surface(size, pygame.SRCALPHA)
            steps = 26
            for i in range(steps):
                frac = i / steps
                inset = int(frac * min(size) * 0.5)
                band_alpha = int(255 * (1.0 - frac) ** 2)
                pygame.draw.rect(
                    mask, (*color, band_alpha),
                    (inset, inset, size[0] - inset * 2, size[1] - inset * 2), 3)
            if len(self._vignette_cache) > 6:
                self._vignette_cache.clear()
            self._vignette_cache[key] = mask
        mask.set_alpha(alpha)
        surface.blit(mask, (0, 0))


class NullFX(ScreenFX):
    """No-op effects, for headless tests that don't care about visuals."""

    def __init__(self, clock):
        super().__init__(clock, enable_crt=False)

    def burst(self, *args, **kwargs):
        return

    def shockwave(self, *args, **kwargs):
        return

    def popup(self, *args, **kwargs):
        return
