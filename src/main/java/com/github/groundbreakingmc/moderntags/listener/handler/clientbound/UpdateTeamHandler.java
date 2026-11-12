package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class UpdateTeamHandler {

    private final TagManager tags;
    /** A team change is broadcast to every player with the same bytes; it is decoded once. */
    private final DecodedPackets<TeamChange> decoded = new DecodedPackets<>();

    /**
     * Connects source team membership and colors to the tag manager.
     */
    public UpdateTeamHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Records source colors and membership, then cancels the foreign team packet.
     * Our renderers send their replacement teams silently.
     */
    public void handle(@NotNull PacketSendEvent event) {
        final var viewer = (Channel) event.getChannel();
        if (event.getLastUsedWrapper() instanceof WrapperPlayServerTeams packet) {
            // Another listener decoded this packet and may have edited it; its bytes are stale.
            event.setCancelled(true);
            final var info = packet.getTeamInfo().orElse(null);
            this.tags.updateTeam(viewer, packet.getTeamName(), packet.getTeamMode(),
                    info == null ? null : info.getColor(), packet.getPlayers());
            return;
        }
        final var buffer = (ByteBuf) event.getByteBuf();
        final int start = buffer.readerIndex();
        final int length = buffer.readableBytes();
        var change = this.decoded.find(buffer, start, length);
        if (change == null) {
            change = TeamChange.of(new WrapperPlayServerTeams(event));
            this.decoded.remember(buffer, start, length, change);
        }
        // Cancel before applying replacements; this packet is never forwarded or re-encoded.
        event.setCancelled(true);
        this.tags.updateTeam(viewer, change.name, change.mode, change.color, change.players);
    }

    /** The parts of a team packet that tags use, detached from its wrapper so all viewers share them. */
    private record TeamChange(String name, TeamMode mode, @Nullable NamedTextColor color, List<String> players) {

        private static TeamChange of(WrapperPlayServerTeams packet) {
            final var info = packet.getTeamInfo().orElse(null);
            return new TeamChange(packet.getTeamName(), packet.getTeamMode(),
                    info == null ? null : info.getColor(), List.copyOf(packet.getPlayers()));
        }
    }
}
