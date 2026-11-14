package com.github.groundbreakingmc.moderntags.util;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

public class NumberUtils {

    /** Shared components of common health values, from 0 to the default maximum of 20. */
    private static final Component[] HEALTH = new Component[21];

    static {
        for (int i = 0; i < HEALTH.length; i++) {
            HEALTH[i] = Component.text(i);
        }
    }

    /**
     * Returns the health shown in tags as unstyled text, rounded up to a whole number.
     * Common values return shared instances, so rendering them allocates nothing.
     *
     * @param health the player's health
     * @return the text component of the rounded health
     */
    public static @NotNull Component healthToComponent(double health) {
        final int normalized = healthToInt(health);
        return normalized >= 0 && normalized < HEALTH.length ? HEALTH[normalized] : Component.text(normalized);
    }

    public static int healthToInt(double health) {
        return (int) Math.ceil(health);
    }
}
