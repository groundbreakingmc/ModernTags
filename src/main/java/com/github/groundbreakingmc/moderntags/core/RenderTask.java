package com.github.groundbreakingmc.moderntags.core;

import com.github.groundbreakingmc.moderntags.renderer.ModernRenderer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sealed task hierarchy posted to {@link RenderLoop} from any thread.
 *
 * <p>Tasks carry events and delayed-operation tokens. Mutable rendering state is
 * accessed only during drain; delayed callbacks publish readiness and post their token.
 */
public sealed interface RenderTask permits
        RenderTask.AttachTag,
        RenderTask.Cleanup,
        RenderTask.GameModeUpdate,
        RenderTask.InitializeAll,
        RenderTask.InvalidateAll,
        RenderTask.PassengersUpdate,
        RenderTask.Render,
        RenderTask.RenderIfTracked,
        RenderTask.ScheduleRender,
        RenderTask.RenderWhenCurrent,
        RenderTask.StopTracking,
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

    /** Refreshes an existing tracked pair; tab-list membership alone does not establish tracking. */
    record RenderIfTracked(@NotNull Player target, @NotNull Player viewer) implements RenderTask {}

    /**
     * Refreshes tracked tag visibility after a player's game mode changes.
     * A viewer's own update also refreshes the active tags they can see.
     *
     * @param target the player whose game mode changed
     * @param viewer the recipient of the game-mode update
     */
    record GameModeUpdate(@NotNull Player target, @NotNull Player viewer) implements RenderTask {}

    /** Registers a native player spawn before scheduling its delayed tag render. */
    record ScheduleRender(@NotNull Player target, @NotNull Player viewer,
                          @NotNull Plugin plugin) implements RenderTask {}

    /** The readiness marker is the only field published by the scheduler callback. */
    record RenderWhenCurrent(@NotNull ViewerState state, long generation,
                             @NotNull AtomicBoolean ready) implements RenderTask {
        public RenderWhenCurrent(@NotNull ViewerState state, long generation) {
            this(state, generation, new AtomicBoolean());
        }
    }

    /** A delayed attachment is valid only for the renderer and generation that spawned it. */
    record AttachTag(@NotNull ViewerState state, long generation,
                     @NotNull ModernRenderer renderer, int tagEntityId, @NotNull Object channel,
                     @NotNull AtomicBoolean ready) implements RenderTask {
        public AttachTag(@NotNull ViewerState state, long generation,
                         @NotNull ModernRenderer renderer, int tagEntityId, @NotNull Object channel) {
            this(state, generation, renderer, tagEntityId, channel, new AtomicBoolean());
        }
    }

    /** Ends native player tracking and cancels rendering, including a pending spawn. */
    record StopTracking(@NotNull Player target, @NotNull Player viewer) implements RenderTask {}

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
     * Stops rendering all pairs. Reload retains client teams so vanilla names stay hidden.
     *
     * @param shutdown whether to remove fallback teams and release the team registry on disable
     */
    record InvalidateAll(boolean shutdown) implements RenderTask {}

    // ── Periodic tick ─────────────────────────────────────────────────────────

    /**
     * Periodic tick for animation frames and placeholder updates.
     * {@code currentTick} is the server tick counter at post-time.
     */
    record Tick(int currentTick) implements RenderTask {}
}
