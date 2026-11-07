package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import io.netty.channel.Channel;

/** Updates native invisibility and crouching for Modern output. */
public final class EntityMetadataHandler {

    private static final int FLAGS_INDEX = 0;
    private static final byte INVISIBLE_FLAG = 0x20;

    private final TagManager tags;

    /**
     * Connects native metadata to the recipient's tracking state.
     */
    public EntityMetadataHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Reads partial metadata for tracked players. Legacy nametags handle these flags themselves.
     */
    public void handle(@NotNull PacketSendEvent event, @NotNull ConfigValues config) {
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;
        final var previousWrapper = event.getLastUsedWrapper();
        final WrapperPlayServerEntityMetadata cachedPacket = previousWrapper instanceof WrapperPlayServerEntityMetadata metadata
                ? metadata : null;
        final int id = cachedPacket != null ? cachedPacket.getEntityId() : readEntityId(event.getByteBuf());
        final var viewer = (Channel) event.getChannel();
        if (!this.tags.isTracked(viewer, id)) return;
        final var packet = cachedPacket != null ? cachedPacket : readMetadata(event, previousWrapper);
        this.handleDecoded(viewer, id, packet, config);
    }

    private void handleDecoded(Channel viewer, int entityId, WrapperPlayServerEntityMetadata packet, ConfigValues config) {
        Boolean invisible = null;
        Boolean sneaking = null;
        for (final var data : packet.getEntityMetadata()) {
            if (invisible == null && data.getIndex() == FLAGS_INDEX && data.getType() == EntityDataTypes.BYTE) {
                invisible = ((byte) data.getValue() & INVISIBLE_FLAG) != 0;
            } else if (sneaking == null && data.getType() == EntityDataTypes.ENTITY_POSE) {
                sneaking = data.getValue() == EntityPose.CROUCHING;
            }
            if (invisible != null && sneaking != null) break;
        }
        if (invisible != null || sneaking != null) this.tags.updateMetadata(viewer, entityId, invisible, sneaking, config);
    }

    private static WrapperPlayServerEntityMetadata readMetadata(PacketSendEvent event, @Nullable PacketWrapper<?> previousWrapper) {
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            return new WrapperPlayServerEntityMetadata(event);
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
            // Observation must not introduce a packet rewrite or replace earlier edits.
            event.setLastUsedWrapper(previousWrapper);
        }
    }

    private static int readEntityId(Object buffer) {
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            return ByteBufHelper.readVarInt(buffer);
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
    }
}
