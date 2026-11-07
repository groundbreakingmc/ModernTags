package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

public final class UpdateTeamHandler {

    private final TagManager tags;

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
        final var previousWrapper = event.getLastUsedWrapper();
        final var packet = previousWrapper instanceof WrapperPlayServerTeams teams
                ? teams : new WrapperPlayServerTeams(event);
        // Cancel before applying replacements; this packet is never forwarded or re-encoded.
        event.setCancelled(true);
        final var info = packet.getTeamInfo().orElse(null);
        this.tags.updateTeam((Channel) event.getChannel(), packet.getTeamName(), packet.getTeamMode(),
                info == null ? null : info.getColor(), packet.getPlayers());
    }
}
