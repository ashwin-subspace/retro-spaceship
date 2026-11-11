package com.ashwin.retrospaceship;

/**
 * The per-frame services every entity is handed.
 *
 * <p>Entities take this instead of reaching for the world, the effect bus or the
 * mixer directly. That is what keeps the module layering acyclic: an enemy knows
 * how to ask for an explosion, but not where explosions live.
 */
public final class Ctx {
    public final Clock clock;
    public final Fx fx;
    public final Sfx sfx;
    public World world;
    public Player player;

    public Ctx(Clock clock, Fx fx, Sfx sfx) {
        this.clock = clock;
        this.fx = fx;
        this.sfx = sfx;
    }
}
