package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Stops rendering a player's tag when PLAYER_INFO_REMOVE is received for them.
 */
public final class PlayerInfoRemoveHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public PlayerInfoRemoveHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerPlayerInfoRemove(event);
        final Player viewer = event.getPlayer();
        for (final UUID uuid : packet.getProfileIds()) {
            final Player target = Bukkit.getPlayer(uuid);
            if (target != null) {
                this.renderLoop.post(new RenderTask.StopRendering(target, viewer));
            }
        }
    }
}
