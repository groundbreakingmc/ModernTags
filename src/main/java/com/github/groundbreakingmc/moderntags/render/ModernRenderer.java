package com.github.groundbreakingmc.moderntags.render;

import com.github.groundbreakingmc.moderntags.config.model.ModernTag;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;

public final class ModernRenderer {

    private ModernRenderer() {
    }

    /**
     * Sends a TextDisplay spawn in the recipient's EventLoop. The caller supplies
     * the owner's shared tag identity and the display's initial position.
     * Sends full frame metadata immediately afterwards. Attachment is separate.
     */
    public static void spawn(@NotNull Channel viewer, int tagEntityId,
                             @NotNull UUID tagUniqueId, @NotNull Vector3d position,
                             @NotNull CompiledFrame frame, @NotNull Component text, byte opacity) {
        final var packet = new WrapperPlayServerSpawnEntity(
                tagEntityId, Optional.of(tagUniqueId), EntityTypes.TEXT_DISPLAY,
                position, 0f, 0f, 0f, 0, Optional.empty());
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
        sendFullMetadata(viewer, tagEntityId, frame, text, opacity);
    }

    /**
     * Sends removal of the tag entity in the recipient's EventLoop.
     * The caller updates its rendering state after this method returns.
     */
    public static void destroy(@NotNull Channel viewer, int tagEntityId) {
        final var packet = new WrapperPlayServerDestroyEntities(tagEntityId);
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
    }

    /**
     * Attaches the tag to its owner's native entity in the recipient's EventLoop.
     * Passengers must contain the current native and foreign passengers, excluding
     * this tag. Their order is preserved and the supplied array is not modified.
     * The caller controls attachment timing and checks that the tag still exists.
     */
    public static void attach(@NotNull Channel viewer, int ownerEntityId, int tagEntityId,
                              int @NotNull [] passengers) {
        final int[] attached = Arrays.copyOf(passengers, passengers.length + 1);
        attached[passengers.length] = tagEntityId;
        final var packet = new WrapperPlayServerSetPassengers(ownerEntityId, attached);
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
    }

