package com.github.groundbreakingmc.moderntags.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore.Action;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/** Scoreboard team operations shared by legacy nametags and modern name hiding. */
public final class LegacyRenderer {

    private static final String OBJECTIVE_NAME = "ModernTags";

    private LegacyRenderer() {
    }

    /**
     * Creates a client team containing one player, in the recipient's EventLoop.
     * The caller supplies resolved text and color and records creation after return.
     * The team name must be unused for this viewer and at most 16 characters for
     * legacy compatibility. Modern tags use empty prefix/suffix and hidden names.
     */
    public static void create(@NotNull Channel viewer, @NotNull String teamName,
                               @NotNull String playerName, @NotNull Component prefix,
                               @NotNull Component suffix, @NotNull NamedTextColor color,
                               boolean visible) {
        final var info = teamInfo(prefix, suffix, color, visible);
        final var packet = new WrapperPlayServerTeams(teamName, TeamMode.CREATE, info, List.of(playerName));
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
    }

    /** Removes an existing team in the recipient's EventLoop. The caller clears its state after return. */
    public static void destroy(@NotNull Channel viewer, @NotNull String teamName) {
        final var packet = new WrapperPlayServerTeams(teamName, TeamMode.REMOVE,
                (ScoreBoardTeamInfo) null, List.of());
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
    }

    /**
     * Updates an existing team's full properties without changing membership.
     * Call in the recipient's EventLoop only when the applied properties differ.
     * Modern mode uses empty prefix/suffix and visible=false, including color updates.
     */
    public static void update(@NotNull Channel viewer, @NotNull String teamName,
                               @NotNull Component prefix, @NotNull Component suffix,
                               @NotNull NamedTextColor color, boolean visible) {
        final var info = teamInfo(prefix, suffix, color, visible);
        final var packet = new WrapperPlayServerTeams(teamName, TeamMode.UPDATE, info, List.of());
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer, packet);
    }

    /** Creates and displays the viewer's single below-name objective in its EventLoop. */
    public static void createBelowName(@NotNull Channel viewer, @NotNull Component label) {
        final var protocol = PacketEvents.getAPI().getProtocolManager();
        protocol.sendPacketSilently(viewer, new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME, ObjectiveMode.CREATE, label, RenderType.INTEGER));
        protocol.sendPacketSilently(viewer, new WrapperPlayServerDisplayScoreboard(2, OBJECTIVE_NAME));
    }

    /** Removes our objective and all its scores in the viewer's EventLoop. */
    public static void destroyBelowName(@NotNull Channel viewer) {
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer,
                new WrapperPlayServerScoreboardObjective(OBJECTIVE_NAME, ObjectiveMode.REMOVE, null, null));
    }

    /** Updates an existing objective's label without recreating its scores. */
    public static void updateBelowName(@NotNull Channel viewer, @NotNull Component label) {
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer,
                new WrapperPlayServerScoreboardObjective(OBJECTIVE_NAME, ObjectiveMode.UPDATE, label, RenderType.INTEGER));
    }

    /** Sends a resolved score for one owner to the existing below-name objective. */
    public static void updateScore(@NotNull Channel viewer, @NotNull String playerName, int value) {
        PacketEvents.getAPI().getProtocolManager().sendPacketSilently(viewer,
                new WrapperPlayServerUpdateScore(playerName, Action.CREATE_OR_UPDATE_ITEM,
                        OBJECTIVE_NAME, Optional.of(value)));
    }

    /** Removes only this owner's score in our objective, using the server's packet format. */
    public static void removeScore(@NotNull Channel viewer, @NotNull String playerName) {
        final var protocol = PacketEvents.getAPI().getProtocolManager();
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_3)) {
            protocol.sendPacketSilently(viewer, new WrapperPlayServerResetScore(playerName, OBJECTIVE_NAME));
        } else {
            protocol.sendPacketSilently(viewer, new WrapperPlayServerUpdateScore(
                    playerName, Action.REMOVE_ITEM, OBJECTIVE_NAME, Optional.empty()));
        }
    }

    private static ScoreBoardTeamInfo teamInfo(Component prefix, Component suffix,
                                               NamedTextColor color, boolean visible) {
        return new ScoreBoardTeamInfo(
                Component.empty(), prefix, suffix,
                visible ? NameTagVisibility.ALWAYS : NameTagVisibility.NEVER,
                CollisionRule.ALWAYS, color, OptionData.NONE);
    }
}
