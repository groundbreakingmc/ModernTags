package com.github.groundbreakingmc.moderntags.utils;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
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

    public static boolean canSee(@NotNull Player viewer, @NotNull Player target) {
        if (viewer == target) return true;

        if (!target.isOnline() || !target.isValid() || target.isDead()) {
            return false;
        }

        if (!viewer.canSee(target)) {
            return false;
        }

        if (target.getGameMode() == GameMode.SPECTATOR && viewer.getGameMode() != GameMode.SPECTATOR) {
            return false;
        }

        return target.getTrackedBy().contains(viewer);
    }
}
