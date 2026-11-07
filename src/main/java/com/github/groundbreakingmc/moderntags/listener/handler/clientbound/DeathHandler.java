package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

public final class DeathHandler {

    private static final byte DEATH_STATUS = 3;

    private final TagManager tags;

    /**
     * Connects death packets to the recipient's tracking state.
     */
    public DeathHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Reads the dying entity's ID without changing the outgoing packet.
     */
    public void handle(@NotNull PacketSendEvent event) {
        if (event.getLastUsedWrapper() instanceof WrapperPlayServerEntityStatus packet) {
            if (packet.getStatus() != DEATH_STATUS) return;
            this.tags.markDead((Channel) event.getChannel(), packet.getEntityId());
            return;
        }
        this.handleNative(event);
    }

    private void handleNative(PacketSendEvent event) {
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        final int entityId;
        try {
            // ENTITY_STATUS uses a fixed-width entity ID, not a VarInt.
            entityId = ByteBufHelper.readInt(buffer);
            if (ByteBufHelper.readByte(buffer) != DEATH_STATUS) return;
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
        this.tags.markDead((Channel) event.getChannel(), entityId);
    }
}
