package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Renders or stops rendering a player's tag on PLAYER_INFO_UPDATE.
 * ADD_PLAYER and game-mode changes refresh tracked pairs; spectator stops rendering.
 * Spectator game-mode updates take precedence when both actions are present.
 */
public final class PlayerInfoUpdateHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public PlayerInfoUpdateHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerPlayerInfoUpdate(event);
        if (packet.getEntries().isEmpty()) return;

        final var actions = packet.getActions();
        final boolean addPlayer = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER);
        final boolean updateGameMode = actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE);
        if (!addPlayer && !updateGameMode) return;

        final Player viewer = event.getPlayer();
        for (final var entry : packet.getEntries()) {
            final Player target = Bukkit.getPlayer(entry.getGameProfile().getUUID());
            if (target == null) continue;
            if (updateGameMode && entry.getGameMode() == GameMode.SPECTATOR) {
                this.renderLoop.post(new RenderTask.StopRendering(target, viewer));
            } else {
                this.renderLoop.post(new RenderTask.RenderIfTracked(target, viewer));
            }
        }
    }
}
