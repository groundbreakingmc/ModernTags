package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts TEAMS packets and delegates per-viewer routing to {@link RenderLoop}.
 * Only CREATE and UPDATE modes carry visual data; other modes are dropped after cancellation.
 */
public final class UpdateTeamHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public UpdateTeamHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerTeams(event);

        // Always cancel — RenderLoop decides what to re-send per viewer.
        event.setCancelled(true);

        final WrapperPlayServerTeams.TeamMode mode = packet.getTeamMode();
        if (mode != WrapperPlayServerTeams.TeamMode.CREATE
                && mode != WrapperPlayServerTeams.TeamMode.UPDATE) {
            return;
        }

        final WrapperPlayServerTeams.ScoreBoardTeamInfo info = packet.getTeamInfo().orElse(null);
        if (info == null) return;

        final Player viewer = event.getPlayer();
        final String teamName = packet.getTeamName();

        for (final String playerName : packet.getPlayers()) {
            final Player target = Bukkit.getPlayerExact(playerName);
            if (target != null) {
                this.renderLoop.post(new RenderTask.TeamUpdate(target, viewer, teamName, info));
            }
        }
    }
}
