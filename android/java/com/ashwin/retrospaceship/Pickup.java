package com.ashwin.retrospaceship;

/** Drops: weapon upgrades, bombs, repairs, and score motes from cleared bullets. */
public final class Pickup extends Entity {

    public static final int SPREAD = 0, LASER = 1, HOMING = 2, BOMB = 3, HEAL = 4, SCORE = 5;

    public final int kind;
    public final int value;
    private float age = 0f;

    public Pickup(float x, float y, int kind, float vx, float vy, int value) {
        super(x, y, vx, vy, 9f);
        this.kind = kind;
        this.value = value;
    }

    public Pickup(float x, float y, int kind) {
        this(x, y, kind, 0f, 95f, 0);
    }

    public boolean isWeapon() {
        return kind == SPREAD || kind == LASER || kind == HOMING;
    }

    @Override
    public void update(Ctx ctx) {
        age += ctx.clock.dt;
        float dts = ctx.clock.dts();
        if (kind == SCORE && ctx.player != null) {
            // Score motes home in, so a bomb's payout always lands and the player
            // is never punished for using one at the wrong moment.
            float dx = ctx.player.x - x;
            float dy = ctx.player.y - y;
            float d = (float) Math.hypot(dx, dy);
            if (d <= 0f) {
                d = 1f;
            }
            vx += dx / d * 420f * dts;
            vy += dy / d * 420f * dts;
            vx *= 0.92f;
            vy *= 0.92f;
        }
        integrate(dts);
        if (y > Cfg.H + 30f) {
            kill();
        }
    }

    public void apply(Player player, Ctx ctx) {
        if (isWeapon()) {
            player.giveWeapon(kind, ctx);
        } else if (kind == BOMB) {
            player.giveBomb(ctx);
        } else if (kind == HEAL) {
            player.heal(1);
            ctx.sfx.play("shield_recharge");
            ctx.fx.popup(player.x, player.y - 40f, "+HP", Theme.color(Theme.GREEN), 15f);
        }
        ctx.fx.burst(x, y, Theme.color(Cfg.C_PICKUP_WEAPON), 10);
    }

    @Override
    public void draw(Draw d) {
        float pulse = 1f + 0.12f * (float) Math.sin(age / 130.0);
        if (kind == SCORE) {
            d.glowSprite(d.assets.get(Assets.MOTE), x, y, 10f, age * 0.3f,
                    Theme.color(Theme.YELLOW), 2f, 255);
            return;
        }
        if (kind == BOMB) {
            d.detailSprite(Assets.PK_BOMB, x, y, radius * 2.4f * pulse, 0f,
                    Theme.color(Cfg.C_PICKUP_BOMB), Theme.color(Theme.WHITE), 1.8f, 255);
            return;
        }
        if (kind == HEAL) {
            d.detailSprite(Assets.PK_HEAL, x, y, radius * 2.4f * pulse, 0f,
                    Theme.color(Theme.GREEN), Theme.color(Theme.WHITE), 1.8f, 255);
            return;
        }
        int color = Theme.color(Cfg.C_PICKUP_WEAPON);
        d.detailSprite(Assets.PK_WEAPON, x, y, radius * 2.2f * pulse,
                age * 0.06f, color, Theme.color(Theme.WHITE), 1.8f, 200);
        // The letter is what actually tells the player which weapon this is, so
        // it sits on top of the badge at full brightness.
        String letter = kind == SPREAD ? "S" : kind == LASER ? "L" : "H";
        d.text(letter, 14f, x, y, Theme.color(Theme.WHITE));
    }

    /** Decide what a dead enemy leaves behind, if anything. */
    public static Pickup rollDrop(float x, float y, boolean lowHealth) {
        float chance = Cfg.PICKUP_CHANCE * (lowHealth ? Cfg.LOW_HP_PICKUP_BOOST : 1f);
        if (!Rng.chance(chance)) {
            return null;
        }
        float roll = Rng.next();
        // On low health the table tilts hard toward repairs: the drop system is
        // also the game's comeback mechanic.
        if (lowHealth && roll < 0.28f) {
            return new Pickup(x, y, HEAL);
        }
        if (roll < 0.16f) {
            return new Pickup(x, y, BOMB);
        }
        if (roll < 0.24f) {
            return new Pickup(x, y, HEAL);
        }
        return new Pickup(x, y, Rng.randint(SPREAD, HOMING));
    }
}
