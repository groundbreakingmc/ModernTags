package com.github.groundbreakingmc.moderntags.renderer;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.mylib.config.Config;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.google.common.collect.ImmutableList;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Renderer that spawns a client-side TEXT_DISPLAY entity that rides the target player.
 *
 * <p>Per-target state (tag entity ID, current frame, active viewers) is stored in
 * {@link #targetData}, keyed by Player reference. All fields are accessed during drain
 * only (single writer, no synchronisation needed).
 *
 * <p>Sneak opacity is applied directly by {@link com.github.groundbreakingmc.moderntags.core.RenderLoop}
 * via {@link #applySneakOpacity}/{@link #removeSneakOpacity} when the suppress mask transitions.
 */
public final class ModernRenderer implements TagRenderer {

    private static final int PASSENGER_DELAY_MS = 100;
    private static final double TAG_Y_OFFSET = 1.8;
    private static final int TEXT_COMPONENT_INDEX = 23;
    private static final int TEXT_OPACITY_INDEX = 26;

    private final ModernTags plugin;
    private final int frameUpdateRate;
    private final int placeholdersUpdateRate;
    private final List<Frame> frames;

    /**
     * Per-target state. Plain map — drain-only access.
     */
    private final IdentityHashMap<Player, TargetData> targetData = new IdentityHashMap<>(64);

    private final ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

    private ModernRenderer(@NotNull ModernTags plugin,
                           int frameUpdateRate,
                           int placeholdersUpdateRate,
                           @NotNull List<Frame> frames) {
        this.plugin = plugin;
        this.frameUpdateRate = frameUpdateRate;
        this.placeholdersUpdateRate = placeholdersUpdateRate;
        this.frames = frames;
    }

    public static ModernRenderer of(@NotNull ModernTags plugin, @NotNull Config config) {
        final int frameUpdateRate = config.findInt("frame-update-rate");
        final int placeholdersUpdateRate = config.findInt("placeholders-update-rate");

        final List<Frame> frames = config.findSectionList("frames").stream()
                .map(ModernRenderer::parseFrame)
                .collect(ImmutableList.toImmutableList());

        if (frames.isEmpty()) {
            throw new IllegalStateException("[ModernTags] ModernRenderer requires at least one frame");
        }
        return new ModernRenderer(plugin, frameUpdateRate, placeholdersUpdateRate, frames);
    }

    // ── TagRenderer ───────────────────────────────────────────────────────────

    @Override
    public void render(@NotNull ViewerState state) {
        final TargetData data = this.getOrCreate(state.target);
        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        final Frame frame = this.frames.get(data.currentFrame);

        this.protocolManager.sendPacketSilently(channel,
                createSpawnPacket(data.tagEntityId, state.target.getLocation()));
        this.protocolManager.sendPacketSilently(channel,
                this.createMetadataPacket(state, data, frame));

        // Attach the tag as a passenger after a short delay to ensure the client has processed the spawn.
        Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
            if (state.target.isOnline() && !state.target.isDead() && ChannelHelper.isOpen(channel)) {
                this.protocolManager.sendPacketSilently(channel,
                        createSetPassengersWithTag(state.target, data.tagEntityId));
            }
        }, PASSENGER_DELAY_MS, TimeUnit.MILLISECONDS);

        data.viewers.add(state.viewer);
    }

    @Override
    public void stopRendering(@NotNull ViewerState state) {
        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;

        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(
                state.viewer, createDestroyPacket(data.tagEntityId));
        data.viewers.remove(state.viewer);
    }

    @Override
    public void updateFrame(@NotNull ViewerState state) {
        if (this.frames.size() <= 1) return;
        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;

        data.currentFrame = (data.currentFrame + 1) % this.frames.size();
        this.broadcastMetadata(state, data);
    }

    @Override
    public void updatePlaceholders(@NotNull ViewerState state) {
        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;
        this.broadcastMetadata(state, data);
    }

    @Override
    public void cleanup(@NotNull Player player) {
        final TargetData data = this.targetData.remove(player);

        for (final TargetData td : this.targetData.values()) {
            td.viewers.remove(player);
        }

        if (data == null) return;

        final var destroyPacket = createDestroyPacket(data.tagEntityId);
        for (final Player viewer : data.viewers) {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, destroyPacket);
        }
    }

    @Override
    public int frameUpdateRate() {
        return this.frameUpdateRate;
    }

    @Override
    public int placeholdersUpdateRate() {
        return this.placeholdersUpdateRate;
    }

    // ── Sneak opacity ─────────────────────────────────────────────────────────

    /**
     * Sends a metadata packet with reduced opacity to indicate the crouching state.
     */
    public void applySneakOpacity(@NotNull ViewerState state) {
        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;
        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        final Frame frame = this.frames.get(data.currentFrame);
        this.protocolManager.sendPacketSilently(channel,
                new WrapperPlayServerEntityMetadata(data.tagEntityId, frame.dataWithSneakOpacity()));
    }

    /**
     * Restores the default opacity after crouching ends.
     */
    public void removeSneakOpacity(@NotNull ViewerState state) {
        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;
        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        final Frame frame = this.frames.get(data.currentFrame);
        this.protocolManager.sendPacketSilently(channel,
                new WrapperPlayServerEntityMetadata(data.tagEntityId, frame.dataWithDefaultOpacity()));
    }

    // ── Passenger IDs ─────────────────────────────────────────────────────────

    /**
     * Returns the tag entity IDs owned by this renderer for {@code state.target}. Used by RenderLoop for SET_PASSENGERS merging.
     */
    public int[] ownedPassengerIds(@NotNull ViewerState state) {
        final TargetData data = this.targetData.get(state.target);
        return data != null ? new int[]{data.tagEntityId} : new int[0];
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    private void broadcastMetadata(@NotNull ViewerState state, @NotNull TargetData data) {
        final Frame frame = this.frames.get(data.currentFrame);
        for (final Player viewer : data.viewers) {
            final Object channel = this.protocolManager.getChannel(viewer.getUniqueId());
            if (channel == null) continue;
            this.protocolManager.sendPacketSilently(channel,
                    this.createMetadataPacket(state, data, frame));
        }
    }

    // ── Packet builders ───────────────────────────────────────────────────────

    private static WrapperPlayServerSpawnEntity createSpawnPacket(int entityId,
                                                                  @NotNull Location location) {
        return new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(UUID.randomUUID()), EntityTypes.TEXT_DISPLAY,
                new Vector3d(location.getX(), location.getY() + TAG_Y_OFFSET, location.getZ()),
                0f, 0f, 0f, 0, Optional.empty()
        );
    }

    private WrapperPlayServerEntityMetadata createMetadataPacket(@NotNull ViewerState state,
                                                                 @NotNull TargetData data,
                                                                 @NotNull Frame frame) {
        final List<EntityData<?>> metadata = new ArrayList<>(frame.entityData().size() + 2);
        metadata.addAll(frame.entityData());
        metadata.add(new EntityData<>(
                TEXT_COMPONENT_INDEX, EntityDataTypes.ADV_COMPONENT,
                this.plugin.tagTextResolver().resolve(state.target, state.viewer, frame.text())
        ));
        if (!data.viewers.contains(state.viewer)) {
            metadata.add(new EntityData<>(
                    TEXT_OPACITY_INDEX, EntityDataTypes.BYTE,
                    state.hasSuppress(ViewerState.SUPPRESS_SNEAK) ? frame.sneakOpacity() : frame.defaultOpacity()
            ));
        }
        return new WrapperPlayServerEntityMetadata(data.tagEntityId, metadata);
    }

    private static WrapperPlayServerSetPassengers createSetPassengersWithTag(
            @NotNull Player target, int tagEntityId) {
        final var passengers = target.getPassengers();
        final int[] ids = new int[passengers.size() + 1];
        for (int i = 0; i < passengers.size(); i++) ids[i] = passengers.get(i).getEntityId();
        ids[ids.length - 1] = tagEntityId;
        return new WrapperPlayServerSetPassengers(target.getEntityId(), ids);
    }

    public static WrapperPlayServerDestroyEntities createDestroyPacket(int... entityIds) {
        return new WrapperPlayServerDestroyEntities(entityIds);
    }

    // ── Per-target state ──────────────────────────────────────────────────────

    @NotNull
    private TargetData getOrCreate(@NotNull Player target) {
        return this.targetData.computeIfAbsent(target, k -> new TargetData());
    }

    private static final class TargetData {

        final int tagEntityId = SpigotReflectionUtil.generateEntityId();
        int currentFrame = 0;

        /**
         * Viewers currently seeing this target's tag. Maintained for broadcast in
         * {@link #updateFrame}/{@link #updatePlaceholders}; mirrors RenderLoop's authoritative list.
         */
        final Set<Player> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    public static final class Frame {

        private final Text text;
        private final byte defaultOpacity;
        private final List<EntityData<?>> dataWithDefaultOpacity;
        private final byte sneakOpacity;
        private final List<EntityData<?>> dataWithSneakOpacity;
        private final List<EntityData<?>> entityData;

        Frame(@NotNull String text, byte defaultTextOpacity, byte sneakTextOpacity,
              @NotNull List<EntityData<?>> entityData) {
            this.text = Text.cacheableOf(text);
            this.defaultOpacity = defaultTextOpacity;
            this.dataWithDefaultOpacity = List.of(new EntityData<>(26, EntityDataTypes.BYTE, defaultTextOpacity));
            this.sneakOpacity = sneakTextOpacity;
            this.dataWithSneakOpacity = List.of(new EntityData<>(26, EntityDataTypes.BYTE, sneakTextOpacity));
            this.entityData = ImmutableList.copyOf(entityData);
        }

        public Text text() {
            return this.text;
        }

        public byte defaultOpacity() {
            return this.defaultOpacity;
        }

        public List<EntityData<?>> dataWithDefaultOpacity() {
            return this.dataWithDefaultOpacity;
        }

        public byte sneakOpacity() {
            return this.sneakOpacity;
        }

        public List<EntityData<?>> dataWithSneakOpacity() {
            return this.dataWithSneakOpacity;
        }

        public List<EntityData<?>> entityData() {
            return this.entityData;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            private String text = "";
            private float xOffset = 0f, yOffset = .15f, zOffset = 0f;
            private float scale = 1f;
            private boolean useVerticalBillboard = false;
            private float viewRange = 1.0f;
            private float shadowRadius = .0f, shadowStrength = 1.0f;
            private int lineWidth = 200;
            private Color backgroundColor = null;
            private byte textOpacity = -1;
            private byte sneakTextOpacity = 60;
            private boolean shadowed = false, seeThrough = false, defaultBackground = false;
            private TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.CENTER;
            private int brightnessEncoded = -1;

            public Builder text(String v) {
                this.text = v;
                return this;
            }

            public Builder xOffset(float v) {
                this.xOffset = v;
                return this;
            }

            public Builder yOffset(float v) {
                this.yOffset = v;
                return this;
            }

            public Builder zOffset(float v) {
                this.zOffset = v;
                return this;
            }

            public Builder scale(float v) {
                this.scale = v;
                return this;
            }

            public Builder useVerticalBillboard(boolean v) {
                this.useVerticalBillboard = v;
                return this;
            }

            public Builder viewRange(float v) {
                this.viewRange = v;
                return this;
            }

            public Builder shadowRadius(float v) {
                this.shadowRadius = v;
                return this;
            }

            public Builder shadowStrength(float v) {
                this.shadowStrength = v;
                return this;
            }

            public Builder lineWidth(int v) {
                this.lineWidth = v;
                return this;
            }

            public Builder textOpacity(byte v) {
                this.textOpacity = v;
                return this;
            }

            public Builder sneakTextOpacity(byte v) {
                this.sneakTextOpacity = v;
                return this;
            }

            public Builder shadowed(boolean v) {
                this.shadowed = v;
                return this;
            }

            public Builder seeThrough(boolean v) {
                this.seeThrough = v;
                return this;
            }

            public Builder defaultBackground(boolean v) {
                this.defaultBackground = v;
                return this;
            }

            public Builder alignment(TextDisplay.TextAlignment v) {
                this.alignment = v;
                return this;
            }

            public Builder backgroundColor(Color v) {
                this.backgroundColor = v;
                return this;
            }

            public Builder brightness(boolean block, int strength) {
                this.brightnessEncoded = strength << (block ? 4 : 20);
                return this;
            }

            public Frame build() {
                final List<EntityData<?>> entityData = new ArrayList<>();
                entityData.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(this.xOffset, this.yOffset, this.zOffset)));
                entityData.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(this.scale, this.scale, this.scale)));
                entityData.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) (this.useVerticalBillboard ? 1 : 3)));
                entityData.add(new EntityData<>(16, EntityDataTypes.INT, this.brightnessEncoded));
                entityData.add(new EntityData<>(17, EntityDataTypes.FLOAT, this.viewRange));
                entityData.add(new EntityData<>(18, EntityDataTypes.FLOAT, this.shadowRadius));
                entityData.add(new EntityData<>(19, EntityDataTypes.FLOAT, this.shadowStrength));
                entityData.add(new EntityData<>(24, EntityDataTypes.INT, this.lineWidth));

                if (this.backgroundColor == null) this.backgroundColor = Color.fromARGB(1073741824);
                entityData.add(new EntityData<>(25, EntityDataTypes.INT, this.backgroundColor.asARGB()));

                byte flags = 0;
                if (this.shadowed) flags |= 0x01;
                if (this.seeThrough) flags |= 0x02;
                if (this.defaultBackground) flags |= 0x04;
                flags |= (byte) (alignmentToBits(this.alignment) << 3);
                entityData.add(new EntityData<>(27, EntityDataTypes.BYTE, flags));

                return new Frame(this.text, this.textOpacity, this.sneakTextOpacity, entityData);
            }

            private static int alignmentToBits(TextDisplay.TextAlignment a) {
                return switch (a) {
                    case CENTER -> 0;
                    case LEFT -> 1;
                    case RIGHT -> 2;
                };
            }
        }
    }

    private static Frame parseFrame(@NotNull Config config) {
        final Frame.Builder b = Frame.builder();

        b.text(config.findStr("text"));
        b.xOffset((float) config.doubleOr("x-offset", 0.0));
        b.yOffset((float) config.doubleOr("y-offset", 0.15));
        b.zOffset((float) config.doubleOr("z-offset", 0.0));
        b.scale((float) config.doubleOr("scale", 1.0));
        b.useVerticalBillboard(config.boolOr("vertical-billboard", false));
        b.viewRange((float) config.doubleOr("view-range", 1.0));
        b.shadowRadius((float) config.doubleOr("shadow-radius", 0.0));
        b.shadowStrength((float) config.doubleOr("shadow-strength", 1.0));
        b.lineWidth(config.intOr("line-width", 200));
        b.textOpacity((byte) config.intOr("text-opacity", -1));
        b.sneakTextOpacity((byte) config.intOr("sneak-text-opacity", 60));
        b.shadowed(config.boolOr("shadowed", true));
        b.seeThrough(config.boolOr("see-through", false));
        b.defaultBackground(config.boolOr("default-background", false));
        b.alignment(TextDisplay.TextAlignment.valueOf(
                config.strOr("alignment", "CENTER").strip().toUpperCase()));

        final String[] brightnessParts = config.strOr("brightness", "block-15").strip().split("-", 2);
        b.brightness(brightnessParts[0].equalsIgnoreCase("block"), Integer.parseInt(brightnessParts[1]));

        final String rawBg = config.strOr("background-color", "#00000000").strip();
        final String hex = !rawBg.isEmpty() && rawBg.charAt(0) == '#' ? rawBg.substring(1) : rawBg;
        final int argb = hex.length() == 6
                ? (int) (0xFF000000L | Long.parseLong(hex, 16))
                : (int) Long.parseLong(hex, 16);
        b.backgroundColor(Color.fromARGB(argb));

        return b.build();
    }
}
