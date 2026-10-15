package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Refreshes tracked tags on PLAYER_INFO_UPDATE.
 * Game-mode updates also refresh invisibility and the viewer's spectator visibility.
 */
public final class PlayerInfoUpdateHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    /**
     * Creates a handler that forwards player-info changes to the render loop.
     *
     * @param renderLoop the owner of tracked tag state
     */
    public PlayerInfoUpdateHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    /**
     * Refreshes tracked tags for player additions and game-mode changes.
     *
     * @param event the outgoing player-info update
     */
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
            if (updateGameMode) {
                this.renderLoop.post(new RenderTask.GameModeUpdate(target, viewer));
            } else {
                this.renderLoop.post(new RenderTask.RenderIfTracked(target, viewer));
            }
        }
    }
}
