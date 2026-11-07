package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SetPassengersHandler {

    private final TagManager tags;

    /**
     * Connects passenger packets to the recipient's tracking state.
     */
    public SetPassengersHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Records foreign passengers and preserves an attached tag in the outgoing packet.
     */
    public void handle(@NotNull PacketSendEvent event, @NotNull ConfigValues config) {
        final var viewer = (Channel) event.getChannel();
        final var previousWrapper = event.getLastUsedWrapper();
        if (previousWrapper instanceof WrapperPlayServerSetPassengers packet) {
            this.update(event, viewer, packet.getEntityId(), packet.getPassengers(), packet, config);
            return;
        }
        this.handleNative(event, viewer, config);
    }

    private void handleNative(PacketSendEvent event, Channel viewer, ConfigValues config) {
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        final int entityId;
        final int[] passengers;
        try {
            entityId = ByteBufHelper.readVarInt(buffer);
            if (!this.tags.isTracked(viewer, entityId)) return;
            final int count = ByteBufHelper.readVarInt(buffer);
            if (count < 0 || count > ByteBufHelper.readableBytes(buffer)) {
                throw new IllegalStateException("Invalid passenger count: " + count);
            }
            passengers = new int[count];
            for (int i = 0; i < count; i++) {
                passengers[i] = ByteBufHelper.readVarInt(buffer);
            }
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
        this.update(event, viewer, entityId, passengers, null, config);
    }

    private void update(PacketSendEvent event, Channel viewer, int entityId, int[] passengers,
                        @Nullable WrapperPlayServerSetPassengers packet, ConfigValues config) {
        final int[] updated = this.tags.updatePassengers(viewer, entityId, passengers, config);
        if (updated == passengers) return;
        if (packet == null) {
            packet = new WrapperPlayServerSetPassengers(entityId, updated);
            // Field-based wrappers have no buffer; PacketEvents re-encodes through this one.
            packet.setBuffer(event.getByteBuf());
            packet.setServerVersion(event.getServerVersion());
            packet.setClientVersion(event.getUser().getClientVersion());
        } else {
            packet.setPassengers(updated);
        }
        event.setLastUsedWrapper(packet);
        event.markForReEncode(true);
    }
}
