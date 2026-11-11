"""Delta-time game clock with hitstop and slow motion.

Everything in gameplay measures time against ``GameClock.t`` -- an accumulated
*game* millisecond counter that only advances while the game is actually
running. Pausing simply stops calling ``tick``, so no code ever has to hand-shift
stored timestamps the way an absolute ``pygame.time.get_ticks()`` model does.

``pygame.time.get_ticks()`` is deliberately not used here or anywhere else in
gameplay -- only the UI layer may use wall-clock time, so menus keep animating
during a hitstop freeze.
"""

from . import config


class GameClock:
    def __init__(self):
        self.t = 0.0                 # accumulated game time (ms)
        self.dt = 0.0                # scaled delta for this frame (ms)
        self.real_dt = 0.0           # unscaled delta for this frame (ms)
        self.scale = 1.0             # current time scale (slow motion)
        self.hitstop_remaining = 0.0
        self.frames = 0
        self._slow_remaining = 0.0
        self._slow_scale = 1.0

    # -- advancing -------------------------------------------------------

    def tick(self, real_dt_ms):
        """Advance the clock by one frame's worth of real time.

        Returns the scaled delta actually applied, which is 0.0 during hitstop.
        """
        real = float(real_dt_ms)
        if real < 0.0:
            real = 0.0
        # Guard against the spiral of death after a long stall (e.g. dragging
        # the window): never simulate more than 50ms in one step.
        real = min(real, 50.0)
        self.real_dt = real
        self.frames += 1

        if self.hitstop_remaining > 0.0:
            self.hitstop_remaining = max(0.0, self.hitstop_remaining - real)
            self.dt = 0.0
            return 0.0

        if self._slow_remaining > 0.0:
            self._slow_remaining = max(0.0, self._slow_remaining - real)
            self.scale = 1.0 if self._slow_remaining <= 0.0 else self._slow_scale
        else:
            self.scale = 1.0

        self.dt = real * self.scale
        self.t += self.dt
        return self.dt

    # -- derived ---------------------------------------------------------

    @property
    def dts(self):
        """Scaled delta in seconds -- multiply per-second speeds by this."""
        return self.dt / 1000.0

    @property
    def frame_scalar(self):
        """Scaled delta relative to a 60fps frame, for per-frame-tuned values."""
        return self.dt / (1000.0 / 60.0)

    @property
    def frozen(self):
        return self.hitstop_remaining > 0.0

    # -- effects ---------------------------------------------------------

    def hitstop(self, ms):
        """Freeze gameplay briefly so an impact reads as significant."""
        self.hitstop_remaining = min(
            float(config.HITSTOP_MAX_MS), max(self.hitstop_remaining, float(ms))
        )

    def slowmo(self, scale, ms):
        """Run gameplay at ``scale`` speed for ``ms`` of real time."""
        # A stronger (slower) request always wins over a weaker active one.
        if self._slow_remaining <= 0.0 or scale < self._slow_scale:
            self._slow_scale = max(0.05, float(scale))
        self._slow_remaining = max(self._slow_remaining, float(ms))

    def clear_effects(self):
        self.hitstop_remaining = 0.0
        self._slow_remaining = 0.0
        self._slow_scale = 1.0
        self.scale = 1.0


class Timer:
    """A one-shot deadline measured on game time.

    ``Timer`` stores an absolute ``clock.t`` deadline, which is safe precisely
    because ``t`` freezes with the game.
    """

    __slots__ = ("deadline",)

    def __init__(self, deadline=0.0):
        self.deadline = deadline

    @classmethod
    def started(cls, clock, ms):
        return cls(clock.t + ms)

    def start(self, clock, ms):
        self.deadline = clock.t + ms
        return self

    def expired(self, clock):
        return clock.t >= self.deadline

    def remaining(self, clock):
        return max(0.0, self.deadline - clock.t)

    def progress(self, clock, span_ms):
        """0.0 at start of a span_ms window, 1.0 at the deadline."""
        if span_ms <= 0:
            return 1.0
        return max(0.0, min(1.0, 1.0 - (self.remaining(clock) / span_ms)))
