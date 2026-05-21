package com.github.groundbreakingmc.moderntags.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class PlayerLookup {

    private PlayerLookup() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @Nullable Player playerById(int entityId) {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.getEntityId() == entityId) {
                return player;
            }
        }
        return null;
    }
}
