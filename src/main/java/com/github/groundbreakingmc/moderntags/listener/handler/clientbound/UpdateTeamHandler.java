
package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Intercepts TEAMS packets and delegates all routing decisions to {@link RenderLoop}.
 * <p>
 * <h2>Design</h2>
 * <p>This handler cancels the original packet and posts a {@link RenderTask.TeamPacket}
 * containing the data required for later processing.
 * {@link RenderLoop#handleTeamPacket} performs per-player classification during drain.
 * <p>
 * <h2>Why cancel unconditionally?</h2>
 * <ul>
 *   <li><b>ModernRenderer</b> — re-sends packets with {@code nameTagVisibility=NEVER}
 *       and tracks CREATE vs UPDATE per viewer.</li>
 *   <li><b>LegacyRenderer</b> — manages its own per-player teams and suppresses
 *       conflicting server team membership.</li>
 *   <li><b>Unmanaged teams</b> — are forwarded by RenderLoop, preserving
 *       scoreboard and tab-list functionality.</li>
 * </ul>
 */
public final class UpdateTeamHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public UpdateTeamHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    /**
     * Captures the outgoing team packet for deferred processing.
     *
     * <p>Previously decoded wrappers are reused to preserve modifications
     * made by earlier listeners and avoid redundant decoding.
     *
     * @param event the outgoing team packet
     */
    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var previous = event.getLastUsedWrapper();
        final var packet = previous instanceof WrapperPlayServerTeams teams
                ? teams : new WrapperPlayServerTeams(event);

        // Capture all required data before leaving the packet callback.
        final Player viewer = event.getPlayer();
        final var teamName = packet.getTeamName();
        final var mode = packet.getTeamMode();

        // Copy packet data before leaving the Netty callback; the wrapper must not escape it.
        final var info = packet.getTeamInfo().orElse(null);

        // The original collection may be modified or reused by another listener.
        final var players = List.copyOf(packet.getPlayers());

        // Routing must finish before any original team membership reaches the viewer.
        event.setCancelled(true);

        this.renderLoop.post(new RenderTask.TeamPacket(
                viewer, teamName, mode, info, players));
    }
}
