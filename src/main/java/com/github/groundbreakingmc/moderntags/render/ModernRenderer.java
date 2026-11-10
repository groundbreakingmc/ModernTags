package com.github.groundbreakingmc.moderntags.render;

import com.github.groundbreakingmc.moderntags.config.model.ModernTag;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;

/**
 * TextDisplay packets of a tag. Every packet is written straight into a pooled buffer:
 * frame properties and text arrive already encoded, and only entity IDs, the spawn
 * position and opacity are written per packet. See {@link PacketBytes}.
 */
public final class ModernRenderer {

    private static final ServerVersion SERVER = PacketEvents.getAPI().getServerManager().getVersion();
    private static final ClientVersion PROTOCOL = SERVER.toClientVersion();

    private static final int SPAWN = PacketType.Play.Server.SPAWN_ENTITY.getId(PROTOCOL);
    private static final int DESTROY = PacketType.Play.Server.DESTROY_ENTITIES.getId(PROTOCOL);
    private static final int METADATA = PacketType.Play.Server.ENTITY_METADATA.getId(PROTOCOL);
    private static final int PASSENGERS = PacketType.Play.Server.SET_PASSENGERS.getId(PROTOCOL);
    private static final int TEXT_DISPLAY = EntityTypes.TEXT_DISPLAY.getId(PROTOCOL);
    private static final int BYTE_TYPE = EntityDataTypes.BYTE.getId(PROTOCOL);

    /** Display metadata sits one index lower before 1.20.2. */
    private static final int INDEX_SHIFT = SERVER.isOlderThan(ServerVersion.V_1_20_2) ? -1 : 0;
    private static final int TEXT_INDEX = 23 + INDEX_SHIFT;
    private static final int OPACITY_INDEX = 26 + INDEX_SHIFT;

    /** Longest encoding of one VarInt, for sizing buffers. */
    private static final int VAR_INT = 5;
    private static final byte[] NO_PROPERTIES = new byte[0];

    /**
     * Everything a spawn packet holds after the position: rotation, data and velocity in
     * this server's order. It is the same for every tag, so PacketEvents encodes it once.
     */
    private static final byte[] SPAWN_TAIL = spawnTail();

    private ModernRenderer() {
    }

    /**
     * Sends a TextDisplay spawn in the recipient's EventLoop. The caller supplies
     * the owner's shared tag identity and the display's initial position.
     * Sends full frame metadata immediately afterwards. Attachment is separate.
     *
     * @param text the frame's text entry from {@link #encodeText}
     */
    public static void spawn(@NotNull Channel viewer, int tagEntityId, @NotNull UUID tagUniqueId,
                             double x, double y, double z,
                             @NotNull CompiledFrame frame, byte @NotNull [] text, byte opacity) {
        PacketBytes.send(viewer, spawnPacket(viewer.alloc(), tagEntityId, tagUniqueId, x, y, z));
        PacketBytes.send(viewer, metadataPacket(viewer.alloc(), tagEntityId, frame.properties, text, true, opacity));
    }

    /**
     * Sends removal of the tag entity in the recipient's EventLoop.
     * The caller updates its rendering state after this method returns.
     */
    public static void destroy(@NotNull Channel viewer, int tagEntityId) {
        PacketBytes.send(viewer, destroyPacket(viewer.alloc(), tagEntityId));
    }

    /**
     * Attaches the tag to its owner's native entity in the recipient's EventLoop.
     * Passengers must contain the current native and foreign passengers, excluding
     * this tag. Their order is preserved and the supplied array is not modified.
     * The caller controls attachment timing and checks that the tag still exists.
     */
    public static void attach(@NotNull Channel viewer, int ownerEntityId, int tagEntityId,
                              int @NotNull [] passengers) {
        PacketBytes.send(viewer, attachPacket(viewer.alloc(), ownerEntityId, tagEntityId, passengers));
    }

    /**
     * Prepares full frames and adjacent deltas once per definition, including the
     * last-to-first transition. The returned frames are shared by all viewers and
     * hold their properties already encoded for this server.
     */
    public static @NotNull List<CompiledFrame> compileFrames(@NotNull ModernTag tag) {
        final int count = tag.frames().size();
        final var properties = new ArrayList<List<EntityData<?>>>(count);
        final var encoded = new ArrayList<byte[]>(count);
        for (final var frame : tag.frames()) {
            final var compiled = compileProperties(frame);
            properties.add(compiled);
            encoded.add(encodeEntries(compiled));
        }
        final var frames = new ArrayList<CompiledFrame>(count);
        for (int i = 0; i < count; i++) {
            final int before = (i + count - 1) % count;
            final var current = properties.get(i);
            final var previous = properties.get(before);
            final var delta = new ArrayList<EntityData<?>>();
            for (int property = 0; property < current.size(); property++) {
                if (!Objects.equals(current.get(property).getValue(), previous.get(property).getValue())) {
                    delta.add(current.get(property));
                }
            }
            final var source = tag.frames().get(i);
            frames.add(new CompiledFrame(encoded.get(i), encoded.get(before), encodeEntries(delta),
                    source.textOpacity(), source.sneakOpacity()));
        }
        return List.copyOf(frames);
    }

