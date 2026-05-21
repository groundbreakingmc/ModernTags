package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts SET_PASSENGERS for player entities and delegates merging to {@link RenderLoop}.
 * The packet is always cancelled so RenderLoop can re-send it with renderer-owned passenger IDs included.
 */
public final class SetPassengersHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public SetPassengersHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerSetPassengers(event);

        final Player target = PlayerLookup.playerById(packet.getEntityId());
        if (target == null) return;

        event.setCancelled(true);

        this.renderLoop.post(new RenderTask.PassengersUpdate(
                target, event.getPlayer(), packet.getPassengers()));
    }
}
