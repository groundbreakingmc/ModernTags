package com.github.groundbreakingmc.moderntags.renderer;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.kyori.adventure.text.Component;

import java.util.List;

/** Native TextDisplay metadata, with frame settings compiled by PacketEvents. */
final class MetadataTemplate {

    private final ServerVersion version;
    private final int packetId;
    private final int componentType;
    private final int opacityType;
    private final int textIndex;
    private final int opacityIndex;
    private final List<EntityData<?>> entries;
    private final byte[] frameEntries;

    MetadataTemplate(ServerVersion version, List<EntityData<?>> frameEntries) {
        this.version = version;
        this.packetId = PacketType.Play.Server.ENTITY_METADATA.getId(version.toClientVersion());
        this.componentType = EntityDataTypes.ADV_COMPONENT.getId(version.toClientVersion());
        this.opacityType = EntityDataTypes.BYTE.getId(version.toClientVersion());
        final int offset = version.isOlderThan(ServerVersion.V_1_20_2) ? -1 : 0;
        this.textIndex = 23 + offset;
        this.opacityIndex = 26 + offset;
        this.entries = List.copyOf(frameEntries);

        if (frameEntries.isEmpty()) {
            this.frameEntries = new byte[0];
            return;
        }
        final var buffer = PacketEvents.getAPI().getNettyManager().getByteBufAllocationOperator().buffer();
        try {
            final var codec = PacketWrapper.createUniversalPacketWrapper(buffer, version);
            codec.writeEntityMetadata(frameEntries);
            // The terminator follows the dynamic entries in the completed packet.
            this.frameEntries = new byte[ByteBufHelper.readableBytes(buffer) - 1];
            ByteBufHelper.getBytes(buffer, 0, this.frameEntries);
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    List<EntityData<?>> entries() {
        return this.entries;
    }

    EntityData<Component> textData(Component text) {
        return new EntityData<>(this.textIndex, EntityDataTypes.ADV_COMPONENT, text);
    }

    private void begin(Object buffer, int entityId, boolean includeEntries) {
        ByteBufHelper.writeVarInt(buffer, this.packetId);
        ByteBufHelper.writeVarInt(buffer, entityId);
        if (includeEntries) ByteBufHelper.writeBytes(buffer, this.frameEntries);
        ByteBufHelper.writeByte(buffer, this.textIndex);
        ByteBufHelper.writeVarInt(buffer, this.componentType);
    }

    private void finish(Object buffer, Byte opacity) {
        if (opacity != null) {
            ByteBufHelper.writeByte(buffer, this.opacityIndex);
            ByteBufHelper.writeVarInt(buffer, this.opacityType);
            ByteBufHelper.writeByte(buffer, opacity);
        }
        ByteBufHelper.writeByte(buffer, 255);
    }

    /** One traversal only: providers still run before every send. */
    static final class Broadcast {

        private final MetadataTemplate template;
        private Component previous;
        private byte[] encoded;

        Broadcast(MetadataTemplate template) {
            this.template = template;
        }

        boolean send(ProtocolManager protocol, Object channel, int entityId,
                     Component text, boolean includeEntries, Byte opacity) {
            // A unique result keeps the wrapper path and never creates a wire fragment.
            if (text != this.previous) {
                this.previous = text;
                this.encoded = null;
                return false;
            }

            final var buffer = ChannelHelper.pooledByteBuf(channel);
            try {
                this.template.begin(buffer, entityId, includeEntries);
                if (this.encoded == null) {
                    final int start = ByteBufHelper.writerIndex(buffer);
                    final var codec = PacketWrapper.createUniversalPacketWrapper(buffer, this.template.version);
                    codec.writeComponent(text);
                    final byte[] encoded = new byte[ByteBufHelper.writerIndex(buffer) - start];
                    ByteBufHelper.getBytes(buffer, start, encoded);
                    this.encoded = encoded;
                } else {
                    ByteBufHelper.writeBytes(buffer, this.encoded);
                }
                this.template.finish(buffer, opacity);
            } catch (RuntimeException | Error failure) {
                ByteBufHelper.release(buffer);
                throw failure;
            }
            // PacketEvents owns the buffer from this boundary, including closed channels.
            protocol.sendPacketSilently(channel, buffer);
            return true;
        }
    }
}
