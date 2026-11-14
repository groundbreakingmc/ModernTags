package com.github.groundbreakingmc.moderntags.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scoreboard team operations shared by legacy nametags and modern name hiding.
 * Name-hiding teams without text, the only kind modern tags use, are written straight
 * into a pooled buffer around info that PacketEvents encoded once; see {@link PacketBytes}.
 * Teams with text still go through a wrapper.
 */
public final class LegacyRenderer {

    private static final String OBJECTIVE_NAME = "ModernTags";

    private static final int TEAMS = PacketType.Play.Server.TEAMS.getId(
            PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
    private static final byte[] NO_INFO = new byte[0];

    /**
     * Encoded info of a team that hides names and has no prefix or suffix, by color.
     * Covers every named color, so a lookup never misses. The arrays are never modified.
     */
    private static final Map<NamedTextColor, byte[]> HIDDEN_INFO = hiddenInfo();

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
        final byte[] hidden = hiddenInfo(prefix, suffix, color, visible);
        if (hidden != null) {
            PacketBytes.write(viewer, teamPacket(viewer.alloc(), teamName, TeamMode.CREATE, hidden, playerName));
            return;
        }
        final var info = teamInfo(prefix, suffix, color, visible);
        final var packet = new WrapperPlayServerTeams(teamName, TeamMode.CREATE, info, List.of(playerName));
        PacketEvents.getAPI().getProtocolManager().writePacketSilently(viewer, packet);
    }

    /** Removes an existing team in the recipient's EventLoop. The caller clears its state after return. */
    public static void destroy(@NotNull Channel viewer, @NotNull String teamName) {
        PacketBytes.write(viewer, teamPacket(viewer.alloc(), teamName, TeamMode.REMOVE, NO_INFO, null));
    }

    /**
     * Updates an existing team's full properties without changing membership.
     * Call in the recipient's EventLoop only when the applied properties differ.
     * Modern mode uses empty prefix/suffix and visible=false, including color updates.
     */
    public static void update(@NotNull Channel viewer, @NotNull String teamName,
                               @NotNull Component prefix, @NotNull Component suffix,
                               @NotNull NamedTextColor color, boolean visible) {
        final byte[] hidden = hiddenInfo(prefix, suffix, color, visible);
        if (hidden != null) {
            PacketBytes.write(viewer, teamPacket(viewer.alloc(), teamName, TeamMode.UPDATE, hidden, null));
            return;
        }
        final var info = teamInfo(prefix, suffix, color, visible);
        final var packet = new WrapperPlayServerTeams(teamName, TeamMode.UPDATE, info, List.of());
        PacketEvents.getAPI().getProtocolManager().writePacketSilently(viewer, packet);
    }

