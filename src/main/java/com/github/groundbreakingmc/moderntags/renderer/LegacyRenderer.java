package com.github.groundbreakingmc.moderntags.renderer;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.groundbreakingmc.mylib.config.Config;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.ToIntBiFunction;

import static com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode;
import static com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType;

/**
 * Renderer that uses Scoreboard TEAMS packets for the nametag and a below-name
 * objective for health or custom placeholder values.
 *
 * <p>Per-target state (team name, current frame, active viewers) is stored in
 * {@link #targetData}, keyed by Player reference. Viewers are mapped to their
 * {@link ViewerState} — not just a bare Set — so {@link #broadcastFullUpdate}
 * can read {@link ViewerState#teamSnapshot} for name-color resolution without
 * creating synthetic state objects.
 *
 * <p>The below-name objective is sent once per viewer lifetime and tracked in
 * {@link #objectiveSentTo}. All fields are accessed during drain only (single writer,
 * no synchronisation needed).
 */
public final class LegacyRenderer implements TagRenderer {

    private static final String OBJECTIVE_NAME = "ModernTags";

    private final TagTextResolver tagTextResolver;
    private final ToIntBiFunction<Player, Player> belowNameValueParser;
    private final Component belowNameChar;
    private final int frameUpdateRate;
    private final int placeholdersUpdateRate;
    private final boolean preserveTeamColor;
    private final Set<NamedTextColor> ignoredColors;
    private final List<Frame> frames;

    /**
     * Per-target state. Plain map — drain-only access (single writer, no sync).
     */
    private final IdentityHashMap<Player, TargetData> targetData = new IdentityHashMap<>(64);

    /**
     * Tracks viewers that have already received the below-name objective packets.
     * Per-renderer, drain-only.
     */
    private final Set<Player> objectiveSentTo = Collections.newSetFromMap(new IdentityHashMap<>(64));

    private final ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

