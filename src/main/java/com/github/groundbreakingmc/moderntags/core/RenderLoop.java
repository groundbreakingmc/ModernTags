package com.github.groundbreakingmc.moderntags.core;

import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
import com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer;
import com.github.groundbreakingmc.moderntags.renderer.ModernRenderer;
import com.github.groundbreakingmc.moderntags.renderer.TagRenderer;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jctools.queues.MpscArrayQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single owner of all {@link ViewerState} objects and the only writer to them.
 * <p>
 * <h3>Threading model</h3>
 * <p>Any thread may call {@link #post(RenderTask)} — it is lock-free (MPSC offer).
 * Drain runs on whichever thread wins the {@link #draining} CAS. All state
 * ({@link #states}, renderer internals) is accessed exclusively during drain, so
 * <b>no synchronisation is needed anywhere inside the process methods</b>.
 * <p>
 * <h3>Key encoding</h3>
 * <p>Map keys are {@code long}: {@code (target.entityId << 32) | viewer.entityId}.
 * Entity IDs are unique 32-bit ints for the duration of a session, so there are no
 * collisions. This avoids boxing and object allocation on the hot path.
 * <p>
 * <h3>Team packet routing</h3>
 * <p>All TEAMS packets are cancelled by {@link com.github.groundbreakingmc.moderntags.listener.handler.clientbound.UpdateTeamHandler}
 * and posted as {@link RenderTask.TeamPacket}. During drain, {@link #handleTeamPacket} classifies
 * each player in the packet into one of three buckets:
 * <ol>
 *   <li><b>ModernRenderer</b> — re-sent with {@code nameTagVisibility=NEVER}. The first send
 *       uses {@code TeamMode.CREATE}; {@link #clientTeams} tracks external team existence and
 *       membership independently of renderer state so later updates and removals are valid.</li>
 *   <li><b>LegacyRenderer</b> — packet suppressed (LegacyRenderer owns the player's team);
 *       color stored in {@link ViewerState#teamColor} for {@code preserve-player-name-color}.</li>
 *   <li><b>Unmanaged / info-only</b> — forwarded unchanged, preserving sidebar and tab-list teams
 *       sent by plugins such as TAB (including empty-player-list packets).</li>
 * </ol>
 */
public final class RenderLoop {

    /**
     * Must be a power of 2. 4096 covers burst traffic on large servers.
     */
    private static final int QUEUE_CAPACITY = 4096;

    private static final String HIDDEN_TEAM_PREFIX = "MT-H-";
    private static final WrapperPlayServerTeams.ScoreBoardTeamInfo HIDDEN_TEAM_INFO =
            new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                    Component.empty(), Component.empty(), Component.empty(),
                    WrapperPlayServerTeams.NameTagVisibility.NEVER,
                    WrapperPlayServerTeams.CollisionRule.ALWAYS,
                    NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE);

    private final MpscArrayQueue<RenderTask> queue = new MpscArrayQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final Logger logger;

    /**
     * (target.entityId << 32) | viewer.entityId → ViewerState.
     * Plain map — only accessed during drain.
     */
    private final Long2ObjectOpenHashMap<ViewerState> states = new Long2ObjectOpenHashMap<>(256);

    /**
     * viewer.entityId → list of keys where that viewer appears as the <em>viewer</em> half.
     * Enables O(viewers) cleanup instead of a full-map scan.
     */
    private final Int2ObjectOpenHashMap<LongArrayList> viewerIndex = new Int2ObjectOpenHashMap<>(64);

    /**
     * target.entityId → list of keys where that player appears as the <em>target</em> half.
     * Symmetric counterpart to {@link #viewerIndex}; eliminates the O(n) state-map scan
     * that was previously required when a target player disconnects.
     */
    private final Int2ObjectOpenHashMap<LongArrayList> targetIndex = new Int2ObjectOpenHashMap<>(64);

    /**
     * viewer.entityId → teamName → set of playerNames currently in that team.
     *
     * <p>Maintained exclusively from TEAMS packets so RenderLoop can look up members
     * when a TEAMS REMOVE arrives (which carries no player list in the protocol).
     *
     * <p>Drain-only access — no synchronisation needed.
     */
    private final Int2ObjectOpenHashMap<Map<String, Set<String>>> teamRegistry =
            new Int2ObjectOpenHashMap<>(64);

    /**
     * External and fallback teams actually sent to each viewer.
     * Unlike renderer state, these survive reload while the client keeps its scoreboard.
     * Legacy team creation removes its target from this membership ledger.
     * Drain-only access.
     */
    private final Int2ObjectOpenHashMap<ClientTeamView> clientTeams =
            new Int2ObjectOpenHashMap<>(64);

    // ── Per-call reusable buffers (drain-only, never escape handleTeamPacket) ─────────────────

    /**
     * Reused across every {@link #handleTeamPacket} call to avoid allocating three
     * {@code ArrayList} instances per intercepted TEAMS packet.
     * Safe because drain is single-threaded and these lists are consumed before the
     * method returns (they are never stored or passed to code that retains them).
     */
    private final ArrayList<String> reuseModernNames = new ArrayList<>();
    private final ArrayList<ViewerState> reuseLegacyStates = new ArrayList<>();
    private final ArrayList<String> reuseUnmanagedNames = new ArrayList<>();

    /**
     * Targets visited per renderer during a tick. Sets are reused between ticks
     * and cleared after each traversal so they do not retain players.
     */
    private final Map<TagRenderer, Set<Player>> tickSeen = new IdentityHashMap<>(32);

    private final ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

    private ImmutableList<TagGroup> groups = ImmutableList.of();
    private boolean hideTagWhenHasPassenger = true;
    private int conditionsRecheckRate = 20;

    public RenderLoop(@NotNull Logger logger) {
        this.logger = logger;
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public void groups(@NotNull List<TagGroup> groups) {
        this.groups = ImmutableList.copyOf(groups);
    }

    public void hideTagWhenHasPassenger(boolean v) {
        this.hideTagWhenHasPassenger = v;
    }

    public void conditionsRecheckRate(int rate) {
        this.conditionsRecheckRate = rate;
    }

    // ── Public API (any thread) ───────────────────────────────────────────────

    /**
     * Enqueues a task and attempts to drain the queue on the calling thread.
     * Tasks are dropped when the queue is full (capacity {@value QUEUE_CAPACITY}).
     */
    public void post(@NotNull RenderTask task) {
        this.queue.offer(task);
        this.tryDrain();
    }

    // ── Drain ─────────────────────────────────────────────────────────────────

    private void tryDrain() {
        do {
            if (!this.draining.compareAndSet(false, true)) {
                return;
            }

            try {
                RenderTask task;
                while ((task = this.queue.poll()) != null) {
                    try {
                        this.processTask(task);
                    } catch (RuntimeException ex) {
                        this.logger.log(Level.SEVERE,
                                "Failed to process render task: " + task, ex);
                    }
                }
            } finally {
                this.draining.set(false);
            }
        } while (!this.queue.isEmpty());
    }

    // ── Task dispatch ─────────────────────────────────────────────────────────

    private void processTask(@NotNull RenderTask task) {
        switch (task) {
            case RenderTask.Render(var target, var viewer) -> this.handleRender(target, viewer);
            case RenderTask.RenderIfTracked(var target, var viewer) -> this.handleRenderIfTracked(target, viewer);
            case RenderTask.GameModeUpdate(var target, var viewer) -> this.handleGameModeUpdate(target, viewer);
            case RenderTask.AttachTag a -> this.handleAttach(a);
            case RenderTask.ScheduleRender s -> this.handleScheduleRender(s);
            case RenderTask.RenderWhenCurrent r -> this.handleRenderWhenCurrent(r);
            case RenderTask.StopTracking(var target, var viewer) -> this.handleStopTracking(target, viewer);
            case RenderTask.StopRendering(var target, var viewer) -> this.handleStop(target, viewer);
            case RenderTask.SuppressChange s -> this.handleSuppress(s);
            case RenderTask.TeamPacket t -> {
                try {
                    this.handleTeamPacket(t);
                } finally {
                    // Pair references belong to this TEAM task, including failed sends.
                    this.reuseLegacyStates.clear();
                }
            }
            case RenderTask.PassengersUpdate p -> this.handlePassengers(p);
            case RenderTask.Cleanup(var player) -> this.handleCleanup(player);
            case RenderTask.InitializeAll() -> this.handleInitializeAll();
            case RenderTask.InvalidateAll(var shutdown) -> this.handleInvalidateAll(shutdown);
            case RenderTask.Tick(var tick) -> this.handleTick(tick);
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void handleGameModeUpdate(@NotNull Player target, @NotNull Player viewer) {
        final ViewerState state = this.states.get(key(target, viewer));
        if (state != null) {
            // A game-mode change can also change the target's native invisibility.
            if (target.isInvisible()) state.addSuppress(ViewerState.SUPPRESS_INVISIBLE);
            else state.removeSuppress(ViewerState.SUPPRESS_INVISIBLE);
        }
        this.handleRenderIfTracked(target, viewer);

        if (target != viewer) return;
        final LongArrayList viewerKeys = this.viewerIndex.get(viewer.getEntityId());
        if (viewerKeys == null) return;
        for (int i = 0; i < viewerKeys.size(); i++) {
            final ViewerState viewed = this.states.get(viewerKeys.getLong(i));
            if (viewed != null && viewed.active && viewed.target != viewer) {
                this.applyCurrentState(viewed);
            }
        }
    }

    private void handleRenderIfTracked(@NotNull Player target, @NotNull Player viewer) {
        if (target == viewer) {
            this.handleRender(target, viewer);
            return;
        }
        final ViewerState state = this.states.get(key(target, viewer));
        if (state == null || !state.tracked) return;
        if (state.pendingSpawn != null) {
            this.handleRenderWhenCurrent(state.pendingSpawn);
            return;
        }
        this.handleRender(target, viewer);
    }

    private void handleScheduleRender(@NotNull RenderTask.ScheduleRender spawn) {
        final ViewerState state = this.getOrCreate(spawn.target(), spawn.viewer());
        this.handleStop(spawn.target(), spawn.viewer());
        state.tracked = true;
        final RenderTask.RenderWhenCurrent pending = new RenderTask.RenderWhenCurrent(
                state, state.renderGeneration);
        state.pendingSpawn = pending;
        try {
            Bukkit.getAsyncScheduler().runDelayed(spawn.plugin(), task -> {
                pending.ready().set(true);
                this.post(pending);
            }, 1L, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            state.tracked = false;
            this.invalidateRender(state);
            throw ex;
        }
    }

    private void handleRenderWhenCurrent(@NotNull RenderTask.RenderWhenCurrent pending) {
        final ViewerState state = pending.state();
        if (this.states.get(key(state.target, state.viewer)) != state
                || state.pendingSpawn != pending || !pending.ready().get()
                || state.renderGeneration != pending.generation() || !state.tracked) return;
        state.pendingSpawn = null;
        this.handleRender(state.target, state.viewer);
    }

    private void handleAttach(@NotNull RenderTask.AttachTag attachment) {
        final ViewerState state = attachment.state();
        if (this.states.get(key(state.target, state.viewer)) != state
                || state.pendingAttach != attachment || !attachment.ready().get()
                || state.renderGeneration != attachment.generation()
                || !state.active || !state.rendered || state.renderer != attachment.renderer()) return;
        state.pendingAttach = null;
        attachment.renderer().attachPassengers(state, attachment.tagEntityId(), attachment.channel());
    }

    private void handleStopTracking(@NotNull Player target, @NotNull Player viewer) {
        final ViewerState state = this.states.get(key(target, viewer));
        if (state != null) {
            state.tracked = false;
            this.handleStop(target, viewer);
        }
        this.removeFromHiddenTeam(target, viewer);
    }

    private void invalidateRender(@NotNull ViewerState state) {
        state.renderGeneration++;
        state.pendingSpawn = null;
        state.pendingAttach = null;
    }

    private void handleRender(@NotNull Player target, @NotNull Player viewer) {
        final ViewerState state = this.getOrCreate(target, viewer);
        this.invalidateRender(state);
        state.active = true;

        // Tear down whatever was rendering before (renderer may have changed on reload).
        if (state.renderer != null) {
            state.renderer.stopRendering(state);
            state.rendered = false;
        }

        if (target == viewer && !viewer.hasPermission("moderntags.see.own")) {
            state.renderer = null;
            this.removeFromHiddenTeam(target, viewer);
            return;
        }

        state.renderer = this.resolveRenderer(target, viewer);
        this.updateNameHiding(state);
        this.applyCurrentState(state);
    }

    private void handleStop(@NotNull Player target, @NotNull Player viewer) {
        final long key = key(target, viewer);
        final ViewerState state = this.states.get(key);
        if (state == null) return;
        this.invalidateRender(state);
        state.active = false;

        if (state.renderer != null) {
            state.renderer.stopRendering(state);
            state.rendered = false;
        }
        state.renderer = null;
    }

    private void handleSuppress(@NotNull RenderTask.SuppressChange s) {
        final ViewerState state = this.states.get(key(s.target(), s.viewer()));
        if (state != null) this.applySuppressChange(state, s.reason(), s.add());
    }

    /**
     * Core suppress logic, extracted so it can be called directly from
     * {@link #handlePassengers} without posting a task back to the queue.
     */
    private void applySuppressChange(@NotNull ViewerState state, byte reason, boolean add) {
        if (add) {
            if (state.hasSuppress(reason)) return;
            state.addSuppress(reason);
        } else {
            if (!state.hasSuppress(reason)) return;
            state.removeSuppress(reason);
        }

        this.applyCurrentState(state);
    }

    /**
     * Applies viewer visibility and the effective suppression mask:
     * <ul>
     *   <li>mask == 0               → show at full opacity</li>
     *   <li>mask == SUPPRESS_SNEAK  → show with sneak opacity (ModernRenderer only)</li>
     *   <li>any other non-zero mask → hide</li>
     * </ul>
     * Spectator targets are visible only to spectators, who ignore invisibility.
     * Legacy nametags use Minecraft's native invisibility handling.
     */
    private void applyCurrentState(@NotNull ViewerState state) {
        if (state.renderer == null) return;

        final boolean spectatorTarget = state.target.getGameMode() == GameMode.SPECTATOR;
        final boolean spectatorViewer = state.viewer.getGameMode() == GameMode.SPECTATOR;
        int suppressMask = state.suppressMask;
        if (state.renderer instanceof LegacyRenderer || spectatorViewer) {
            suppressMask &= ~ViewerState.SUPPRESS_INVISIBLE;
        }
        final boolean fullyHidden = spectatorTarget && !spectatorViewer
                || (suppressMask & ~ViewerState.SUPPRESS_SNEAK) != 0;

        if (fullyHidden) {
            if (state.rendered) {
                this.invalidateRender(state);
                state.renderer.hide(state);
                state.rendered = false;
            }
            return;
        }

        if (!state.rendered) {
            this.invalidateRender(state);
            final boolean legacyClaimsTeam = state.renderer instanceof LegacyRenderer
                    && this.protocolManager.getChannel(state.viewer.getUniqueId()) != null;
            try {
                state.renderer.render(state);
            } finally {
                if (legacyClaimsTeam) {
                    // Legacy may have sent CREATE before a later placeholder evaluation fails.
                    this.forgetExternalMembership(state.viewer.getEntityId(), state.target.getName());
                }
            }
            state.rendered = true;
        } else if (state.renderer instanceof ModernRenderer modern) {
            modern.updateOpacity(state);
        }
    }

    // ── Team packet handling ──────────────────────────────────────────────────

    /**
     * Central handler for all intercepted TEAMS packets.
     * <p>
     * <h3>Processing steps</h3>
     * <ol>
     *   <li>For {@code REMOVE}: snapshot the current registry members <em>before</em>
     *       clearing, since the protocol carries no player list for this mode.</li>
     *   <li>Classify every affected player into <em>modern</em>, <em>legacy</em>, or
     *       <em>unmanaged</em> using the current renderer, or matching modern tag rules
     *       while the renderer is absent. Legacy removals must still belong to the source team.</li>
     *   <li>Update {@link #teamRegistry} based on mode.</li>
     *   <li>Re-send packets:
     *     <ul>
     *       <li>Modern  → {@link #resendForModernPlayers}</li>
     *       <li>Legacy  → {@link ViewerState#teamColor} stored;
     *           {@link LegacyRenderer#updateViewer(ViewerState)} triggered</li>
     *       <li>Unmanaged / empty-player-list → {@link #resendForUnmanagedPlayers}</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>Allocation notes</h3>
     * <p>The three classification lists ({@link #reuseModernNames}, {@link #reuseLegacyStates},
     * {@link #reuseUnmanagedNames}) are instance-level buffers. Name lists are cleared here;
     * pair references are released by the task dispatcher after processing, including failures.
     * The REMOVE path no longer copies the registry {@code Set} into an {@code ArrayList} — it
     * holds the {@code Set} reference directly, which is safe because {@code REMOVE} in step 3
     * removes the set from the map without modifying its contents.
     */
    private void handleTeamPacket(@NotNull RenderTask.TeamPacket t) {
        final int viewerId = t.viewer().getEntityId();
        final WrapperPlayServerTeams.TeamMode mode = t.mode();
        Map<String, Set<String>> viewerTeams = this.teamRegistry.get(viewerId);
        final Set<String> sourceMembers = viewerTeams != null ? viewerTeams.get(t.teamName()) : null;

        // ── Step 1: Resolve affected players ─────────────────────────────────
        // For REMOVE the protocol sends no player list; read the registry before clearing.
        // UPDATE has no membership payload, so classify its existing members as well.
        // Empty CREATE/ADD/REMOVE_ENTITIES must retain their empty membership payload.
        // Classification reads source membership before the registry is changed.
        final Collection<String> affectedPlayers;
        if (mode == WrapperPlayServerTeams.TeamMode.REMOVE
                || mode == WrapperPlayServerTeams.TeamMode.UPDATE && t.players().isEmpty()) {
            affectedPlayers = sourceMembers != null ? sourceMembers : Set.of();
        } else {
            affectedPlayers = t.players();
        }

        // ── Step 2: Classify affected players (reusable lists, no per-call allocation) ──
        this.reuseModernNames.clear();
        this.reuseUnmanagedNames.clear();

        for (final String name : affectedPlayers) {
            final Player target = Bukkit.getPlayerExact(name);
            if (target == null) {
                this.reuseUnmanagedNames.add(name);
                continue;
            }
            final ViewerState state = this.states.get(key(target, t.viewer()));
            if (this.usesModernRenderer(target, t.viewer(), state)) {
                this.reuseModernNames.add(name);
            } else if (state == null || state.renderer == null) {
                this.reuseUnmanagedNames.add(name);
            } else if (mode != WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES
                    || sourceMembers != null && sourceMembers.contains(name)) {
                this.reuseLegacyStates.add(state);
            }
        }

        // ── Step 3: Update team registry ──────────────────────────────────────
        switch (mode) {
            case CREATE, ADD_ENTITIES -> {
                if (!t.players().isEmpty()) {
                    if (viewerTeams == null) {
                        viewerTeams = new HashMap<>();
                        this.teamRegistry.put(viewerId, viewerTeams);
                    }
                    final Set<String> members = sourceMembers != null ? sourceMembers
                            : viewerTeams.computeIfAbsent(t.teamName(), $ -> new HashSet<>());
                    for (final Set<String> previousMembers : viewerTeams.values()) {
                        if (previousMembers == members) continue;
                        for (final String name : t.players()) previousMembers.remove(name);
                    }
                    members.addAll(t.players());
                }
            }
            case REMOVE_ENTITIES -> {
                if (sourceMembers != null) {
                    t.players().forEach(sourceMembers::remove);
                    if (sourceMembers.isEmpty()) viewerTeams.remove(t.teamName());
                }
            }
            case REMOVE -> {
                if (viewerTeams != null) viewerTeams.remove(t.teamName());
            }
            case UPDATE -> { /* no membership change */ }
        }

        // ── Step 4: Obtain viewer channel ─────────────────────────────────────
        final Object channel = this.protocolManager.getChannel(t.viewer().getUniqueId());
        if (channel == null) return;

        // ── Step 5: Re-send for ModernRenderer players ────────────────────────
        this.resendForModernPlayers(t, viewerId, channel, mode, this.reuseModernNames);

        // ── Step 6: Update color for LegacyRenderer players ───────────────────
        // The packet itself is NOT forwarded — LegacyRenderer owns these teams.
        // Only the resolved NamedTextColor is persisted (not the full packet wrapper).
        for (final ViewerState state : this.reuseLegacyStates) {
            if (mode == WrapperPlayServerTeams.TeamMode.REMOVE
                    || mode == WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES) {
                // Server removed the player from a team → clear stored color so LegacyRenderer
                // falls back to the frame default color on the next placeholder update.
                state.teamColor = null;
            } else if (t.info() != null) {
                // CREATE / UPDATE / ADD_ENTITIES with info → persist only the color field.
                final Object rawColor = t.info().getColor();
                state.teamColor = rawColor instanceof NamedTextColor nc ? nc : null;
            }

            // Refresh only this viewer so the new (or cleared) color is applied immediately.
            ((LegacyRenderer) state.renderer).updateViewer(state);
        }

        // ── Step 7: Re-send for unmanaged players (and info-only packets) ─────
        this.resendForUnmanagedPlayers(t, viewerId, channel, mode,
                this.reuseUnmanagedNames, affectedPlayers.isEmpty());
    }

    /**
     * Re-sends external teams for modern targets with vanilla nametags hidden.
     * Member removals remain suppressed until an add transfers the entry elsewhere.
     */
    private void resendForModernPlayers(
            @NotNull RenderTask.TeamPacket t,
            int viewerId,
            @NotNull Object channel,
            @NotNull WrapperPlayServerTeams.TeamMode mode,
            @NotNull List<String> modernPlayers) {

        if (mode == WrapperPlayServerTeams.TeamMode.REMOVE) {
            // Transfer modern entries before removing the team that hides their names.
            // REMOVE_ENTITIES may have removed them from the source registry while
            // the client still retains them, so use actual client membership here.
            final ClientTeamView view = this.clientTeams.get(viewerId);
            final ClientTeam team = view != null ? view.teams.get(t.teamName()) : null;
            if (team != null) {
                for (final String name : List.copyOf(team.members)) {
                    final Player target = Bukkit.getPlayerExact(name);
                    if (target == null || target == t.viewer()) continue;
                    final ViewerState state = this.states.get(key(target, t.viewer()));
                    if (this.usesModernRenderer(target, t.viewer(), state)) {
                        this.addToHiddenTeam(target, t.viewer(), channel);
                    }
                }
            }
            this.sendClientTeam(viewerId, channel, t.teamName(), mode, null, List.of());
            return;
        }
        if (modernPlayers.isEmpty()) return;

        switch (mode) {
            case CREATE, UPDATE -> {
                if (t.info() != null) {
                    this.sendClientTeam(viewerId, channel, t.teamName(), mode,
                            makeNeverInfo(t.info()), modernPlayers);
                }
            }
            case ADD_ENTITIES -> {
                final ClientTeamView view = this.clientTeams.get(viewerId);
                final ClientTeam team = view != null ? view.teams.get(t.teamName()) : null;
                if (team == null) return;
                // An empty or unmanaged CREATE may have forwarded the original visibility.
                if (team.info.getTagVisibility() != WrapperPlayServerTeams.NameTagVisibility.NEVER) {
                    this.sendClientTeam(viewerId, channel, t.teamName(),
                            WrapperPlayServerTeams.TeamMode.UPDATE, makeNeverInfo(team.info), List.of());
                }
                this.sendClientTeam(viewerId, channel, t.teamName(), mode, null, modernPlayers);
            }
            case REMOVE_ENTITIES -> { /* keep modern targets in their hidden-name team */ }
        }
    }

    /**
     * Re-sends the team packet for players not managed by ModernTags, and also for
     * info-only packets (empty player list) such as sidebar/tab-list teams created by TAB.
     *
     * <p>Passing an empty {@code unmanagedPlayers} list with {@code noAffectedPlayers=true}
     * triggers a verbatim re-send of the original packet, which is exactly what plugins
     * like TAB expect for their display teams.
     *
     * <p>The original {@code info} is forwarded as-is — {@code makeNeverInfo} must NOT be
     * applied here, as unmanaged players and info-only display teams are not managed by
     * ModernTags and must retain their original {@code nameTagVisibility}.
     */
    private void resendForUnmanagedPlayers(
            @NotNull RenderTask.TeamPacket t,
            int viewerId,
            @NotNull Object channel,
            @NotNull WrapperPlayServerTeams.TeamMode mode,
            @NotNull List<String> unmanagedPlayers,
            boolean noAffectedPlayers) {

        switch (mode) {
            case CREATE, UPDATE -> {
                // Re-send if there are unmanaged players, OR if the packet had no players at all
                // (info-only packet — e.g. TAB sidebar/tab-list display team).
                if (!unmanagedPlayers.isEmpty() || noAffectedPlayers) {
                    this.sendClientTeam(viewerId, channel, t.teamName(), mode, t.info(), unmanagedPlayers);
                }
            }
            case ADD_ENTITIES, REMOVE_ENTITIES -> {
                if (!unmanagedPlayers.isEmpty()) {
                    this.sendClientTeam(viewerId, channel, t.teamName(), mode, null, unmanagedPlayers);
                }
            }
            case REMOVE -> {
                // Forward REMOVE if:
                //  a) There were unmanaged members in this team (we forwarded their CREATE), OR
                //  b) The original packet had no affected players at all (empty-player-list team
                //     whose CREATE we forwarded verbatim in a previous packet).
                //
                // The shared sender also prevents a second REMOVE after the modern path sent it.
                if (!unmanagedPlayers.isEmpty() || noAffectedPlayers) {
                    this.sendClientTeam(viewerId, channel, t.teamName(), mode, null, List.of());
                }
            }
        }
    }

    /**
     * Sends external team operations while retaining the client-side membership they produce.
     * In particular, REMOVE_ENTITIES is valid only for entries still in that exact team.
     */
    private void sendClientTeam(int viewerId, @NotNull Object channel, @NotNull String name,
                                @NotNull WrapperPlayServerTeams.TeamMode mode,
                                @Nullable WrapperPlayServerTeams.ScoreBoardTeamInfo info,
                                @NotNull List<String> players) {
        ClientTeamView view = this.clientTeams.get(viewerId);
        ClientTeam team = view != null ? view.teams.get(name) : null;
        switch (mode) {
            case CREATE, UPDATE -> {
                if (info == null) return;
                final boolean create = team == null;
                this.sendTeam(channel, name, create
                                ? WrapperPlayServerTeams.TeamMode.CREATE : WrapperPlayServerTeams.TeamMode.UPDATE,
                        info, create ? players : List.of());
                if (view == null) {
                    view = new ClientTeamView();
                    this.clientTeams.put(viewerId, view);
                }
                if (create) {
                    team = new ClientTeam(info);
                    view.teams.put(name, team);
                } else {
                    team.info = info;
                }
                // UPDATE carries no members: a repeated CREATE needs a separate ADD.
                if (create || mode == WrapperPlayServerTeams.TeamMode.CREATE) {
                    if (!create && !players.isEmpty()) {
                        this.sendTeam(channel, name, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, null, players);
                    }
                    this.addClientMembers(view, team, players);
                }
            }
            case ADD_ENTITIES -> {
                if (team == null || players.isEmpty()) return;
                this.sendTeam(channel, name, mode, null, players);
                this.addClientMembers(view, team, players);
            }
            case REMOVE_ENTITIES -> {
                if (team == null || players.isEmpty()) return;
                final Set<String> removals = new LinkedHashSet<>(players.size());
                for (final String player : players) {
                    if (view.memberships.get(player) == team) {
                        removals.add(player);
                    }
                }
                if (removals.isEmpty()) return;
                this.sendTeam(channel, name, mode, null, removals);
                for (final String player : removals) {
                    view.memberships.remove(player);
                    team.members.remove(player);
                }
            }
            case REMOVE -> {
                if (team == null) return;
                this.sendTeam(channel, name, mode, null, null);
                view.teams.remove(name);
                if (name.equals(view.hiddenTeamName)) view.hiddenTeamName = null;
                for (final String member : team.members) {
                    view.memberships.remove(member, team);
                }
            }
        }
    }

    private void addClientMembers(@NotNull ClientTeamView view, @NotNull ClientTeam team,
                                  @NotNull List<String> members) {
        for (final String member : members) {
            final ClientTeam previous = view.memberships.put(member, team);
            if (previous != null && previous != team) previous.members.remove(member);
            team.members.add(member);
        }
    }

    private void forgetExternalMembership(int viewerId, @NotNull String member) {
        final ClientTeamView view = this.clientTeams.get(viewerId);
        if (view == null) return;
        final ClientTeam previous = view.memberships.remove(member);
        if (previous != null) previous.members.remove(member);
    }

    /**
     * Keeps vanilla-name suppression independent of the display passenger's lifetime.
     * Existing external teams keep their packet routing; other targets share a hidden-name team.
     */
    private void updateNameHiding(@NotNull ViewerState state) {
        if (!(state.renderer instanceof ModernRenderer) || state.target == state.viewer) {
            this.removeFromHiddenTeam(state.target, state.viewer);
            return;
        }
        final Object channel = this.protocolManager.getChannel(state.viewer.getUniqueId());
        if (channel == null) return;
        final int viewerId = state.viewer.getEntityId();
        final ClientTeamView view = this.clientTeams.get(viewerId);
        final ClientTeam team = view != null ? view.memberships.get(state.target.getName()) : null;
        if (team == null) this.addToHiddenTeam(state.target, state.viewer, channel);
    }

    private void addToHiddenTeam(@NotNull Player target, @NotNull Player viewer, @NotNull Object channel) {
        final int viewerId = viewer.getEntityId();
        final ClientTeamView view = this.clientTeams.computeIfAbsent(viewerId, $ -> new ClientTeamView());
        if (view.hiddenTeamName == null) {
            int suffix = 0;
            String name;
            do {
                name = HIDDEN_TEAM_PREFIX + suffix++;
            } while (view.teams.containsKey(name));
            this.sendClientTeam(viewerId, channel, name,
                    WrapperPlayServerTeams.TeamMode.CREATE, HIDDEN_TEAM_INFO, List.of(target.getName()));
            view.hiddenTeamName = name;
        } else {
            this.sendClientTeam(viewerId, channel, view.hiddenTeamName,
                    WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, null, List.of(target.getName()));
        }
    }

    private void removeFromHiddenTeam(@NotNull Player target, @NotNull Player viewer) {
        final ClientTeamView view = this.clientTeams.get(viewer.getEntityId());
        if (view == null || view.hiddenTeamName == null) return;
        final Object channel = this.protocolManager.getChannel(viewer.getUniqueId());
        if (channel == null) return;
        final String name = view.hiddenTeamName;
        this.sendClientTeam(viewer.getEntityId(), channel, name,
                WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, null, List.of(target.getName()));
        if (view.teams.get(name).members.isEmpty()) {
            this.sendClientTeam(viewer.getEntityId(), channel, name,
                    WrapperPlayServerTeams.TeamMode.REMOVE, null, List.of());
        }
    }

    private boolean usesModernRenderer(@NotNull Player target, @NotNull Player viewer, @Nullable ViewerState state) {
        if (state != null && state.renderer != null) return state.renderer instanceof ModernRenderer;
        return (target != viewer || viewer.hasPermission("moderntags.see.own"))
                && this.resolveRenderer(target, viewer) instanceof ModernRenderer;
    }

    private static final class ClientTeamView {

        private final Map<String, ClientTeam> teams = new HashMap<>();
        private final Map<String, ClientTeam> memberships = new HashMap<>();
        private @Nullable String hiddenTeamName;
    }

    private static final class ClientTeam {

        private WrapperPlayServerTeams.ScoreBoardTeamInfo info;
        private final Set<String> members = new HashSet<>();

        private ClientTeam(@NotNull WrapperPlayServerTeams.ScoreBoardTeamInfo info) {
            this.info = info;
        }
    }

    // ── Remaining handlers ────────────────────────────────────────────────────

    private void handlePassengers(@NotNull RenderTask.PassengersUpdate p) {
        final ViewerState state = this.states.get(key(p.target(), p.viewer()));

        final Object channel = this.protocolManager.getChannel(p.viewer().getUniqueId());
        if (channel == null) return;

        if (this.hideTagWhenHasPassenger) {
            // Call directly instead of posting back to the queue — we are already inside
            // drain, so re-posting would add unnecessary queue overhead and one extra object
            // allocation (RenderTask.SuppressChange record).
            final boolean hasReal = p.incomingPassengers().length > 0;
            if (state != null) this.applySuppressChange(state, ViewerState.SUPPRESS_PASSENGER, hasReal);
            this.sendPassengers(channel, p.target().getEntityId(), p.incomingPassengers());
            return;
        }

        if (!(state != null && state.renderer instanceof ModernRenderer modern)) {
            this.sendPassengers(channel, p.target().getEntityId(), p.incomingPassengers());
            return;
        }

        final int[] ownedIds = modern.ownedPassengerIds(state);
        if (ownedIds.length == 0) {
            this.sendPassengers(channel, p.target().getEntityId(), p.incomingPassengers());
            return;
        }

        // Merge renderer-owned passenger IDs into the incoming list before forwarding.
        final int[] merged = new int[p.incomingPassengers().length + ownedIds.length];
        System.arraycopy(p.incomingPassengers(), 0, merged, 0, p.incomingPassengers().length);
        System.arraycopy(ownedIds, 0, merged, p.incomingPassengers().length, ownedIds.length);
        this.sendPassengers(channel, p.target().getEntityId(), merged);
    }

    private void handleCleanup(@NotNull Player player) {
        final int playerId = player.getEntityId();

        // ── Viewer side: remove all (target → player) states via viewer index ─
        final LongArrayList viewerKeys = this.viewerIndex.remove(playerId);
        if (viewerKeys != null) {
            for (int i = 0; i < viewerKeys.size(); i++) {
                final long k = viewerKeys.getLong(i);
                final ViewerState st = this.states.remove(k);
                if (st != null && st.rendered && st.renderer != null) {
                    st.renderer.stopRendering(st);
                }
                // Keep the target index consistent.
                final int tid = (int) (k >>> 32);
                final LongArrayList tkeys = this.targetIndex.get(tid);
                if (tkeys != null) tkeys.rem(k);
            }
        }

        // ── Target side: remove all (player → viewer) states via target index ─
        // Previously required an O(n) full-map scan; now O(viewers) via targetIndex.
        final LongArrayList targetKeys = this.targetIndex.remove(playerId);
        if (targetKeys != null) {
            for (int i = 0; i < targetKeys.size(); i++) {
                final long k = targetKeys.getLong(i);
                final ViewerState st = this.states.remove(k);
                if (st != null && st.rendered && st.renderer != null) {
                    st.renderer.stopRendering(st);
                }
                // Keep the viewer index consistent.
                final int vid = (int) k;
                final LongArrayList vkeys = this.viewerIndex.get(vid);
                if (vkeys != null) vkeys.rem(k);
            }
        }

        // Delegate renderer-level cleanup (frame state, objective cache, etc.).
        for (final TagGroup group : this.groups) {
            for (final TagEntry entry : group.entries()) {
                entry.modernRenderer().cleanup(player);
                entry.legacyRenderer().cleanup(player);
            }
        }

        // Remove all team-registry and forwarding-tracker entries for this viewer.
        this.teamRegistry.remove(playerId);
        this.clientTeams.remove(playerId);

        // Fallback teams can outlive ViewerState during reload or after death.
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            this.removeFromHiddenTeam(player, viewer);
        }

        // Also scrub the player's name from every other viewer's registry entries
        // to avoid stale lookups if the player rejoins with the same name.
        final String leavingName = player.getName();
        for (final Map<String, Set<String>> teams : this.teamRegistry.values()) {
            for (final Set<String> members : teams.values()) {
                members.remove(leavingName);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void handleInitializeAll() {
        for (final Player target : Bukkit.getOnlinePlayers()) {
            // The entity tracker excludes the target itself; preserve permission-gated own tags.
            this.handleRender(target, target);
            for (final Player viewer : target.getTrackedPlayers()) {
                this.getOrCreate(target, viewer).tracked = true;
                this.handleRender(target, viewer);
            }
        }
    }

    private void handleInvalidateAll(boolean shutdown) {
        for (final ViewerState state : this.states.values()) {
            if (state.renderer != null && this.needsTeardown(state)) {
                state.renderer.stopRendering(state);
                state.rendered = false;
            }
        }
        this.states.clear();
        this.viewerIndex.clear();
        this.targetIndex.clear();
        this.tickSeen.clear();
        // Keep name hiding during reload; remove our fallback teams when disabling the plugin.
        if (shutdown) {
            for (final Player viewer : Bukkit.getOnlinePlayers()) {
                final ClientTeamView view = this.clientTeams.get(viewer.getEntityId());
                final Object channel = this.protocolManager.getChannel(viewer.getUniqueId());
                if (view != null && view.hiddenTeamName != null && channel != null) {
                    this.sendClientTeam(viewer.getEntityId(), channel, view.hiddenTeamName,
                            WrapperPlayServerTeams.TeamMode.REMOVE, null, List.of());
                }
            }
            this.clientTeams.clear();
            this.teamRegistry.clear();
        }
    }

    private boolean needsTeardown(ViewerState state) {
        return state.renderer instanceof LegacyRenderer legacy
                ? legacy.hasRegisteredTeam(state)
                : state.rendered;
    }

    private void recheckConditions() {
        for (final ViewerState state : this.states.values()) {
            if (!state.active) continue;

            final TagRenderer renderer = state.target == state.viewer
                    && !state.viewer.hasPermission("moderntags.see.own")
                    ? null : this.resolveRenderer(state.target, state.viewer);
            if (renderer == state.renderer) continue;

            this.invalidateRender(state);
            if (state.renderer != null) {
                state.renderer.stopRendering(state);
            }
            state.rendered = false;
            state.renderer = renderer;
            this.updateNameHiding(state);
            this.applyCurrentState(state);
        }
    }

    private void handleTick(int currentTick) {
        if (this.conditionsRecheckRate > 0
                && currentTick % this.conditionsRecheckRate == 0) {
            this.recheckConditions();
        }

        // Advance each target once per renderer, regardless of its viewer count.
        try {
            for (final ViewerState state : this.states.values()) {
                // Recover ready callbacks even if their queue offer was dropped during a burst.
                if (state.pendingSpawn != null && state.pendingSpawn.ready().get()) {
                    this.handleRenderWhenCurrent(state.pendingSpawn);
                }
                if (state.pendingAttach != null && state.pendingAttach.ready().get()) {
                    this.handleAttach(state.pendingAttach);
                }
                if (!state.rendered || state.renderer == null) continue;
                if (!this.tickSeen.computeIfAbsent(state.renderer,
                                renderer -> Collections.newSetFromMap(new IdentityHashMap<>()))
                        .add(state.target)) continue;

                final int frameRate = state.renderer.frameUpdateRate();
                if (frameRate > 0 && currentTick % frameRate == 0) {
                    state.renderer.updateFrame(state);
                    continue;
                }
                final int placeholderRate = state.renderer.placeholdersUpdateRate();
                if (placeholderRate > 0 && currentTick % placeholderRate == 0) {
                    state.renderer.updatePlaceholders(state);
                }
            }
        } finally {
            for (final Set<Player> targets : this.tickSeen.values()) {
                targets.clear();
            }
        }
    }

    // ── State access (drain-only) ─────────────────────────────────────────────

    @NotNull
    private ViewerState getOrCreate(@NotNull Player target, @NotNull Player viewer) {
        final long k = key(target, viewer);
        ViewerState state = this.states.get(k);
        if (state == null) {
            state = new ViewerState(target, viewer);
            if (target.isInvisible()) {
                state.addSuppress(ViewerState.SUPPRESS_INVISIBLE);
            }
            this.states.put(k, state);
            this.viewerIndex
                    .computeIfAbsent(viewer.getEntityId(), id -> new LongArrayList(4))
                    .add(k);
            this.targetIndex
                    .computeIfAbsent(target.getEntityId(), id -> new LongArrayList(4))
                    .add(k);
        }
        return state;
    }

    @SuppressWarnings("unused")
    public @Nullable ViewerState getState(@NotNull Player target, @NotNull Player viewer) {
        return this.states.get(key(target, viewer));
    }

    // ── Renderer resolution ───────────────────────────────────────────────────

    @Nullable
    private TagRenderer resolveRenderer(@NotNull Player target, @NotNull Player viewer) {
        final Context ownerCtx = new Context(target);
        for (final TagGroup group : this.groups) {
            if (!group.passCondition(ownerCtx)) continue;
            final Context viewerCtx = new Context(viewer);
            for (final TagEntry entry : group.entries()) {
                if (entry.passCondition(viewerCtx)) {
                    return entry.rendererFor(viewer);
                }
            }
        }
        return null;
    }

    // ── Packet helpers ────────────────────────────────────────────────────────

    /**
     * Builds a new {@link WrapperPlayServerTeams.ScoreBoardTeamInfo} identical to {@code original}
     * except with {@code nameTagVisibility} forced to {@code NEVER}.
     */
    private static WrapperPlayServerTeams.ScoreBoardTeamInfo makeNeverInfo(
            @NotNull WrapperPlayServerTeams.ScoreBoardTeamInfo original) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                original.getDisplayName(),
                original.getPrefix(),
                original.getSuffix(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                original.getCollisionRule(),
                original.getColor(),
                original.getOptionData()
        );
    }

    private void sendTeam(@NotNull Object channel, @NotNull String name,
                          @NotNull WrapperPlayServerTeams.TeamMode mode,
                          @Nullable WrapperPlayServerTeams.ScoreBoardTeamInfo info,
                          @Nullable Collection<String> players) {
        this.protocolManager.sendPacketSilently(channel, new WrapperPlayServerTeams(name, mode, info, players));
    }

    private void sendPassengers(@NotNull Object channel, int vehicleEntityId, int @NotNull [] passengers) {
        this.protocolManager.sendPacketSilently(channel,
                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers(
                        vehicleEntityId, passengers));
    }

    // ── Key encoding ──────────────────────────────────────────────────────────

    private static long key(@NotNull Player target, @NotNull Player viewer) {
        return ((long) target.getEntityId() << 32) | (viewer.getEntityId() & 0xFFFFFFFFL);
    }
}