    /**
     * Encodes a tag's text as the metadata entry every display of it receives. The ticker
     * calls this once per changed text, so viewers copy bytes instead of each serializing
     * the same component. The result is never modified.
     */
    public static byte @NotNull [] encodeText(@NotNull Component text) {
        return encodeEntries(List.of(new EntityData<>(TEXT_INDEX, EntityDataTypes.ADV_COMPONENT, text)));
    }

    /**
     * Updates an existing display from its last applied frame, text and opacity.
     * Uses the prepared adjacent delta, or a full frame after a skipped frame or
     * definition switch. Equal output sends nothing. Both frames must use the same
     * server protocol; call in the viewer's EventLoop.
     *
     * After normal return, the caller must retain the new frame, text and opacity
     * even when no packet was needed: they describe the client's equivalent state
     * and identify the predecessor for the next adjacent transition.
     *
     * @param text the frame's text entry from {@link #encodeText}
     * @param previousText the text entry last applied to this display
     */
    public static void updateMetadata(@NotNull Channel viewer, int tagEntityId,
                                      @NotNull CompiledFrame frame, byte @NotNull [] text, byte opacity,
                                      @NotNull CompiledFrame previousFrame,
                                      byte @NotNull [] previousText, byte previousOpacity) {
        final byte[] properties;
        if (frame == previousFrame) {
            properties = NO_PROPERTIES;
        } else if (previousFrame.properties == frame.previousProperties) {
            properties = frame.delta;
        } else {
            PacketBytes.send(viewer, metadataPacket(viewer.alloc(), tagEntityId, frame.properties, text, true, opacity));
            return;
        }

        // Entries of equal text are equal bytes; one shared entry is recognized by identity.
        final boolean textChanged = !Arrays.equals(text, previousText);
        final boolean opacityChanged = opacity != previousOpacity;
        if (properties.length == 0 && !textChanged && !opacityChanged) return;
        PacketBytes.send(viewer, metadataPacket(viewer.alloc(), tagEntityId, properties,
                textChanged ? text : null, opacityChanged, opacity));
    }

    /**
     * Compares each natively written packet with PacketEvents' encoding of the same values.
     * Called once at startup, before any connection is served: a server protocol that lays
     * these packets out differently must stop the plugin there rather than corrupt packets.
     *
     * @throws IllegalStateException when a layout differs
     */
    static void verify() {
        final var allocator = UnpooledByteBufAllocator.DEFAULT;
        final var uniqueId = new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L);
        PacketBytes.expect(new WrapperPlayServerSpawnEntity(300, Optional.of(uniqueId), EntityTypes.TEXT_DISPLAY,
                        new Vector3d(1.5, -64.25, 30_000_000.0), 0f, 0f, 0f, 0, Optional.empty()),
                spawnPacket(allocator, 300, uniqueId, 1.5, -64.25, 30_000_000.0));
        PacketBytes.expect(new WrapperPlayServerDestroyEntities(300), destroyPacket(allocator, 300));
        PacketBytes.expect(new WrapperPlayServerSetPassengers(200, new int[]{7, 300}),
                attachPacket(allocator, 200, 300, new int[]{7}));

        final var text = Component.text("ModernTags");
        final EntityData<?> property = new EntityData<>(15 + INDEX_SHIFT, EntityDataTypes.BYTE, (byte) 3);
        final List<EntityData<?>> entries = List.of(property,
                new EntityData<>(TEXT_INDEX, EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(OPACITY_INDEX, EntityDataTypes.BYTE, (byte) 60));
        PacketBytes.expect(new WrapperPlayServerEntityMetadata(300, entries),
                metadataPacket(allocator, 300, encodeEntries(List.of(property)), encodeText(text), true, (byte) 60));
    }

    private static ByteBuf spawnPacket(ByteBufAllocator allocator, int tagEntityId, UUID tagUniqueId,
                                       double x, double y, double z) {
        final ByteBuf buffer = allocator.buffer(
                3 * VAR_INT + 2 * Long.BYTES + 3 * Double.BYTES + SPAWN_TAIL.length);
        ByteBufHelper.writeVarInt(buffer, SPAWN);
        ByteBufHelper.writeVarInt(buffer, tagEntityId);
        buffer.writeLong(tagUniqueId.getMostSignificantBits());
        buffer.writeLong(tagUniqueId.getLeastSignificantBits());
        ByteBufHelper.writeVarInt(buffer, TEXT_DISPLAY);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeBytes(SPAWN_TAIL);
        return buffer;
    }

    private static ByteBuf destroyPacket(ByteBufAllocator allocator, int tagEntityId) {
        final ByteBuf buffer = allocator.buffer(3 * VAR_INT);
        ByteBufHelper.writeVarInt(buffer, DESTROY);
        ByteBufHelper.writeVarInt(buffer, 1);
        ByteBufHelper.writeVarInt(buffer, tagEntityId);
        return buffer;
    }

