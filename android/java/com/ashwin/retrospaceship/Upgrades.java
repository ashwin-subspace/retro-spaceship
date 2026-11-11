package com.ashwin.retrospaceship;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Post-boss upgrade cards.
 *
 * <p>Three are offered after each boss. Picking one is what gives runs different
 * shapes: a graze build and a bomb-and-tank build play nothing alike by the
 * third boss, which is what makes a second run worth starting.
 */
public final class Upgrades {
    private Upgrades() {}

    public interface Effect {
        void apply(World world);
    }

    public static final class Card {
        public final String key;
        public final String title;
        public final String description;
        public final Effect effect;

        Card(String key, String title, String description, Effect effect) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.effect = effect;
        }
    }

    private static final List<Card> POOL = Arrays.asList(
            new Card("HULL", "+1 MAX HP", "Reinforce the hull.", world -> {
                world.player.maxHealth++;
                world.player.health = Math.min(world.player.maxHealth, world.player.health + 1);
            }),
            new Card("BOMB", "+1 MAX BOMB", "Carry another bomb.", world -> {
                world.player.maxBombs++;
                world.player.bombs = Math.min(world.player.maxBombs, world.player.bombs + 1);
            }),
            new Card("SHIELD", "FAST SHIELD", "Shield recharges 25% sooner.", world -> {
                world.player.shieldRechargeScale *= 0.75f;
                world.player.shield = true;
                world.player.shieldReadyAt = 0.0;
            }),
            new Card("GRAZE", "WIDE GRAZE", "+8px graze radius.",
                    world -> world.player.grazeRadius += 8f),
            new Card("WEAPON", "+1 WEAPON LV", "Upgrade the current weapon.",
                    world -> world.player.weaponLevel =
                            Math.min(Cfg.WEAPON_MAX_LEVEL, world.player.weaponLevel + 1)),
            new Card("OVERDRIVE", "LONG OVERDRIVE", "Overdrive lasts 2s longer.",
                    world -> world.player.overdriveBonusMs += 2000f),
            new Card("SCORE", "+10% SCORE", "All score gains increased.",
                    world -> world.player.scoreBonus += 0.10f),
            new Card("REPAIR", "FULL REPAIR", "Restore all HP and shield.", world -> {
                world.player.health = world.player.maxHealth;
                world.player.shield = true;
                world.player.shieldReadyAt = 0.0;
            })
    );

    /** Offer three distinct cards. */
    public static List<Card> drawThree() {
        return new ArrayList<>(Rng.shuffled(POOL).subList(0, 3));
    }
}
