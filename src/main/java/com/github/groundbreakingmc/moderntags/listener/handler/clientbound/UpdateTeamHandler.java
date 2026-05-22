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
 *
 * <h3>Design</h3>
 * <p>This handler is intentionally thin — it cancels the packet and posts a
 * {@link RenderTask.TeamPacket} that carries the full original context.
 * {@link RenderLoop#handleTeamPacket} performs the actual per-player classification
 * during drain (drain-only access, no synchronisation needed).
 *
 * <h3>Why cancel unconditionally?</h3>
 * <ul>
 *   <li><b>ModernRenderer</b> — must re-send with {@code nameTagVisibility=NEVER}
 *       and track CREATE vs UPDATE per viewer to avoid sending UPDATE to a client
 *       that never received CREATE.</li>
 *   <li><b>LegacyRenderer</b> — manages its own per-player team; the server's team
 *       for LegacyRenderer-tracked players must be suppressed (only color info is extracted).</li>
 *   <li><b>Unmanaged / info-only packets</b> — forwarded unchanged by RenderLoop,
 *       preserving sidebar/tab-list teams sent by plugins such as TAB.</li>
 * </ul>
 */
public final class UpdateTeamHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public UpdateTeamHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerTeams(event);

        // Always cancel — RenderLoop is the sole authority on what each viewer sees.
        event.setCancelled(true);

        final Player viewer = event.getPlayer();
        final String teamName = packet.getTeamName();
        final WrapperPlayServerTeams.TeamMode mode = packet.getTeamMode();

        // Extract info and player list eagerly so we don't hold a PacketEvents wrapper reference
        // beyond the netty thread. TeamPacket is a plain data carrier.
        final WrapperPlayServerTeams.ScoreBoardTeamInfo info = packet.getTeamInfo().orElse(null);
        final List<String> players = List.copyOf(packet.getPlayers());

        this.renderLoop.post(new RenderTask.TeamPacket(viewer, teamName, mode, info, players));
    }
}