    private static ByteBuf attachPacket(ByteBufAllocator allocator, int ownerEntityId, int tagEntityId,
                                        int[] passengers) {
        final ByteBuf buffer = allocator.buffer((passengers.length + 4) * VAR_INT);
        ByteBufHelper.writeVarInt(buffer, PASSENGERS);
        ByteBufHelper.writeVarInt(buffer, ownerEntityId);
        ByteBufHelper.writeVarInt(buffer, passengers.length + 1);
        for (final int passenger : passengers) ByteBufHelper.writeVarInt(buffer, passenger);
        ByteBufHelper.writeVarInt(buffer, tagEntityId);
        return buffer;
    }

    /** Writes encoded entries in the order properties, text, opacity; a null text is left out. */
    private static ByteBuf metadataPacket(ByteBufAllocator allocator, int tagEntityId, byte[] properties,
                                          byte @Nullable [] text, boolean withOpacity, byte opacity) {
        final ByteBuf buffer = allocator.buffer(
                3 * VAR_INT + properties.length + (text == null ? 0 : text.length) + 3);
        ByteBufHelper.writeVarInt(buffer, METADATA);
        ByteBufHelper.writeVarInt(buffer, tagEntityId);
        buffer.writeBytes(properties);
        if (text != null) buffer.writeBytes(text);
        if (withOpacity) {
            buffer.writeByte(OPACITY_INDEX);
            ByteBufHelper.writeVarInt(buffer, BYTE_TYPE);
            buffer.writeByte(opacity);
        }
        buffer.writeByte(0xFF);
        return buffer;
    }

    /** Encodes metadata entries through PacketEvents, without the terminator that ends a packet's list. */
    private static byte[] encodeEntries(List<EntityData<?>> entries) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            PacketWrapper.createUniversalPacketWrapper(buffer).writeEntityMetadata(entries);
            final byte[] encoded = new byte[buffer.readableBytes() - 1];
            buffer.readBytes(encoded);
            return encoded;
        } finally {
            buffer.release();
        }
    }

    private static byte[] spawnTail() {
        final byte[] packet = PacketBytes.of(new WrapperPlayServerSpawnEntity(0, Optional.of(new UUID(0L, 0L)),
                EntityTypes.TEXT_DISPLAY, Vector3d.zero(), 0f, 0f, 0f, 0, Optional.empty()));
        // Packet ID, entity ID 0, UUID, entity type and position precede the tail; verify() checks it.
        final int head = ByteBufHelper.getByteSize(SPAWN) + 1 + 2 * Long.BYTES
                + ByteBufHelper.getByteSize(TEXT_DISPLAY) + 3 * Double.BYTES;
        return Arrays.copyOfRange(packet, Math.min(head, packet.length), packet.length);
    }

    private static List<EntityData<?>> compileProperties(ModernTag.Frame frame) {
        return List.of(
                new EntityData<>(11 + INDEX_SHIFT, EntityDataTypes.VECTOR3F,
                        new Vector3f(frame.xOffset(), frame.yOffset(), frame.zOffset())),
                new EntityData<>(12 + INDEX_SHIFT, EntityDataTypes.VECTOR3F,
                        new Vector3f(frame.scale(), frame.scale(), frame.scale())),
                new EntityData<>(15 + INDEX_SHIFT, EntityDataTypes.BYTE, (byte) (frame.verticalBillboard() ? 1 : 3)),
                new EntityData<>(16 + INDEX_SHIFT, EntityDataTypes.INT, frame.brightness()),
                new EntityData<>(17 + INDEX_SHIFT, EntityDataTypes.FLOAT, frame.viewRange()),
                new EntityData<>(18 + INDEX_SHIFT, EntityDataTypes.FLOAT, frame.shadowRadius()),
                new EntityData<>(19 + INDEX_SHIFT, EntityDataTypes.FLOAT, frame.shadowStrength()),
                new EntityData<>(24 + INDEX_SHIFT, EntityDataTypes.INT, frame.lineWidth()),
                new EntityData<>(25 + INDEX_SHIFT, EntityDataTypes.INT, frame.backgroundColor()),
                new EntityData<>(27 + INDEX_SHIFT, EntityDataTypes.BYTE, frame.flags()));
    }

    /**
     * Encoded full properties and an adjacent delta shared by viewers, prepared by compileFrames.
     * The arrays are never modified; {@code previousProperties} is the predecessor's own array,
     * so an adjacent transition is recognized by identity.
     */
    public static final class CompiledFrame {

        private final byte[] properties;
        private final byte[] previousProperties;
        private final byte[] delta;
        private final byte textOpacity;
        private final byte sneakOpacity;

        private CompiledFrame(byte[] properties, byte[] previousProperties, byte[] delta,
                              byte textOpacity, byte sneakOpacity) {
            this.properties = properties;
            this.previousProperties = previousProperties;
            this.delta = delta;
            this.textOpacity = textOpacity;
            this.sneakOpacity = sneakOpacity;
        }

        /** Returns this frame's configured opacity for the viewer's current pose state. */
        public byte opacity(boolean sneaking) {
            return sneaking ? this.sneakOpacity : this.textOpacity;
        }
    }
}
