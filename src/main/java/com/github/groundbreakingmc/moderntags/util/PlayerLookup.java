package com.github.groundbreakingmc.moderntags.util;

import org.bukkit.entity.Player;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Player entity IDs shared by connection events and packet handlers.
 */
public final class PlayerLookup {

    // Keep the primitive-key API: ConcurrentMap<Long, Player> would box each lookup.
    private static final NonBlockingHashMapLong<Player> PLAYERS = new NonBlockingHashMapLong<>();

    private PlayerLookup() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Publishes a connected player for lookups from packet threads.
     *
     * @param player the player joining, or already online when the plugin starts
     */
    public static void register(@NotNull Player player) {
        PLAYERS.put(player.getEntityId(), player);
    }

    /**
     * Removes a disconnected player without removing a replacement entry.
     *
     * @param player the player whose connection is ending
     */
    public static void unregister(@NotNull Player player) {
        PLAYERS.remove(player.getEntityId(), player);
    }

    /**
     * Releases player references when the plugin is disabled.
     */
    public static void clear() {
        PLAYERS.clear();
    }

    /**
     * Finds a connected player by their protocol entity ID.
     *
     * @param entityId the ID carried by an entity packet
     * @return the player, or null if the ID does not belong to a connected player
     */
    public static @Nullable Player playerById(int entityId) {
        return PLAYERS.get(entityId);
    }
}
