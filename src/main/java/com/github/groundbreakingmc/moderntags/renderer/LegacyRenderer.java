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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.*;
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
 * can read {@link ViewerState#teamColor} for name-color resolution without
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
    private final Map<Player, TargetData> targetData = new IdentityHashMap<>(64);

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
        this.frameUpdateRate = frames.size() > 1 ? frameUpdateRate : -1;
        this.placeholdersUpdateRate = placeholdersUpdateRate;
        this.preserveTeamColor = preserveTeamColor;
        this.ignoredColors = ignoredColors;
        this.frames = frames;
    }

    public static LegacyRenderer of(@NotNull TagTextResolver tagTextResolver,
                                    @NotNull ToIntBiFunction<Player, Player> belowNameValueParser,
                                    @NotNull Component belowNameChar,
                                    @NotNull Config config) {
        final int frameUpdateRate = config.findInt("frame-update-rate");
        final int placeholdersUpdateRate = config.findInt("placeholders-update-rate");
        final boolean preserveTeamColor = config.boolOr("preserve-player-name-color", false);

        final var ignoredColors = new ReferenceOpenHashSet<NamedTextColor>();
        for (final var name : config.strListOr("ignored-colors", List.of("white"))) {
            final var color = NamedTextColor.NAMES.value(name.toLowerCase());
            if (color != null) ignoredColors.add(color);
        }

        final var frameSections = config.findSectionList("frames");
        if (frameSections.isEmpty()) {
            throw new IllegalStateException(
                    "[ModernTags] LegacyRenderer requires at least one frame under \"frames\"");
        }

        final var builder = ImmutableList.<Frame>builder();
        for (int i = 0; i < frameSections.size(); i++) {
            try {
                builder.add(Frame.parse(frameSections.get(i), tagTextResolver));
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

    /**
     * Sends a TEAM CREATE (first render) or UPDATE (re-render) packet to the viewer,
     * followed by the below-name objective if this viewer hasn't received it yet.
     * No-ops for self-tags — scoreboard teams don't support them.
     */
    @Override
    public void render(@NotNull ViewerState state) {
        final var data = this.targetData.computeIfAbsent(state.target,
                target -> new TargetData("MT-" + target.getName()));
        final var frame = this.frames.get(data.currentFrame);
        final boolean create = !data.viewers.containsKey(state.viewer);

        this.protocolManager.sendPacketSilently(state.channel,
                this.buildTeamPacket(state, data, frame, create, true));

        if (this.objectiveSentTo.add(state.viewer)) {
            this.protocolManager.sendPacketSilently(state.channel, this.buildObjectiveCreatePacket());
            this.protocolManager.sendPacketSilently(state.channel, this.buildObjectiveDisplayPacket());
        }

        this.protocolManager.sendPacketSilently(state.channel,
                this.buildUpdateScorePacket(state.target, state.viewer));

        data.viewers.put(state.viewer, state);
    }

    /**
     * Checks whether this exact state has a registered team, including hidden tags.
     *
     * @param state the target and viewer state to check
     * @return true if this state is registered for the viewer in this renderer
     */
    public boolean hasRegisteredTeam(@NotNull ViewerState state) {
        final var data = this.targetData.get(state.target);
        return data != null && data.viewers.get(state.viewer) == state;
    }

    /**
     * Sends TEAM REMOVE to the viewer and drops them from the per-target viewer map.
     */
    @Override
    public void stopRendering(@NotNull ViewerState state) {
        final var data = this.targetData.get(state.target);
        if (data == null) return;

        data.viewers.remove(state.viewer);

        this.protocolManager.sendPacketSilently(state.channel, this.buildTeamRemovePacket(data));
    }

    /**
     * Restores full nametag visibility (ALWAYS) after a temporary hide.
     */
    @Override
    public void show(@NotNull ViewerState state) {
        this.sendVisibilityUpdate(state, true);
    }

    /**
     * Sets nametag visibility to NEVER without destroying the team (temporary hide).
     */
    @Override
    public void hide(@NotNull ViewerState state) {
        this.sendVisibilityUpdate(state, false);
    }

    private void sendVisibilityUpdate(@NotNull ViewerState state, boolean showTag) {
        if (state.target == state.viewer) return;

        final var data = this.targetData.get(state.target);
        if (data == null) return;

        final var frame = this.frames.get(data.currentFrame);
        this.protocolManager.sendPacketSilently(state.channel,
                this.buildTeamPacket(state, data, frame, false, showTag));
    }

    /**
     * Advances the animation frame and broadcasts to all current viewers.
     * {@code state} is a representative state for {@code state.target}.
     */
    @Override
    public void updateFrame(@NotNull ViewerState state) {
        if (this.frames.size() <= 1) return;

        final var data = this.targetData.get(state.target);
        if (data == null || data.viewers.isEmpty()) return;

        data.currentFrame = (data.currentFrame + 1) % this.frames.size();
        this.broadcastFullUpdate(data);
    }

    /**
     * Re-resolves placeholders / name color and broadcasts to all current viewers.
     */
    @Override
    public void updatePlaceholders(@NotNull ViewerState state) {
        final var data = this.targetData.get(state.target);
        if (data == null || data.viewers.isEmpty()) return;
        this.broadcastFullUpdate(data);
    }

    /**
     * Refreshes the current text, name color and below-name score for one rendered pair.
     * Hidden pairs retain their visibility until the render loop shows them again.
     *
     * @param state the current pair whose tag is refreshed
     */
    public void updateViewer(@NotNull ViewerState state) {
        if (!state.rendered) return;

        final var data = this.targetData.get(state.target);
        if (data == null) return;

        this.sendFullUpdate(state, data, this.frames.get(data.currentFrame));
    }

    /**
     * Releases all state for {@code player} as both target and viewer.
     * As target: sends TEAM REMOVE to all current viewers.
     * As viewer: sends OBJECTIVE REMOVE and removes from all target viewer maps.
     */
    @Override
    public void cleanup(@NotNull Player player) {
        final var data = this.targetData.remove(player);
        if (data != null && !data.viewers.isEmpty()) {
            final var removeTeam = this.buildTeamRemovePacket(data);
            for (final var state : data.viewers.values()) {
                this.protocolManager.sendPacketSilently(state.channel, removeTeam);
            }
        }

        for (final var otherTarget : this.targetData.values()) {
            otherTarget.viewers.remove(player);
        }

        if (this.objectiveSentTo.remove(player)) {
            final var channel = this.protocolManager.getChannel(player.getUniqueId());
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

    /**
     * Sends a TEAM UPDATE + score packet to every active viewer.
     * Iterates {@link TargetData#viewers} so {@link ViewerState#teamColor} is available
     * for name-color resolution without any additional lookups.
     */
    private void broadcastFullUpdate(@NotNull TargetData data) {
        final var frame = this.frames.get(data.currentFrame);
        for (final var viewerState : data.viewers.values()) {
            if (!viewerState.rendered) continue;
            this.sendFullUpdate(viewerState, data, frame);
        }
    }

    private void sendFullUpdate(@NotNull ViewerState state, @NotNull TargetData data, @NotNull Frame frame) {
        this.protocolManager.sendPacketSilently(state.channel,
                this.buildTeamPacket(state, data, frame, false, true));
        this.protocolManager.sendPacketSilently(state.channel,
                this.buildUpdateScorePacket(state.target, state.viewer));
    }

    /**
     * Returns the effective name color. When {@code preserveTeamColor} is enabled,
     * prefers the resolved color stored in {@link ViewerState#teamColor} unless it is in
     * {@code ignoredColors}. Falls back to the frame default.
     *
     * <p>The color is already a {@link NamedTextColor} (resolved at packet-intercept time
     * by RenderLoop) so no further instanceof checks or casts are needed here.
     */
    private NamedTextColor resolveNameColor(@NotNull ViewerState state,
                                            @NotNull NamedTextColor frameDefault) {
        if (!this.preserveTeamColor) return frameDefault;
        final var serverColor = state.teamColor;
        if (serverColor == null) return frameDefault;
        if (this.ignoredColors.contains(serverColor)) return frameDefault;
        return serverColor;
    }

    private WrapperPlayServerTeams buildTeamPacket(@NotNull ViewerState state,
                                                   @NotNull TargetData data,
                                                   @NotNull Frame frame,
                                                   boolean create,
                                                   boolean showTag) {
        return new WrapperPlayServerTeams(
                data.teamName,
                create ? TeamMode.CREATE
                        : TeamMode.UPDATE,
                new ScoreBoardTeamInfo(
                        Component.text(data.teamName),
                        this.tagTextResolver.resolve(state, frame.compiledPrefix()),
                        this.tagTextResolver.resolve(state, frame.compiledSuffix()),
                        showTag ? NameTagVisibility.ALWAYS
                                : NameTagVisibility.NEVER,
                        CollisionRule.ALWAYS,
                        this.resolveNameColor(state, frame.nameColor()),
                        OptionData.NONE
                ),
                create ? List.of(state.target.getName()) : List.of()
        );
    }

    @SuppressWarnings("deprecation")
    private WrapperPlayServerTeams buildTeamRemovePacket(@NotNull TargetData data) {
        return new WrapperPlayServerTeams(
                data.teamName,
                TeamMode.REMOVE,
                (ScoreBoardTeamInfo) null,
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

    private static final class TargetData {

        final String teamName;
        int currentFrame = 0;

        /**
         * Active viewers mapped to their {@link ViewerState}.
         * Storing the state (not just the Player) gives {@link #broadcastFullUpdate} access
         * to {@link ViewerState#teamColor} without any extra lookups.
         */
        final Map<Player, ViewerState> viewers = new IdentityHashMap<>();

        TargetData(@NotNull String teamName) {
            this.teamName = teamName;
        }
    }

    public static final class Frame {

        private final Text<ViewerState> compiledPrefix;
        private final NamedTextColor nameColor;
        private final Text<ViewerState> compiledSuffix;

        private Frame(@NotNull Builder builder, @NotNull TagTextResolver textResolver) {
            this.compiledPrefix = textResolver.compile(builder.prefix);
            this.nameColor = builder.nameColor;
            this.compiledSuffix = textResolver.compile(builder.suffix);
        }

        static Frame parse(@NotNull Config node, @NotNull TagTextResolver textResolver) {
            return new Builder()
                    .prefix(node.findStr("prefix"))
                    .nameColor(Objects.requireNonNullElse(
                            NamedTextColor.NAMES.value(node.findStr("name-color").toLowerCase()),
                            NamedTextColor.WHITE))
                    .suffix(node.findStr("suffix"))
                    .build(textResolver);
        }

        public Text<ViewerState> compiledPrefix() {
            return this.compiledPrefix;
        }

        public NamedTextColor nameColor() {
            return this.nameColor;
        }

        public Text<ViewerState> compiledSuffix() {
            return this.compiledSuffix;
        }

        public static final class Builder {

            private String prefix = "";
            private NamedTextColor nameColor = NamedTextColor.WHITE;
            private String suffix = "";

            public Builder() {
            }

            public Builder prefix(String prefix) {
                this.prefix = prefix;
                return this;
            }

            public Builder nameColor(NamedTextColor color) {
                this.nameColor = color;
                return this;
            }

            public Builder suffix(String suffix) {
                this.suffix = suffix;
                return this;
            }

            public Frame build(@NotNull TagTextResolver textResolver) {
                return new Frame(this, textResolver);
            }
        }
    }
}