    private LegacyRenderer(@NotNull TagTextResolver tagTextResolver,
                           @NotNull ToIntBiFunction<Player, Player> belowNameValueParser,
                           @NotNull Component belowNameChar,
                           int frameUpdateRate,
                           int placeholdersUpdateRate,
                           boolean preserveTeamColor,
                           @NotNull Set<NamedTextColor> ignoredColors,
                           @NotNull List<Frame> frames) {
        this.tagTextResolver = tagTextResolver;
        this.belowNameValueParser = belowNameValueParser;
        this.belowNameChar = belowNameChar;
        this.frameUpdateRate = frameUpdateRate;
        this.placeholdersUpdateRate = placeholdersUpdateRate;
        this.preserveTeamColor = preserveTeamColor;
        this.ignoredColors = ignoredColors;
        this.frames = frames;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static LegacyRenderer of(@NotNull TagTextResolver tagTextResolver,
                                    @NotNull ToIntBiFunction<Player, Player> belowNameValueParser,
                                    @NotNull Component belowNameChar,
                                    @NotNull Config config) {
        final int frameUpdateRate = config.findInt("frame-update-rate");
        final int placeholdersUpdateRate = config.findInt("placeholders-update-rate");
        final boolean preserveTeamColor = config.boolOr("preserve-player-name-color", false);

        final Set<NamedTextColor> ignoredColors = new ReferenceOpenHashSet<>();
        for (final String name : config.strListOr("ignored-colors", List.of("white"))) {
            final NamedTextColor color = NamedTextColor.NAMES.value(name.toLowerCase());
            if (color != null) ignoredColors.add(color);
        }

        final List<Config> frameSections = config.findSectionList("frames");
        if (frameSections.isEmpty()) {
            throw new IllegalStateException(
                    "[ModernTags] LegacyRenderer requires at least one frame under \"frames\"");
        }

        final ImmutableList.Builder<Frame> builder = ImmutableList.builder();
        for (int i = 0; i < frameSections.size(); i++) {
            try {
                builder.add(Frame.parse(frameSections.get(i)));
            } catch (final Exception ex) {
                throw new RuntimeException(
                        "[ModernTags] LegacyRenderer: failed to parse frames[" + i + "]", ex);
            }
        }

        return new LegacyRenderer(
                tagTextResolver, belowNameValueParser, belowNameChar,
                frameUpdateRate, placeholdersUpdateRate,
                preserveTeamColor, ignoredColors, builder.build()
        );
    }

    // ── TagRenderer ───────────────────────────────────────────────────────────

    /**
     * Sends a TEAM CREATE (first render) or UPDATE (re-render) packet to the viewer,
     * followed by the below-name objective if this viewer hasn't received it yet.
     * No-ops for self-tags — scoreboard teams don't support them.
     */
    @Override
    public void render(@NotNull ViewerState state) {
        if (state.target == state.viewer) return;

        final TargetData data = this.getOrCreate(state.target);
        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        final Frame frame = this.frames.get(data.currentFrame);
        final boolean create = !data.viewers.containsKey(state.viewer);

        this.protocolManager.sendPacketSilently(channel,
                this.buildTeamPacket(state, data, frame, create, true));

        if (this.objectiveSentTo.add(state.viewer)) {
            this.protocolManager.sendPacketSilently(channel, this.buildObjectiveCreatePacket());
            this.protocolManager.sendPacketSilently(channel, this.buildObjectiveDisplayPacket());
        }

        this.protocolManager.sendPacketSilently(channel,
                this.buildUpdateScorePacket(state.target, state.viewer));

        data.viewers.put(state.viewer, state);
    }

    /**
     * Sends TEAM REMOVE to the viewer and drops them from the per-target viewer map.
     */
    @Override
    public void stopRendering(@NotNull ViewerState state) {
        if (state.target == state.viewer) return;

        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;

        data.viewers.remove(state.viewer);

        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        this.protocolManager.sendPacketSilently(channel, this.buildTeamRemovePacket(data));
    }

    /**
     * Restores full nametag visibility (ALWAYS) after a temporary hide.
     */
    @Override
    public void show(@NotNull ViewerState state) {
        if (state.target == state.viewer) return;

        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;

        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        final Frame frame = this.frames.get(data.currentFrame);
        this.protocolManager.sendPacketSilently(channel,
                this.buildTeamPacket(state, data, frame, false, true));
    }

    /**
     * Sets nametag visibility to NEVER without destroying the team (temporary hide).
     */
    @Override
    public void hide(@NotNull ViewerState state) {
        if (state.target == state.viewer) return;

        final TargetData data = this.targetData.get(state.target);
        if (data == null) return;

        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;

        final Frame frame = this.frames.get(data.currentFrame);
        this.protocolManager.sendPacketSilently(channel,
                this.buildTeamPacket(state, data, frame, false, false));
    }

    /**
     * Advances the animation frame and broadcasts to all current viewers.
     * {@code state} is a representative state for {@code state.target}.
     */
    @Override
    public void updateFrame(@NotNull ViewerState state) {
        if (this.frames.size() <= 1) return;

        final TargetData data = this.targetData.get(state.target);
        if (data == null || data.viewers.isEmpty()) return;

        data.currentFrame = (data.currentFrame + 1) % this.frames.size();
        this.broadcastFullUpdate(state.target, data);
    }

    /**
     * Re-resolves placeholders / name color and broadcasts to all current viewers.
     */
    @Override
    public void updatePlaceholders(@NotNull ViewerState state) {
        final TargetData data = this.targetData.get(state.target);
        if (data == null || data.viewers.isEmpty()) return;
        this.broadcastFullUpdate(state.target, data);
    }

    /**
     * Releases all state for {@code player} as both target and viewer.
     * As target: sends TEAM REMOVE to all current viewers.
     * As viewer: sends OBJECTIVE REMOVE and removes from all target viewer maps.
     */
    @Override
    public void cleanup(@NotNull Player player) {
        final TargetData data = this.targetData.remove(player);
        if (data != null && !data.viewers.isEmpty()) {
            final var removeTeam = this.buildTeamRemovePacket(data);
            for (final Player viewer : data.viewers.keySet()) {
                final Object channel = this.protocolManager.getChannel(viewer.getUniqueId());
                if (channel != null) {
                    this.protocolManager.sendPacketSilently(channel, removeTeam);
                }
            }
        }

        for (final TargetData td : this.targetData.values()) {
            td.viewers.remove(player);
        }

        if (this.objectiveSentTo.remove(player)) {
            final Object channel = this.protocolManager.getChannel(player.getUniqueId());
            if (channel != null) {
                this.protocolManager.sendPacketSilently(channel, this.buildObjectiveRemovePacket());
            }
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

    // ── Broadcast ─────────────────────────────────────────────────────────────

    /**
     * Sends a TEAM UPDATE + score packet to every active viewer.
     * Iterates {@link TargetData#viewers} so {@link ViewerState#teamSnapshot} is available
     * for name-color resolution without creating synthetic state objects.
     */
    private void broadcastFullUpdate(@NotNull Player target, @NotNull TargetData data) {
        final Frame frame = this.frames.get(data.currentFrame);
        for (final ViewerState viewerState : data.viewers.values()) {
            final Object channel = this.protocolManager.getChannel(viewerState.viewer.getUniqueId());
            if (channel == null) continue;
            this.protocolManager.sendPacketSilently(channel,
                    this.buildTeamPacket(viewerState, data, frame, false, true));
            this.protocolManager.sendPacketSilently(channel,
                    this.buildUpdateScorePacket(target, viewerState.viewer));
        }
    }

    // ── Color resolution ──────────────────────────────────────────────────────

    /**
     * Returns the effective name color. When {@code preserveTeamColor} is enabled,
     * prefers the color from {@link ViewerState#teamSnapshot} unless it is in
     * {@code ignoredColors}. Falls back to the frame default.
     */
    private NamedTextColor resolveNameColor(@NotNull ViewerState state,
                                            @NotNull NamedTextColor frameDefault) {
        if (!this.preserveTeamColor) return frameDefault;
        final ViewerState.TeamSnapshot snapshot = state.teamSnapshot;
        if (snapshot == null) return frameDefault;
        if (!(snapshot.color() instanceof NamedTextColor serverColor)) return frameDefault;
        if (this.ignoredColors.contains(serverColor)) return frameDefault;
        return serverColor;
    }

    // ── Packet builders ───────────────────────────────────────────────────────

    private WrapperPlayServerTeams buildTeamPacket(@NotNull ViewerState state,
                                                   @NotNull TargetData data,
                                                   @NotNull Frame frame,
                                                   boolean create,
                                                   boolean showTag) {
        return new WrapperPlayServerTeams(
                data.teamName,
                create ? WrapperPlayServerTeams.TeamMode.CREATE
                        : WrapperPlayServerTeams.TeamMode.UPDATE,
                new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                        Component.text(data.teamName),
                        this.tagTextResolver.resolve(state.target, state.viewer, frame.compiledPrefix()),
                        this.tagTextResolver.resolve(state.target, state.viewer, frame.compiledSuffix()),
                        showTag ? WrapperPlayServerTeams.NameTagVisibility.ALWAYS
                                : WrapperPlayServerTeams.NameTagVisibility.NEVER,
                        WrapperPlayServerTeams.CollisionRule.ALWAYS,
                        this.resolveNameColor(state, frame.nameColor()),
                        WrapperPlayServerTeams.OptionData.NONE
                ),
                List.of(state.target.getName())
        );
    }

    @SuppressWarnings("deprecation")
    private WrapperPlayServerTeams buildTeamRemovePacket(@NotNull TargetData data) {
        return new WrapperPlayServerTeams(
                data.teamName,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                (Collection<String>) null
        );
    }

    private WrapperPlayServerScoreboardObjective buildObjectiveCreatePacket() {
        return new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME, ObjectiveMode.CREATE, this.belowNameChar, RenderType.INTEGER);
    }

    private WrapperPlayServerScoreboardObjective buildObjectiveRemovePacket() {
        return new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME, ObjectiveMode.REMOVE, null, null);
    }

