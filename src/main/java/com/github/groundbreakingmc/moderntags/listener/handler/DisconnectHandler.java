package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.groundbreakingmc.moderntags.manager.PlayerTagManager;
import com.github.groundbreakingmc.moderntags.text.PlaceholderParser;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles DISCONNECT packets to cleanup player data when disconnecting.
 */
public final class DisconnectHandler implements PacketHandler {

    private final PlayerTagManager tagManager;
    private final PlaceholderParser placeholderParser;

    public DisconnectHandler(@NotNull PlayerTagManager tagManager, @NotNull PlaceholderParser placeholderParser) {
        this.tagManager = tagManager;
        this.placeholderParser = placeholderParser;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final Player player = event.getPlayer();

        this.tagManager.cleanup(player);
        this.placeholderParser.cleanup(player);
    }
}
