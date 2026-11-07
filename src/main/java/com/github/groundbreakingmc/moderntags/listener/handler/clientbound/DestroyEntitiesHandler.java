package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

public final class DestroyEntitiesHandler {

    private final TagManager tags;

    /**
     * Connects entity removal packets to the recipient's tracking state.
     */
    public DestroyEntitiesHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Reads removed entity IDs without copying them or changing the outgoing packet.
     */
    public void handle(@NotNull PacketSendEvent event) {
        final var viewer = (Channel) event.getChannel();
        if (event.getLastUsedWrapper() instanceof WrapperPlayServerDestroyEntities packet) {
            this.handleDecoded(viewer, packet);
            return;
        }
        this.handleNative(viewer, event.getByteBuf());
    }

    private void handleDecoded(Channel viewer, WrapperPlayServerDestroyEntities packet) {
        for (final int entityId : packet.getEntityIds()) {
            this.tags.untrack(viewer, entityId);
        }
    }

    private void handleNative(Channel viewer, Object buffer) {
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            final int count = ByteBufHelper.readVarInt(buffer);
            for (int i = 0; i < count; i++) {
                final int entityId = ByteBufHelper.readVarInt(buffer);
                this.tags.untrack(viewer, entityId);
            }
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
    }
}
