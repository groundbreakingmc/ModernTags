package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.groundbreakingmc.moderntags.manager.PlayerTagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles PLAYER_INFO_UPDATE packets to show/hide tags when players are added or change game modes.
 */
public final class PlayerInfoUpdateHandler implements PacketHandler {

    private final PlayerTagManager tagManager;

    public PlayerInfoUpdateHandler(@NotNull PlayerTagManager tagManager) {
        this.tagManager = tagManager;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerPlayerInfoUpdate(event);

        if (packet.getEntries().isEmpty()) {
            return;
        }

        final var actions = packet.getActions();

        if (actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
            this.handleAddPlayer(packet, event.getPlayer());
        } else if (actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) {
            this.handleGameModeUpdate(packet, event.getPlayer());
        }
    }

    private void handleAddPlayer(@NotNull WrapperPlayServerPlayerInfoUpdate packet, @NotNull Player viewer) {
        final var playerInfo = packet.getEntries().get(0);
        final Player target = Bukkit.getPlayer(playerInfo.getGameProfile().getUUID());

        if (target != null) {
            this.tagManager.showPlayerTag(target, viewer);
        }
    }

    private void handleGameModeUpdate(@NotNull WrapperPlayServerPlayerInfoUpdate packet, @NotNull Player viewer) {
        final var playerInfo = packet.getEntries().get(0);
        final Player target = Bukkit.getPlayer(playerInfo.getGameProfile().getUUID());

        if (target == null) {
            return;
        }

        if (playerInfo.getGameMode() == GameMode.SPECTATOR) {
            this.tagManager.hidePlayerTag(target, viewer);
        } else {
            this.tagManager.showPlayerTag(target, viewer);
        }
    }
}
