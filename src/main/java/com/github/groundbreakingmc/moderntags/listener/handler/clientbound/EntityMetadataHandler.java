package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.jetbrains.annotations.NotNull;
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
        final var viewer = (Channel) event.getChannel();
        if (event.getLastUsedWrapper() instanceof WrapperPlayServerEntityMetadata packet) {
            final int entityId = packet.getEntityId();
            if (this.tags.isTracked(viewer, entityId)) this.handleDecoded(viewer, entityId, packet, config);
            return;
        }
        this.handleNative(event, viewer, config);
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

    private void handleNative(PacketSendEvent event, Channel viewer, ConfigValues config) {
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        final int entityId;
        Boolean invisible = null;
        Boolean sneaking = null;
        try {
            entityId = ByteBufHelper.readVarInt(buffer);
            if (!this.tags.isTracked(viewer, entityId)) return;
            // Paper exposes the server wire format here, including for translated clients.
            final var version = event.getServerVersion().toClientVersion();
            PacketWrapper<?> reader = null;
            int index;
            while ((index = ByteBufHelper.readUnsignedByte(buffer)) != 0xFF) {
                final int typeId = ByteBufHelper.readVarInt(buffer);
                final var type = EntityDataTypes.getById(version, typeId);
                if (type == null) {
                    throw new IllegalStateException("Unknown entity metadata type id: " + typeId + " version " + version);
                }
                if (type == EntityDataTypes.BYTE) {
                    final byte flags = ByteBufHelper.readByte(buffer);
                    if (invisible == null && index == FLAGS_INDEX) invisible = (flags & INVISIBLE_FLAG) != 0;
                } else if (type == EntityDataTypes.ENTITY_POSE) {
                    final int pose = ByteBufHelper.readVarInt(buffer);
                    if (sneaking == null) sneaking = pose == EntityPose.CROUCHING.getId(version);
                } else if (!skipValue(buffer, type, version)) {
                    // Only opaque payloads need library codecs. readData=false neither reads
                    // the packet header nor registers this local reader for re-encoding.
                    if (reader == null) reader = new PacketWrapper<>(event, false);
                    type.read(reader);
                }
                if (invisible != null && sneaking != null) break;
            }
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
        if (invisible != null || sneaking != null) this.tags.updateMetadata(viewer, entityId, invisible, sneaking, config);
    }

    /** Returns false with the cursor unchanged when a library codec is needed. */
    private static boolean skipValue(Object buffer, EntityDataType<?> type, ClientVersion version) {
        if (type == EntityDataTypes.BOOLEAN) {
            ByteBufHelper.skipBytes(buffer, 1);
        } else if (type == EntityDataTypes.INT || type == EntityDataTypes.OPTIONAL_INT
                || type == EntityDataTypes.BLOCK_FACE || type == EntityDataTypes.BLOCK_STATE
                || type == EntityDataTypes.OPTIONAL_BLOCK_STATE || type == EntityDataTypes.HUMANOID_ARM) {
            ByteBufHelper.readVarInt(buffer);
        } else if (type == EntityDataTypes.FLOAT) {
            ByteBufHelper.skipBytes(buffer, Float.BYTES);
        } else if (type == EntityDataTypes.STRING
                || type == EntityDataTypes.ADV_COMPONENT && version.isOlderThan(ClientVersion.V_1_20_3)) {
            skipString(buffer);
        } else if (type == EntityDataTypes.BLOCK_POSITION) {
            ByteBufHelper.skipBytes(buffer, Long.BYTES);
        } else if (type == EntityDataTypes.OPTIONAL_BLOCK_POSITION) {
            if (ByteBufHelper.readBoolean(buffer)) ByteBufHelper.skipBytes(buffer, Long.BYTES);
        } else if (type == EntityDataTypes.OPTIONAL_UUID) {
            if (ByteBufHelper.readBoolean(buffer)) ByteBufHelper.skipBytes(buffer, 2 * Long.BYTES);
        } else if (type == EntityDataTypes.ROTATION || type == EntityDataTypes.VECTOR3F) {
            ByteBufHelper.skipBytes(buffer, 3 * Float.BYTES);
        } else if (type == EntityDataTypes.QUATERNION) {
            ByteBufHelper.skipBytes(buffer, 4 * Float.BYTES);
        } else if (type == EntityDataTypes.LONG) {
            skipVarLong(buffer);
        } else if (type == EntityDataTypes.VILLAGER_DATA) {
            ByteBufHelper.readVarInt(buffer);
            ByteBufHelper.readVarInt(buffer);
            ByteBufHelper.readVarInt(buffer);
        } else if (type == EntityDataTypes.OPTIONAL_GLOBAL_POSITION) {
            if (ByteBufHelper.readBoolean(buffer)) {
                skipString(buffer);
                ByteBufHelper.skipBytes(buffer, Long.BYTES);
            }
        } else if (type == EntityDataTypes.OPTIONAL_ADV_COMPONENT) {
            final int readerIndex = ByteBufHelper.readerIndex(buffer);
            if (!ByteBufHelper.readBoolean(buffer)) return true;
            if (version.isOlderThan(ClientVersion.V_1_20_3)) {
                skipString(buffer);
                return true;
            }
            ByteBufHelper.readerIndex(buffer, readerIndex);
            return false;
        } else if (type == EntityDataTypes.PARTICLES || type == EntityDataTypes.NBT) {
            final int readerIndex = ByteBufHelper.readerIndex(buffer);
            final int sizeOrTag = type == EntityDataTypes.PARTICLES
                    ? ByteBufHelper.readVarInt(buffer) : ByteBufHelper.readUnsignedByte(buffer);
            if (sizeOrTag == 0) return true;
            // Rewind the presence/header field too: the codec consumes the entire value.
            ByteBufHelper.readerIndex(buffer, readerIndex);
            return false;
        } else {
            return false;
        }
        return true;
    }

    private static void skipString(Object buffer) {
        ByteBufHelper.skipBytes(buffer, ByteBufHelper.readVarInt(buffer));
    }

    private static void skipVarLong(Object buffer) {
        for (int i = 0; i < 10; i++) {
            if ((ByteBufHelper.readByte(buffer) & 0x80) == 0) return;
        }
        throw new IllegalStateException("VarLong is too big");
    }
}
