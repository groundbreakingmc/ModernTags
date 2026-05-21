package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Stops rendering a player's tag when DESTROY_ENTITIES names their entity ID.
 */
public final class DestroyEntitiesHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public DestroyEntitiesHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerDestroyEntities(event);
        final Player viewer = event.getPlayer();
        for (final int entityId : packet.getEntityIds()) {
            final Player target = PlayerLookup.playerById(entityId);
            if (target != null) {
                this.renderLoop.post(new RenderTask.StopRendering(target, viewer));
            }
        }
    }
}
