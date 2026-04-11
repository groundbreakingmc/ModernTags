package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.groundbreakingmc.moderntags.manager.PlayerTagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles PLAYER_POSITION_AND_LOOK packets to refresh the player's own tag after teleportation.
 */
public final class PlayerPositionHandler implements PacketHandler {

    private final PlayerTagManager tagManager;

    public PlayerPositionHandler(@NotNull PlayerTagManager tagManager) {
        this.tagManager = tagManager;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final Player player = event.getPlayer();

        // Refresh the player's own tag after position change
        this.tagManager.hidePlayerTag(player, player);
        this.tagManager.showPlayerTag(player, player);
    }
}
