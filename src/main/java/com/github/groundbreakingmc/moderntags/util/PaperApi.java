package com.github.groundbreakingmc.moderntags.util;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Paper API that Spigot lacks, checked once at class initialization.
 * Callers keep the Paper path, which needs no conversion or allocation,
 * and use the Bukkit equivalent on Spigot.
 */
public final class PaperApi {

    /** {@code Player#displayName()} returns the display name as a component. */
    public static final boolean COMPONENT_DISPLAY_NAMES = has(Player.class, "displayName");

    /** {@code Entity#getX()}, {@code getY()} and {@code getZ()} read a position without a Location. */
    public static final boolean ENTITY_COORDINATES = has(Entity.class, "getX");

    private PaperApi() {
    }

    private static boolean has(Class<?> type, String method) {
        try {
            type.getMethod(method);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