    /** Creates and displays the viewer's single below-name objective in its EventLoop. */
    public static void createBelowName(@NotNull Channel viewer, @NotNull Component label) {
        final var protocol = PacketEvents.getAPI().getProtocolManager();
        protocol.writePacketSilently(viewer, new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME, ObjectiveMode.CREATE, label, RenderType.INTEGER));
        protocol.writePacketSilently(viewer, new WrapperPlayServerDisplayScoreboard(2, OBJECTIVE_NAME));
    }

    /** Removes our objective and all its scores in the viewer's EventLoop. */
    public static void destroyBelowName(@NotNull Channel viewer) {
        PacketEvents.getAPI().getProtocolManager().writePacketSilently(viewer,
                new WrapperPlayServerScoreboardObjective(OBJECTIVE_NAME, ObjectiveMode.REMOVE, null, null));
    }

    /** Updates an existing objective's label without recreating its scores. */
    public static void updateBelowName(@NotNull Channel viewer, @NotNull Component label) {
        PacketEvents.getAPI().getProtocolManager().writePacketSilently(viewer,
                new WrapperPlayServerScoreboardObjective(OBJECTIVE_NAME, ObjectiveMode.UPDATE, label, RenderType.INTEGER));
    }

    /** Sends a resolved score for one owner to the existing below-name objective. */
    public static void updateScore(@NotNull Channel viewer, @NotNull String playerName, int value) {
        PacketEvents.getAPI().getProtocolManager().writePacketSilently(viewer,
                new WrapperPlayServerUpdateScore(playerName, Action.CREATE_OR_UPDATE_ITEM,
                        OBJECTIVE_NAME, Optional.of(value)));
    }

    /** Removes only this owner's score in our objective, using the server's packet format. */
    public static void removeScore(@NotNull Channel viewer, @NotNull String playerName) {
        final var protocol = PacketEvents.getAPI().getProtocolManager();
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_3)) {
            protocol.writePacketSilently(viewer, new WrapperPlayServerResetScore(playerName, OBJECTIVE_NAME));
        } else {
            protocol.writePacketSilently(viewer, new WrapperPlayServerUpdateScore(
                    playerName, Action.REMOVE_ITEM, OBJECTIVE_NAME, Optional.empty()));
        }
    }

    /**
     * Compares each natively written team packet with PacketEvents' encoding of the same values.
     * Called once at startup, before any connection is served: a server protocol that lays
     * these packets out differently must stop the plugin there rather than corrupt packets.
     *
     * @throws IllegalStateException when a layout differs
     */
    static void verify() {
        final var allocator = UnpooledByteBufAllocator.DEFAULT;
        final var color = NamedTextColor.RED;
        final byte[] hidden = HIDDEN_INFO.get(color);
        final var info = teamInfo(Component.empty(), Component.empty(), color, false);
        // A member outside ASCII also checks that the length prefix counts UTF-8 bytes.
        final String team = "MT-12c";
        final String member = "\u0418\u0433\u0440\u043e\u043a_1";
        PacketBytes.expect(new WrapperPlayServerTeams(team, TeamMode.CREATE, info, List.of(member)),
                teamPacket(allocator, team, TeamMode.CREATE, hidden, member));
        PacketBytes.expect(new WrapperPlayServerTeams(team, TeamMode.UPDATE, info, List.of()),
                teamPacket(allocator, team, TeamMode.UPDATE, hidden, null));
        PacketBytes.expect(new WrapperPlayServerTeams(team, TeamMode.REMOVE, (ScoreBoardTeamInfo) null, List.of()),
                teamPacket(allocator, team, TeamMode.REMOVE, NO_INFO, null));
    }

    /** Writes a team packet: name, mode, then the info and the single member its mode carries. */
    private static ByteBuf teamPacket(ByteBufAllocator allocator, String teamName, TeamMode mode,
                                      byte[] info, @Nullable String member) {
        final ByteBuf buffer = allocator.buffer(64 + info.length);
        ByteBufHelper.writeVarInt(buffer, TEAMS);
        writeString(buffer, teamName);
        buffer.writeByte(mode.ordinal());
        buffer.writeBytes(info);
        if (member != null) {
            ByteBufHelper.writeVarInt(buffer, 1);
            writeString(buffer, member);
        }
        return buffer;
    }

    /**
     * Writes a protocol string. Team names and vanilla player names are ASCII and are written
     * byte by byte: Netty's UTF-8 writer creates a temporary ByteBuffer per pooled direct buffer.
     */
    private static void writeString(ByteBuf buffer, String value) {
        final int length = value.length();
        int ascii = 0;
        while (ascii < length && value.charAt(ascii) < 0x80) ascii++;
        if (ascii == length) {
            ByteBufHelper.writeVarInt(buffer, length);
            for (int i = 0; i < length; i++) buffer.writeByte(value.charAt(i));
        } else {
            final byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            ByteBufHelper.writeVarInt(buffer, encoded.length);
            buffer.writeBytes(encoded);
        }
    }

    /**
     * Returns the prepared info for a name-hiding team without text, or null when the team
     * needs a wrapper. {@link Component#empty()} is one shared instance, so no text is compared.
     */
    private static byte @Nullable [] hiddenInfo(Component prefix, Component suffix,
                                                NamedTextColor color, boolean visible) {
        return visible || prefix != Component.empty() || suffix != Component.empty()
                ? null : HIDDEN_INFO.get(color);
    }

    private static Map<NamedTextColor, byte[]> hiddenInfo() {
        final var encoded = new IdentityHashMap<NamedTextColor, byte[]>();
        // An UPDATE packet ends with its info, after the packet ID, a one-byte name and the mode;
        // verify() checks the result.
        final int head = ByteBufHelper.getByteSize(TEAMS) + 3;
        for (final var color : NamedTextColor.NAMES.values()) {
            final byte[] packet = PacketBytes.of(new WrapperPlayServerTeams("-", TeamMode.UPDATE,
                    teamInfo(Component.empty(), Component.empty(), color, false), List.of()));
            encoded.put(color, Arrays.copyOfRange(packet, Math.min(head, packet.length), packet.length));
        }
        return encoded;
    }

    private static ScoreBoardTeamInfo teamInfo(Component prefix, Component suffix,
                                               NamedTextColor color, boolean visible) {
        return new ScoreBoardTeamInfo(
                Component.empty(), prefix, suffix,
                visible ? NameTagVisibility.ALWAYS : NameTagVisibility.NEVER,
                CollisionRule.ALWAYS, color, OptionData.NONE);
    }
}