    /**
     * Prepares full frames and adjacent deltas once per definition, including the
     * last-to-first transition. The returned frames are shared by all viewers.
     */
    public static @NotNull List<CompiledFrame> compileFrames(@NotNull ModernTag tag,
                                                            @NotNull ServerVersion version) {
        final int offset = version.isOlderThan(ServerVersion.V_1_20_2) ? -1 : 0;
        final var properties = new ArrayList<List<EntityData<?>>>(tag.frames().size());
        for (final var frame : tag.frames()) properties.add(compileProperties(frame, offset));
        final var frames = new ArrayList<CompiledFrame>(properties.size());
        for (int i = 0; i < properties.size(); i++) {
            final var current = properties.get(i);
            final var previous = properties.get((i + properties.size() - 1) % properties.size());
            final var delta = new ArrayList<EntityData<?>>();
            for (int property = 0; property < current.size(); property++) {
                if (!Objects.equals(current.get(property).getValue(), previous.get(property).getValue())) {
                    delta.add(current.get(property));
                }
            }
            final var source = tag.frames().get(i);
            frames.add(new CompiledFrame(current, previous, List.copyOf(delta), 23 + offset, 26 + offset,
                    source.textOpacity(), source.sneakOpacity()));
        }
        return List.copyOf(frames);
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
     */
    public static void updateMetadata(@NotNull Channel viewer, int tagEntityId,
                                      @NotNull CompiledFrame frame, @NotNull Component text, byte opacity,
                                      @NotNull CompiledFrame previousFrame,
                                      @NotNull Component previousText, byte previousOpacity) {
        final List<EntityData<?>> properties;
        if (frame == previousFrame) {
            properties = List.of();
        } else if (previousFrame.properties == frame.previousProperties) {
            properties = frame.delta;
        } else {
            sendFullMetadata(viewer, tagEntityId, frame, text, opacity);
            return;
        }

        final boolean textChanged = !text.equals(previousText);
        final boolean opacityChanged = opacity != previousOpacity;
        if (properties.isEmpty() && !textChanged && !opacityChanged) return;

        List<EntityData<?>> changed = properties;
        if (textChanged || opacityChanged) {
            changed = new ArrayList<>(properties.size() + 2);
            changed.addAll(properties);
            if (textChanged) changed.add(new EntityData<>(frame.textIndex, EntityDataTypes.ADV_COMPONENT, text));
            if (opacityChanged) changed.add(new EntityData<>(frame.opacityIndex, EntityDataTypes.BYTE, opacity));
        }
        sendMetadata(viewer, tagEntityId, changed);
    }

    private static void sendFullMetadata(Channel viewer, int tagEntityId, CompiledFrame frame,
                                         Component text, byte opacity) {
        final var metadata = new ArrayList<EntityData<?>>(frame.properties.size() + 2);
        metadata.addAll(frame.properties);
        metadata.add(new EntityData<>(frame.textIndex, EntityDataTypes.ADV_COMPONENT, text));
        metadata.add(new EntityData<>(frame.opacityIndex, EntityDataTypes.BYTE, opacity));
        sendMetadata(viewer, tagEntityId, metadata);
    }

    private static void sendMetadata(Channel viewer, int tagEntityId, List<EntityData<?>> metadata) {
        final var packet = new WrapperPlayServerEntityMetadata(tagEntityId, metadata);
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
    }

    private static List<EntityData<?>> compileProperties(ModernTag.Frame frame, int offset) {
        return List.of(
                new EntityData<>(11 + offset, EntityDataTypes.VECTOR3F,
                        new Vector3f(frame.xOffset(), frame.yOffset(), frame.zOffset())),
                new EntityData<>(12 + offset, EntityDataTypes.VECTOR3F,
                        new Vector3f(frame.scale(), frame.scale(), frame.scale())),
                new EntityData<>(15 + offset, EntityDataTypes.BYTE, (byte) (frame.verticalBillboard() ? 1 : 3)),
                new EntityData<>(16 + offset, EntityDataTypes.INT, frame.brightness()),
                new EntityData<>(17 + offset, EntityDataTypes.FLOAT, frame.viewRange()),
                new EntityData<>(18 + offset, EntityDataTypes.FLOAT, frame.shadowRadius()),
                new EntityData<>(19 + offset, EntityDataTypes.FLOAT, frame.shadowStrength()),
                new EntityData<>(24 + offset, EntityDataTypes.INT, frame.lineWidth()),
                new EntityData<>(25 + offset, EntityDataTypes.INT, frame.backgroundColor()),
                new EntityData<>(27 + offset, EntityDataTypes.BYTE, frame.flags()));
    }

    /**
     * Full properties and an adjacent delta shared by viewers, prepared by compileFrames.
     */
    public static final class CompiledFrame {

        private final List<EntityData<?>> properties;
        private final List<EntityData<?>> previousProperties;
        private final List<EntityData<?>> delta;
        private final int textIndex;
        private final int opacityIndex;
        private final byte textOpacity;
        private final byte sneakOpacity;

        private CompiledFrame(List<EntityData<?>> properties, List<EntityData<?>> previousProperties,
                              List<EntityData<?>> delta, int textIndex, int opacityIndex,
                              byte textOpacity, byte sneakOpacity) {
            this.properties = properties;
            this.previousProperties = previousProperties;
            this.delta = delta;
            this.textIndex = textIndex;
            this.opacityIndex = opacityIndex;
            this.textOpacity = textOpacity;
            this.sneakOpacity = sneakOpacity;
        }

        /** Returns this frame's configured opacity for the viewer's current pose state. */
        public byte opacity(boolean sneaking) {
            return sneaking ? this.sneakOpacity : this.textOpacity;
        }
    }
}
