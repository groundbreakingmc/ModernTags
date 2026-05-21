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
 * ADD_PLAYER triggers a render; switching to spectator stops it; switching back re-renders.
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

        if (actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
            final Player viewer = event.getPlayer();
            final Player target = Bukkit.getPlayer(
                    packet.getEntries().get(0).getGameProfile().getUUID());
            if (target != null) {
                this.renderLoop.post(new RenderTask.Render(target, viewer));
            }
        } else if (actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) {
            final var entry = packet.getEntries().get(0);
            final Player viewer = event.getPlayer();
            final Player target = Bukkit.getPlayer(entry.getGameProfile().getUUID());
            if (target == null) return;
            if (entry.getGameMode() == GameMode.SPECTATOR) {
                this.renderLoop.post(new RenderTask.StopRendering(target, viewer));
            } else {
                this.renderLoop.post(new RenderTask.Render(target, viewer));
            }
        }
    }
}
