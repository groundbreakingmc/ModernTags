package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts SET_PASSENGERS for player entities and delegates merging to {@link RenderLoop}.
 * Matching packets are cancelled so RenderLoop can re-send them with renderer-owned passenger IDs included.
 */
public final class SetPassengersHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public SetPassengersHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var previous = event.getLastUsedWrapper();
        final Player target;
        final int[] passengers;

        if (previous instanceof WrapperPlayServerSetPassengers packet) {
            // Preserve passenger edits made by preceding packet listeners.
            target = PlayerLookup.playerById(packet.getEntityId());
            if (target == null) return;
            passengers = packet.getPassengers();
        } else {
            final Object buffer = event.getByteBuf();
            final int index = ByteBufHelper.readerIndex(buffer);
            try {
                // Decode only player vehicles; no wrapper or passenger array for other entities.
                target = PlayerLookup.playerById(ByteBufHelper.readVarInt(buffer));
                if (target == null) return;

                final int count = ByteBufHelper.readVarInt(buffer);
                if (count < 0) return;
                passengers = new int[count];
                for (int i = 0; i < count; i++) {
                    passengers[i] = ByteBufHelper.readVarInt(buffer);
                }
            } finally {
                // Keep the packet readable by PacketEvents and subsequent listeners.
                ByteBufHelper.readerIndex(buffer, index);
            }
        }

        // Merge before sending so tag entities do not replace the server's passengers.
        event.setCancelled(true);

        this.renderLoop.post(new RenderTask.PassengersUpdate(
                target, event.getPlayer(), passengers));
    }
}
