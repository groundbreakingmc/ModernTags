package com.github.groundbreakingmc.moderntags.renderer;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.groundbreakingmc.mylib.config.Config;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Renderer that spawns a client-side TEXT_DISPLAY entity that rides the target player.
 *
 * <p>Per-target state (tag entity ID, current frame, active viewers) is stored in
 * {@link #targetData}, keyed by Player reference. All fields are accessed during drain
 * only (single writer, no synchronisation needed).
 *
 * <p>Spawn metadata omits client defaults and applies the current sneak opacity.
 * Frame transitions are compiled at configuration load, including the last-to-first transition.
 * {@link com.github.groundbreakingmc.moderntags.core.RenderLoop} uses {@link #updateOpacity}
 * to refresh it while the display remains rendered.
 */
public final class ModernRenderer implements TagRenderer {

    private static final int PASSENGER_DELAY_MS = 100;
    private static final double TAG_Y_OFFSET = 1.8;
    private static final int TEXT_OPACITY_INDEX = 26;

    private final ModernTags plugin;
    private final int frameUpdateRate;
    private final int placeholdersUpdateRate;
    private final List<Frame> frames;
    private final int textIndex;
    private final List<Transition> transitions;

    /**
     * Per-target state. Plain map — drain-only access.
     */
    private final Map<Player, TargetData> targetData = new IdentityHashMap<>(64);

    private final ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

    private ModernRenderer(@NotNull ModernTags plugin,
                           int frameUpdateRate,
                           int placeholdersUpdateRate,
                           @NotNull List<Frame> frames) {
        this.plugin = plugin;
        this.frameUpdateRate = frames.size() > 1 ? frameUpdateRate : -1;
        this.placeholdersUpdateRate = placeholdersUpdateRate;
        this.frames = frames;
        this.textIndex = PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_20_2) ? 22 : 23;
        this.transitions = compileTransitions(frames);
    }

    private List<Transition> compileTransitions(List<Frame> frames) {
        if (frames.size() <= 1) return List.of();
        final var transitions = new ArrayList<Transition>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            final var previous = frames.get((i + frames.size() - 1) % frames.size());
            final var current = frames.get(i);
            final var changed = new ArrayList<EntityData<?>>();
            for (int entry = 0; entry < current.entityData().size(); entry++) {
                if (!Objects.equals(previous.entityData().get(entry).getValue(), current.entityData().get(entry).getValue())) {
                    changed.add(current.entityData().get(entry));
                }
            }
            final var normal = current.dataWithDefaultOpacity().getFirst();
            final var sneak = current.dataWithSneakOpacity().getFirst();
            transitions.add(new Transition(List.copyOf(changed),
                    normal.getValue().equals(previous.dataWithDefaultOpacity().getFirst().getValue()) ? null : normal,
                    sneak.getValue().equals(previous.dataWithSneakOpacity().getFirst().getValue()) ? null : sneak));
        }
        return transitions;
    }

    private record Transition(List<EntityData<?>> entries, EntityData<?> normalOpacity, EntityData<?> sneakOpacity) {
    }

    public static ModernRenderer of(@NotNull ModernTags plugin, @NotNull Config config) {
        final int frameUpdateRate = config.findInt("frame-update-rate");
        final int placeholdersUpdateRate = config.findInt("placeholders-update-rate");

        final var frames = config.findSectionList("frames").stream()
                .map(frame -> parseFrame(frame, plugin.tagTextResolver()))
                .collect(ImmutableList.toImmutableList());

        if (frames.isEmpty()) {
            throw new IllegalStateException("[ModernTags] ModernRenderer requires at least one frame");
        }
        return new ModernRenderer(plugin, frameUpdateRate, placeholdersUpdateRate, frames);
    }

    @Override
    public void render(@NotNull ViewerState state) {
        final var data = this.targetData.computeIfAbsent(state.target, target -> new TargetData());
        final var frame = this.frames.get(data.currentFrame);

        this.protocolManager.sendPacketSilently(state.channel,
                createSpawnPacket(data.tagEntityId, state.target.getLocation()));
        this.protocolManager.sendPacketSilently(state.channel,
                this.createMetadataPacket(state, data, frame));

        // Validate delayed attachment on the render loop after the client has processed the spawn.
        final var renderLoop = this.plugin.renderLoop();
        final var attachment = new RenderTask.AttachTag(
                state, state.renderGeneration, this, data.tagEntityId);
        state.pendingAttach = attachment;
        Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
            attachment.ready().set(true);
            renderLoop.post(attachment);
        }, PASSENGER_DELAY_MS, TimeUnit.MILLISECONDS);

        data.viewers.put(state.viewer, state);
    }

    /**
     * Attaches a current tag after the render loop validates its state and generation.
     * Called only during drain, which owns target data and viewer membership.
     */
    public void attachPassengers(@NotNull ViewerState state, int tagEntityId) {
        final var data = this.targetData.get(state.target);
        if (data == null || data.tagEntityId != tagEntityId || data.viewers.get(state.viewer) != state) return;
        if (state.target.isOnline() && !state.target.isDead() && ChannelHelper.isOpen(state.channel)) {
            this.protocolManager.sendPacketSilently(state.channel,
                    createSetPassengersWithTag(state.target, tagEntityId));
        }
    }

    @Override
    public void stopRendering(@NotNull ViewerState state) {
        final var data = this.targetData.get(state.target);
        if (data == null) return;

        this.protocolManager.sendPacketSilently(state.channel, createDestroyPacket(data.tagEntityId));
        data.viewers.remove(state.viewer);
    }

    @Override
    public void updateFrame(@NotNull ViewerState state) {
        if (this.frames.size() <= 1) return;
        final var data = this.targetData.get(state.target);
        if (data == null) return;

        data.currentFrame = (data.currentFrame + 1) % this.frames.size();
        this.broadcastMetadata(data);
    }

    @Override
    public void updatePlaceholders(@NotNull ViewerState state) {
        final var data = this.targetData.get(state.target);
        if (data == null) return;
        final var frame = this.frames.get(data.currentFrame);
        // Frame settings and opacity remain on the client until their own update paths change them.
        for (final var pair : data.viewers.values()) {
            try {
                final var text = this.plugin.tagTextResolver().resolve(pair, frame.text());
                this.protocolManager.sendPacketSilently(pair.channel,
                        new WrapperPlayServerEntityMetadata(data.tagEntityId,
                                List.of(new EntityData<>(this.textIndex, EntityDataTypes.ADV_COMPONENT, text))));
            } catch (RuntimeException failure) {
                this.logMetadataFailure(pair, data.currentFrame, "update placeholders", failure);
            }
        }
    }

    @Override
    public void cleanup(@NotNull Player player) {
        final var data = this.targetData.remove(player);

        for (final var otherTarget : this.targetData.values()) {
            otherTarget.viewers.remove(player);
        }

        if (data == null) return;

        final var destroyPacket = createDestroyPacket(data.tagEntityId);
        for (final var state : data.viewers.values()) {
            this.protocolManager.sendPacketSilently(state.channel, destroyPacket);
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

    /**
     * Updates an existing display's opacity for the pair's current sneaking state.
     *
     * @param state the rendered pair whose opacity is refreshed
     */
    public void updateOpacity(@NotNull ViewerState state) {
        final var data = this.targetData.get(state.target);
        if (data == null) return;
        final var frame = this.frames.get(data.currentFrame);
        this.protocolManager.sendPacketSilently(state.channel,
                new WrapperPlayServerEntityMetadata(data.tagEntityId,
                        state.hasSuppress(ViewerState.SUPPRESS_SNEAK)
                                ? frame.dataWithSneakOpacity() : frame.dataWithDefaultOpacity()));
    }

    /**
     * Appends this pair's rendered display ID to the server's passenger IDs.
     * A spawned display is eligible even while its delayed attachment is pending.
     *
     * @param state the pair whose display may be merged into a passenger packet
     * @param passengers the server's passenger IDs, which are never modified
     * @return the original array if no display is rendered, otherwise a copy with its ID appended
     */
    public int[] mergePassengers(@NotNull ViewerState state, int @NotNull [] passengers) {
        if (!state.rendered) return passengers;
        final var data = this.targetData.get(state.target);
        if (data == null) return passengers;

        final var merged = Arrays.copyOf(passengers, passengers.length + 1);
        merged[passengers.length] = data.tagEntityId;
        return merged;
    }

    private void broadcastMetadata(@NotNull TargetData data) {
        final var frame = this.frames.get(data.currentFrame);
        final var transition = this.transitions.get(data.currentFrame);
        for (final var state : data.viewers.values()) {
            try {
                final var text = this.plugin.tagTextResolver().resolve(state, frame.text());
                final var opacity = state.hasSuppress(ViewerState.SUPPRESS_SNEAK)
                        ? transition.sneakOpacity() : transition.normalOpacity();
                this.protocolManager.sendPacketSilently(state.channel,
                        createMetadataPacket(data, transition.entries(), text, opacity, false));
            } catch (RuntimeException failure) {
                this.logMetadataFailure(state, data.currentFrame, "update frame", failure);
            }
        }
    }

    private void logMetadataFailure(ViewerState state, int frame, String operation, RuntimeException failure) {
        this.plugin.getLogger().log(Level.SEVERE, "Failed to " + operation
                + " for tag owner=" + state.target.getUniqueId()
                + ", viewer=" + state.viewer.getUniqueId() + ", frame=" + (frame + 1), failure);
    }

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
        final var text = this.plugin.tagTextResolver().resolve(state, frame.text());
        final var opacity = state.hasSuppress(ViewerState.SUPPRESS_SNEAK)
                ? frame.dataWithSneakOpacity().getFirst() : frame.dataWithDefaultOpacity().getFirst();
        return createMetadataPacket(data, frame.initialData, text,
                (Byte) opacity.getValue() == -1 ? null : opacity, true);
    }

    private WrapperPlayServerEntityMetadata createMetadataPacket(@NotNull TargetData data,
                                                                 @NotNull List<EntityData<?>> entries,
                                                                 @NotNull Component text,
                                                                 EntityData<?> opacity,
                                                                 boolean initialization) {
        final var metadata = new ArrayList<EntityData<?>>(entries.size() + 2);
        metadata.addAll(entries);
        if (!initialization || !Component.empty().equals(text)) {
            metadata.add(new EntityData<>(this.textIndex, EntityDataTypes.ADV_COMPONENT, text));
        }
        if (opacity != null) metadata.add(opacity);
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

    private static final class TargetData {

        final int tagEntityId = SpigotReflectionUtil.generateEntityId();
        int currentFrame = 0;

        // Broadcast only to pairs with a display registered in this renderer.
        /**
         * Viewers currently seeing this target's tag. Maintained for broadcast in
         * {@link #updateFrame}/{@link #updatePlaceholders}; mirrors RenderLoop's authoritative list.
         */
        final Map<Player, ViewerState> viewers = new IdentityHashMap<>();
    }

    public static final class Frame {

        private final Text<ViewerState> text;
        private final List<EntityData<?>> dataWithDefaultOpacity;
        private final List<EntityData<?>> dataWithSneakOpacity;
        private final List<EntityData<?>> entityData;
        private final List<EntityData<?>> initialData;

        Frame(@NotNull Text<ViewerState> text, byte defaultTextOpacity, byte sneakTextOpacity,
              @NotNull List<EntityData<?>> entityData) {
            this.text = text;
            final var version = PacketEvents.getAPI().getServerManager().getVersion();
            final int offset = version.isOlderThan(ServerVersion.V_1_20_2) ? -1 : 0;
            this.dataWithDefaultOpacity = List.of(new EntityData<>(TEXT_OPACITY_INDEX + offset, EntityDataTypes.BYTE, defaultTextOpacity));
            this.dataWithSneakOpacity = List.of(new EntityData<>(TEXT_OPACITY_INDEX + offset, EntityDataTypes.BYTE, sneakTextOpacity));
            this.entityData = offset == 0 ? ImmutableList.copyOf(entityData)
                    : entityData.stream().<EntityData<?>>map(entry -> shiftedEntry(entry, offset)).toList();
            this.initialData = this.entityData.stream()
                    .filter(entry -> !Objects.equals(entry.getValue(), clientDefault(entry.getIndex() - offset)))
                    .toList();
        }

        private static <T> EntityData<T> shiftedEntry(EntityData<T> entry, int offset) {
            return new EntityData<>(entry.getIndex() + offset, entry.getType(), entry.getValue());
        }

        private static Object clientDefault(int index) {
            return switch (index) {
                case 11 -> new Vector3f(0f, 0f, 0f);
                case 12 -> new Vector3f(1f, 1f, 1f);
                case 15, 27 -> (byte) 0;
                case 16 -> -1;
                case 17, 19 -> 1f;
                case 18 -> 0f;
                case 24 -> 200;
                case 25 -> 0x40000000;
                default -> throw new IllegalArgumentException("Unknown TextDisplay metadata index: " + index);
            };
        }

        public Text<ViewerState> text() {
            return this.text;
        }

        public List<EntityData<?>> dataWithDefaultOpacity() {
            return this.dataWithDefaultOpacity;
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

            public Builder() {
            }

            public Builder text(String text) {
                this.text = text;
                return this;
            }

            public Builder xOffset(float offset) {
                this.xOffset = offset;
                return this;
            }

            public Builder yOffset(float offset) {
                this.yOffset = offset;
                return this;
            }

            public Builder zOffset(float offset) {
                this.zOffset = offset;
                return this;
            }

            public Builder scale(float scale) {
                this.scale = scale;
                return this;
            }

            public Builder useVerticalBillboard(boolean vertical) {
                this.useVerticalBillboard = vertical;
                return this;
            }

            public Builder viewRange(float range) {
                this.viewRange = range;
                return this;
            }

            public Builder shadowRadius(float radius) {
                this.shadowRadius = radius;
                return this;
            }

            public Builder shadowStrength(float strength) {
                this.shadowStrength = strength;
                return this;
            }

            public Builder lineWidth(int width) {
                this.lineWidth = width;
                return this;
            }

            public Builder textOpacity(byte opacity) {
                this.textOpacity = opacity;
                return this;
            }

            public Builder sneakTextOpacity(byte opacity) {
                this.sneakTextOpacity = opacity;
                return this;
            }

            public Builder shadowed(boolean shadowed) {
                this.shadowed = shadowed;
                return this;
            }

            public Builder seeThrough(boolean seeThrough) {
                this.seeThrough = seeThrough;
                return this;
            }

            public Builder defaultBackground(boolean defaultBackground) {
                this.defaultBackground = defaultBackground;
                return this;
            }

            public Builder alignment(TextDisplay.TextAlignment alignment) {
                this.alignment = alignment;
                return this;
            }

            public Builder backgroundColor(Color color) {
                this.backgroundColor = color;
                return this;
            }

            public Builder brightness(boolean block, int strength) {
                this.brightnessEncoded = strength << (block ? 4 : 20);
                return this;
            }

            public Frame build(@NotNull TagTextResolver textResolver) {
                final var entityData = new ArrayList<EntityData<?>>();
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

                return new Frame(textResolver.compile(this.text), this.textOpacity, this.sneakTextOpacity, entityData);
            }

            private static int alignmentToBits(TextDisplay.TextAlignment alignment) {
                return switch (alignment) {
                    case CENTER -> 0;
                    case LEFT -> 1;
                    case RIGHT -> 2;
                };
            }
        }
    }

    private static Frame parseFrame(@NotNull Config config, @NotNull TagTextResolver textResolver) {
        final var builder = Frame.builder();

        builder.text(config.findStr("text"));
        builder.xOffset((float) config.doubleOr("x-offset", 0.0));
        builder.yOffset((float) config.doubleOr("y-offset", 0.15));
        builder.zOffset((float) config.doubleOr("z-offset", 0.0));
        builder.scale((float) config.doubleOr("scale", 1.0));
        builder.useVerticalBillboard(config.boolOr("vertical-billboard", false));
        builder.viewRange((float) config.doubleOr("view-range", 1.0));
        builder.shadowRadius((float) config.doubleOr("shadow-radius", 0.0));
        builder.shadowStrength((float) config.doubleOr("shadow-strength", 1.0));
        builder.lineWidth(config.intOr("line-width", 200));
        builder.textOpacity((byte) config.intOr("text-opacity", -1));
        builder.sneakTextOpacity((byte) config.intOr("sneak-opacity", 60));
        builder.shadowed(config.boolOr("shadowed", true));
        builder.seeThrough(config.boolOr("see-through", false));
        builder.defaultBackground(config.boolOr("default-background", false));
        builder.alignment(TextDisplay.TextAlignment.valueOf(
                config.strOr("alignment", "CENTER").strip().toUpperCase()));

        final var brightnessParts = config.strOr("brightness", "block-15").strip().split("-", 2);
        builder.brightness(brightnessParts[0].equalsIgnoreCase("block"), Integer.parseInt(brightnessParts[1]));

        final var rawBackground = config.strOr("background-color", "#00000000").strip();
        final var hex = !rawBackground.isEmpty() && rawBackground.charAt(0) == '#' ? rawBackground.substring(1) : rawBackground;
        final int argb = hex.length() == 6
                ? (int) (0xFF000000L | Long.parseLong(hex, 16))
                : (int) Long.parseLong(hex, 16);
        builder.backgroundColor(Color.fromARGB(argb));

        return builder.build(textResolver);
    }
}
