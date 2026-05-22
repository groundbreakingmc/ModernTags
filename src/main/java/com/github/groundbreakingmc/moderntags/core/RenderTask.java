package com.github.groundbreakingmc.moderntags.core;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Sealed task hierarchy posted to {@link RenderLoop} from any thread.
 *
 * <p>All subtypes are plain data carriers — no logic, no Bukkit API calls.
 * Fields that come from the Bukkit API (entity IDs, passenger arrays, etc.)
 * are extracted at post-time by the calling thread, never inside RenderLoop.
 */
public sealed interface RenderTask permits
        RenderTask.Cleanup,
        RenderTask.InitializeAll,
        RenderTask.InvalidateAll,
        RenderTask.PassengersUpdate,
        RenderTask.Render,
        RenderTask.StopRendering,
        RenderTask.SuppressChange,
        RenderTask.TeamPacket,
        RenderTask.Tick {

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start rendering {@code target}'s tag for {@code viewer}.
     */
    record Render(@NotNull Player target, @NotNull Player viewer) implements RenderTask {}

    /**
     * Stop rendering and destroy the client-side tag entity.
     */
    record StopRendering(@NotNull Player target, @NotNull Player viewer) implements RenderTask {}

    // ── State changes ─────────────────────────────────────────────────────────

    /**
     * A suppression reason was added or removed for a (target, viewer) pair.
     *
     * <p>{@code reason} is one of {@link ViewerState#SUPPRESS_INVISIBLE},
     * {@link ViewerState#SUPPRESS_SNEAK}, or {@link ViewerState#SUPPRESS_PASSENGER}.
     * {@code add=true} sets the bit; {@code add=false} clears it.
     */
    record SuppressChange(
            @NotNull Player target,
            @NotNull Player viewer,
            byte reason,
            boolean add
    ) implements RenderTask {}

    // ── Team packet ───────────────────────────────────────────────────────────

    /**
     * A TEAMS packet was intercepted for {@code viewer} (already cancelled by the handler).
     *
     * <p>Carries the complete original packet data so {@link RenderLoop} can make
     * per-player routing decisions during drain:
     * <ul>
     *   <li><b>ModernRenderer players</b> — re-sent with {@code nameTagVisibility=NEVER} to
     *       suppress the vanilla nametag; CREATE is tracked per-viewer to avoid sending UPDATE
     *       before the client knows the team.</li>
     *   <li><b>LegacyRenderer players</b> — packet is dropped (LegacyRenderer owns their team);
     *       color/snapshot stored in {@link ViewerState#teamSnapshot} for name-color resolution.</li>
     *   <li><b>Unmanaged players</b> — packet forwarded as-is, including info-only packets
     *       (empty {@code players}) used by plugins like TAB for sidebar/tab-list display.</li>
     * </ul>
     *
     * @param viewer   the player who would have received the packet
     * @param teamName name of the scoreboard team
     * @param mode     original packet mode (CREATE, UPDATE, ADD_ENTITIES, REMOVE_ENTITIES, REMOVE)
     * @param info     team visual info; {@code null} for ADD_ENTITIES / REMOVE_ENTITIES / REMOVE
     * @param players  player names carried by the packet; empty for UPDATE / REMOVE
     */
    record TeamPacket(
            @NotNull Player viewer,
            @NotNull String teamName,
            @NotNull WrapperPlayServerTeams.TeamMode mode,
            @Nullable WrapperPlayServerTeams.ScoreBoardTeamInfo info,
            @NotNull List<String> players
    ) implements RenderTask {}

    // ── Passengers ────────────────────────────────────────────────────────────

    /**
     * Server sent a SET_PASSENGERS for {@code target} (already cancelled by the handler).
     * {@code incomingPassengers} is a safe copy. RenderLoop merges renderer-owned IDs and re-sends.
     */
    record PassengersUpdate(
            @NotNull Player target,
            @NotNull Player viewer,
            int @NotNull [] incomingPassengers
    ) implements RenderTask {}

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Remove all state where {@code player} appears as target or viewer.
     */
    record Cleanup(@NotNull Player player) implements RenderTask {}

    // ── Bulk operations ───────────────────────────────────────────────────────

    /**
     * Render all (target, viewer) pairs for all online players. Posted on enable/reload.
     */
    record InitializeAll() implements RenderTask {}

    /**
     * Stop rendering all pairs and release all state. Posted on disable/reload start.
     */
    record InvalidateAll() implements RenderTask {}

    // ── Periodic tick ─────────────────────────────────────────────────────────

    /**
     * Periodic tick for animation frames and placeholder updates.
     * {@code currentTick} is the server tick counter at post-time.
     */
    record Tick(int currentTick) implements RenderTask {}
}