    private WrapperPlayServerDisplayScoreboard buildObjectiveDisplayPacket() {
        return new WrapperPlayServerDisplayScoreboard(2 /* below name */, OBJECTIVE_NAME);
    }

    private WrapperPlayServerUpdateScore buildUpdateScorePacket(@NotNull Player target,
                                                                @NotNull Player viewer) {
        return new WrapperPlayServerUpdateScore(
                target.getName(),
                WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                OBJECTIVE_NAME,
                Optional.of(this.belowNameValueParser.applyAsInt(target, viewer))
        );
    }

    // ── Per-target state ──────────────────────────────────────────────────────

    @NotNull
    private TargetData getOrCreate(@NotNull Player target) {
        return this.targetData.computeIfAbsent(target, k -> new TargetData("MT-" + k.getName()));
    }

    private static final class TargetData {

        final String teamName;
        int currentFrame = 0;

        /**
         * Active viewers mapped to their {@link ViewerState}.
         * Storing the state (not just the Player) gives {@link #broadcastFullUpdate} access
         * to {@link ViewerState#teamSnapshot} without any extra lookups.
         */
        final Map<Player, ViewerState> viewers = new IdentityHashMap<>();

        TargetData(@NotNull String teamName) {
            this.teamName = teamName;
        }
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    public static final class Frame {

        private final Text compiledPrefix;
        private final NamedTextColor nameColor;
        private final Text compiledSuffix;

        private Frame(@NotNull Builder builder) {
            this.compiledPrefix = Text.cacheableOf(builder.prefix);
            this.nameColor = builder.nameColor;
            this.compiledSuffix = Text.cacheableOf(builder.suffix);
        }

        static Frame parse(@NotNull Config node) {
            return new Builder()
                    .prefix(node.findStr("prefix"))
                    .nameColor(Objects.requireNonNullElse(
                            NamedTextColor.NAMES.value(node.findStr("name-color").toLowerCase()),
                            NamedTextColor.WHITE))
                    .suffix(node.findStr("suffix"))
                    .build();
        }

        public Text compiledPrefix() {
            return this.compiledPrefix;
        }

        public NamedTextColor nameColor() {
            return this.nameColor;
        }

        public Text compiledSuffix() {
            return this.compiledSuffix;
        }

        public static final class Builder {

            private String prefix = "";
            private NamedTextColor nameColor = NamedTextColor.WHITE;
            private String suffix = "";

            public Builder prefix(String v) {
                this.prefix = v;
                return this;
            }

            public Builder nameColor(NamedTextColor v) {
                this.nameColor = v;
                return this;
            }

            public Builder suffix(String v) {
                this.suffix = v;
                return this;
            }

            public Frame build() {
                return new Frame(this);
            }
        }
    }
}
