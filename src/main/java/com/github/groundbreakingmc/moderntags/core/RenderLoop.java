package com.github.groundbreakingmc.moderntags.core;

import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
import com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer;
import com.github.groundbreakingmc.moderntags.renderer.ModernRenderer;
import com.github.groundbreakingmc.moderntags.renderer.TagRenderer;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.*;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscUnboundedArrayQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single owner of all {@link ViewerState} objects and the only writer to them.
 * <p>
 * <h2>Threading model</h2>
 * <p>Any thread may call {@link #post(RenderTask)} — it is lock-free (MPSC offer).
 * Drain runs on whichever thread wins the {@link #draining} CAS. All state
 * ({@link #states}, renderer internals) is accessed exclusively during drain, so
 * <b>no synchronisation is needed anywhere inside the process methods</b>.
 * <p>
 * <h2>Key encoding</h2>
 * <p>Map keys are {@code long}: {@code (target.entityId << 32) | viewer.entityId}.
 * Entity IDs are unique 32-bit ints for the duration of a session, so there are no
 * collisions. This avoids boxing and object allocation on the hot path.
 * <p>
 * <h2>Team packet routing</h2>
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
     * Initial linked-array chunk size; backlog is limited by available heap.
     */
    private static final int QUEUE_INITIAL_CAPACITY = 128;

    private static final String HIDDEN_TEAM_PREFIX = "MT-H-";
    private static final ScoreBoardTeamInfo HIDDEN_TEAM_INFO =
            new ScoreBoardTeamInfo(
                    Component.empty(), Component.empty(), Component.empty(),
                    NameTagVisibility.NEVER,
                    CollisionRule.ALWAYS,
                    NamedTextColor.WHITE, OptionData.NONE);

    private final MessagePassingQueue<RenderTask> queue = new MpscUnboundedArrayQueue<>(QUEUE_INITIAL_CAPACITY);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final Logger logger;

    /**
     * (target.entityId << 32) | viewer.entityId → ViewerState.
     * Plain map — only accessed during drain.
     */
    private final Long2ObjectMap<ViewerState> states = new Long2ObjectOpenHashMap<>(256);

    /**
     * viewer.entityId → list of keys where that viewer appears as the <em>viewer</em> half.
     * Enables O(viewers) cleanup instead of a full-map scan.
     */
    private final Int2ObjectMap<LongList> viewerIndex = new Int2ObjectOpenHashMap<>(64);

    /**
     * target.entityId → list of keys where that player appears as the <em>target</em> half.
     * Symmetric counterpart to {@link #viewerIndex}; eliminates the O(n) state-map scan
     * that was previously required when a target player disconnects.
     */
    private final Int2ObjectMap<LongList> targetIndex = new Int2ObjectOpenHashMap<>(64);

    /**
     * viewer.entityId → teamName → set of playerNames currently in that team.
     *
     * <p>Maintained exclusively from TEAMS packets so RenderLoop can look up members
     * when a TEAMS REMOVE arrives (which carries no player list in the protocol).
     *
     * <p>Drain-only access — no synchronisation needed.
     */
    private final Int2ObjectMap<Map<String, Set<String>>> teamRegistry = new Int2ObjectOpenHashMap<>(64);

    /**
     * External and fallback teams actually sent to each viewer.
     * Unlike renderer state, these survive reload while the client keeps its scoreboard.
     * Legacy team creation removes its target from this membership ledger.
     * Drain-only access.
     */
    private final Int2ObjectMap<ClientTeamView> clientTeams = new Int2ObjectOpenHashMap<>(64);

    /**
     * Reused across every {@link #handleTeamPacket} call to avoid allocating three
     * {@code ArrayList} instances per intercepted TEAMS packet.
     * Safe because drain is single-threaded and these lists are consumed before the
     * method returns (they are never stored or passed to code that retains them).
     */
    private final List<String> reuseModernNames = new ArrayList<>();
    private final List<ViewerState> reuseLegacyStates = new ArrayList<>();
    private final List<String> reuseUnmanagedNames = new ArrayList<>();

    private final Predicate<ViewerState> currentRenderedPair = state -> state.rendered
            && this.states.get(key(state.target, state.viewer)) == state;

    /**
     * Renderers bound during this lifecycle; unused configured renderers are never visited.
     */
    private final Set<TagRenderer> tickRenderers = Collections.newSetFromMap(new IdentityHashMap<>(32));

    private final ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

    private List<TagGroup> groups = ImmutableList.of();
    private boolean hideTagWhenHasPassenger = true;
    private int conditionsRecheckRate = 20;

    public RenderLoop(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void groups(@NotNull List<TagGroup> groups) {
        this.groups = ImmutableList.copyOf(groups);
    }

    public void hideTagWhenHasPassenger(boolean enabled) {
        this.hideTagWhenHasPassenger = enabled;
    }

    public void conditionsRecheckRate(int rate) {
        this.conditionsRecheckRate = rate;
    }

    /**
     * Enqueues a task and attempts to drain the queue on the calling thread.
     * Tasks remain queued until consumed, subject to JVM resource failure.
     */
    public void post(@NotNull RenderTask task) {
        this.queue.offer(task);
        this.tryDrain();
    }

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

    private void processTask(@NotNull RenderTask task) {
        switch (task) {
            case RenderTask.Render(var target, var viewer) -> this.handleRender(this.getOrCreate(target, viewer));
            case RenderTask.RenderIfTracked(var target, var viewer) ->
                    this.handleRenderIfTracked(target, viewer, target == viewer
                            ? this.getOrCreate(target, viewer) : this.states.get(key(target, viewer)));
            case RenderTask.GameModeUpdate(var target, var viewer) -> this.handleGameModeUpdate(target, viewer);
            case RenderTask.AttachTag attachment -> this.handleAttach(attachment);
            case RenderTask.ScheduleRender spawn -> this.handleScheduleRender(spawn);
            case RenderTask.RenderWhenCurrent pending -> this.handleRenderWhenCurrent(pending);
            case RenderTask.StopTracking(var target, var viewer) -> this.handleStopTracking(target, viewer);
            case RenderTask.StopRendering(var target, var viewer) ->
                    this.handleStop(this.states.get(key(target, viewer)));
            case RenderTask.SuppressChange change -> this.handleSuppress(change);
            case RenderTask.TeamPacket packet -> {
                try {
                    this.handleTeamPacket(packet);
                } finally {
                    // Pair references belong to this TEAM task, including failed sends.
                    this.reuseLegacyStates.clear();
                }
            }
            case RenderTask.PassengersUpdate update -> this.handlePassengers(update);
            case RenderTask.Cleanup(var player) -> this.handleCleanup(player);
            case RenderTask.InitializeAll() -> this.handleInitializeAll();
            case RenderTask.InvalidateAll(var shutdown) -> this.handleInvalidateAll(shutdown);
            case RenderTask.Tick(var tick) -> this.handleTick(tick);
        }
    }

    private void handleGameModeUpdate(@NotNull Player target, @NotNull Player viewer) {
        final ViewerState state = this.states.get(key(target, viewer));
        if (state != null) {
            // A game-mode change can also change the target's native invisibility.
            if (target.isInvisible()) state.addSuppress(ViewerState.SUPPRESS_INVISIBLE);
            else state.removeSuppress(ViewerState.SUPPRESS_INVISIBLE);
        }
        this.handleRenderIfTracked(target, viewer, state);

        if (target != viewer) return;
        final LongList viewerKeys = this.viewerIndex.get(viewer.getEntityId());
        if (viewerKeys == null) return;
        for (int i = 0; i < viewerKeys.size(); i++) {
            final ViewerState viewed = this.states.get(viewerKeys.getLong(i));
            if (viewed != null && viewed.active && viewed.target != viewer) {
                this.applyCurrentState(viewed);
            }
        }
    }

    private void handleRenderIfTracked(@NotNull Player target, @NotNull Player viewer,
                                       @Nullable ViewerState state) {
        if (target == viewer) {
            this.handleRender(state != null ? state : this.getOrCreate(target, viewer));
            return;
        }
        if (state == null || !state.tracked) return;
        if (state.pendingSpawn != null) return;
        this.handleRender(state);
    }

    private void handleScheduleRender(@NotNull RenderTask.ScheduleRender spawn) {
        final var state = this.getOrCreate(spawn.target(), spawn.viewer());
        this.handleStop(state);
        state.tracked = true;
        final var pending = new RenderTask.RenderWhenCurrent(state);
        state.pendingSpawn = pending;
        try {
            Bukkit.getAsyncScheduler().runDelayed(spawn.plugin(), task -> {
                this.post(pending);
            }, 1L, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            state.tracked = false;
            this.invalidateRender(state);
            throw ex;
        }
    }

    private void handleRenderWhenCurrent(@NotNull RenderTask.RenderWhenCurrent pending) {
        final var state = pending.state();
        if (state.pendingSpawn != pending || !state.tracked
                || this.states.get(key(state.target, state.viewer)) != state) return;
        state.pendingSpawn = null;
        this.handleRender(state);
    }

    private void handleAttach(@NotNull RenderTask.AttachTag attachment) {
        final var state = attachment.state();
        if (state.pendingAttach != attachment
                || !state.active || !state.rendered || state.renderer != attachment.renderer()
                || this.states.get(key(state.target, state.viewer)) != state) return;
        state.pendingAttach = null;
        attachment.renderer().attachPassengers(state, attachment.tagEntityId());
    }

    private void handleStopTracking(@NotNull Player target, @NotNull Player viewer) {
        final ViewerState state = this.states.get(key(target, viewer));
        if (state != null) {
            state.tracked = false;
            this.handleStop(state);
        }
        this.removeFromHiddenTeam(target, viewer, state);
    }

    private void invalidateRender(@NotNull ViewerState state) {
        state.pendingSpawn = null;
        state.pendingAttach = null;
    }

    private void handleRender(@NotNull ViewerState state) {
        final var target = state.target;
        final var viewer = state.viewer;
        this.invalidateRender(state);
        state.active = true;

        // Tear down whatever was rendering before (renderer may have changed on reload).
        if (state.renderer != null) {
            state.renderer.stopRendering(state);
            state.rendered = false;
        }

        if (target == viewer && !viewer.hasPermission("moderntags.see.own")) {
            state.renderer = null;
            this.removeFromHiddenTeam(target, viewer, state);
            return;
        }

        state.renderer = this.resolveRenderer(target, viewer);
        this.updateNameHiding(state);
        this.applyCurrentState(state);
    }

    private void handleStop(@Nullable ViewerState state) {
        if (state == null) return;
        this.invalidateRender(state);
        state.active = false;

        if (state.renderer != null) {
            state.renderer.stopRendering(state);
            state.rendered = false;
        }
        state.renderer = null;
    }

    private void handleSuppress(@NotNull RenderTask.SuppressChange change) {
        final ViewerState state = this.states.get(key(change.target(), change.viewer()));
        if (state != null) this.applySuppressChange(state, change.changedMask(), change.values());
    }

    /**
     * Core suppress logic, extracted so it can be called directly from
     * {@link #handlePassengers} without posting a task back to the queue.
     */
    private void applySuppressChange(@NotNull ViewerState state, byte changedMask, byte values) {
        final byte updatedMask = (byte) ((state.suppressMask & ~changedMask) | (values & changedMask));
        if (updatedMask == state.suppressMask) return;
        state.suppressMask = updatedMask;
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
            final boolean legacyClaimsTeam = state.renderer instanceof LegacyRenderer;
            try {
                state.renderer.render(state);
            } finally {
                if (legacyClaimsTeam) {
                    // Legacy may have sent CREATE before a later placeholder evaluation fails.
                    this.forgetExternalMembership(state.viewer.getEntityId(), state.target.getName());
                }
            }
            state.rendered = true;
            this.tickRenderers.add(state.renderer);
        } else if (state.renderer instanceof ModernRenderer modern) {
            modern.updateOpacity(state);
        }
    }

    /**
     * Central handler for all intercepted TEAMS packets.
     * <p>
     * <h2>Processing steps</h2>
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
     * <h2>Allocation notes</h2>
     * <p>The three classification lists ({@link #reuseModernNames}, {@link #reuseLegacyStates},
     * {@link #reuseUnmanagedNames}) are instance-level buffers. Name lists are cleared here;
     * pair references are released by the task dispatcher after processing, including failures.
     * The REMOVE path no longer copies the registry {@code Set} into an {@code ArrayList} — it
     * holds the {@code Set} reference directly, which is safe because {@code REMOVE} in step 3
     * removes the set from the map without modifying its contents.
     */
    private void handleTeamPacket(@NotNull RenderTask.TeamPacket packet) {
        final int viewerId = packet.viewer().getEntityId();
        final var mode = packet.mode();
        Map<String, Set<String>> viewerTeams = this.teamRegistry.get(viewerId);
        final Set<String> sourceMembers = viewerTeams != null ? viewerTeams.get(packet.teamName()) : null;

        final Collection<String> affectedPlayers;
        if (mode == TeamMode.REMOVE || mode == TeamMode.UPDATE && packet.players().isEmpty()) {
            affectedPlayers = sourceMembers != null ? sourceMembers : Set.of();
        } else {
            affectedPlayers = packet.players();
        }

        this.reuseModernNames.clear();
        this.reuseUnmanagedNames.clear();

        for (final var name : affectedPlayers) {
            final var target = Bukkit.getPlayerExact(name);
            if (target == null) {
                this.reuseUnmanagedNames.add(name);
                continue;
            }
            final ViewerState state = this.states.get(key(target, packet.viewer()));
            if (this.usesModernRenderer(target, packet.viewer(), state)) {
                this.reuseModernNames.add(name);
            } else if (state == null || state.renderer == null) {
                this.reuseUnmanagedNames.add(name);
            } else if (mode != TeamMode.REMOVE_ENTITIES || sourceMembers != null && sourceMembers.contains(name)) {
                this.reuseLegacyStates.add(state);
            }
        }

        switch (mode) {
            case CREATE, ADD_ENTITIES -> {
                if (!packet.players().isEmpty()) {
                    if (viewerTeams == null) {
                        viewerTeams = new HashMap<>();
                        this.teamRegistry.put(viewerId, viewerTeams);
                    }
                    final Set<String> members = sourceMembers != null ? sourceMembers
                            : viewerTeams.computeIfAbsent(packet.teamName(), $ -> new HashSet<>());
                    for (final var previousMembers : viewerTeams.values()) {
                        if (previousMembers == members) continue;
                        for (final var name : packet.players()) previousMembers.remove(name);
                    }
                    members.addAll(packet.players());
                }
            }
            case REMOVE_ENTITIES -> {
                if (sourceMembers != null) {
                    packet.players().forEach(sourceMembers::remove);
                    if (sourceMembers.isEmpty()) viewerTeams.remove(packet.teamName());
                }
            }
            case REMOVE -> {
                if (viewerTeams != null) viewerTeams.remove(packet.teamName());
            }
            case UPDATE -> { /* no membership change */ }
        }

        final Object channel = this.protocolManager.getChannel(packet.viewer().getUniqueId());
        if (channel == null) return;

        this.resendForModernPlayers(packet, viewerId, channel, mode, this.reuseModernNames);

        for (final var state : this.reuseLegacyStates) {
            if (mode == TeamMode.REMOVE || mode == TeamMode.REMOVE_ENTITIES) {
                state.teamColor = null;
            } else if (packet.info() != null) {
                final Object rawColor = packet.info().getColor();
                state.teamColor = rawColor instanceof NamedTextColor color ? color : null;
            }

            ((LegacyRenderer) state.renderer).updateViewer(state);
        }

        this.resendForUnmanagedPlayers(packet, viewerId, channel, mode,
                this.reuseUnmanagedNames, affectedPlayers.isEmpty());
    }

    /**
     * Re-sends external teams for modern targets with vanilla nametags hidden.
     * Member removals remain suppressed until an add transfers the entry elsewhere.
     */
    private void resendForModernPlayers(
            @NotNull RenderTask.TeamPacket packet,
            int viewerId,
            @NotNull Object channel,
            @NotNull TeamMode mode,
            @NotNull List<String> modernPlayers) {

        if (mode == TeamMode.REMOVE) {
            // Transfer actual client members before removing the team that hides their names.
            final ClientTeamView view = this.clientTeams.get(viewerId);
            final ClientTeam team = view != null ? view.teams.get(packet.teamName()) : null;
            if (team != null) {
                // Adding a fallback member also removes it from this team.
                for (final var name : List.copyOf(team.members)) {
                    final var target = Bukkit.getPlayerExact(name);
                    if (target == null || target == packet.viewer()) continue;
                    final ViewerState state = this.states.get(key(target, packet.viewer()));
                    if (this.usesModernRenderer(target, packet.viewer(), state)) {
                        this.addToHiddenTeam(target, packet.viewer(), channel);
                    }
                }
            }
            this.sendClientTeam(viewerId, channel, packet.teamName(), mode, null, List.of());
            return;
        }
        if (modernPlayers.isEmpty()) return;

        switch (mode) {
            case CREATE, UPDATE -> {
                if (packet.info() != null) {
                    this.sendClientTeam(viewerId, channel, packet.teamName(), mode,
                            makeNeverInfo(packet.info()), modernPlayers);
                }
            }
            case ADD_ENTITIES -> {
                final ClientTeamView view = this.clientTeams.get(viewerId);
                final ClientTeam team = view != null ? view.teams.get(packet.teamName()) : null;
                if (team == null) return;
                // An empty or unmanaged CREATE may have forwarded the original visibility.
                if (team.info.getTagVisibility() != NameTagVisibility.NEVER) {
                    this.sendClientTeam(viewerId, channel, packet.teamName(),
                            TeamMode.UPDATE, makeNeverInfo(team.info), List.of());
                }
                this.sendClientTeam(viewerId, channel, packet.teamName(), mode, null, modernPlayers);
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
            @NotNull RenderTask.TeamPacket packet,
            int viewerId,
            @NotNull Object channel,
            @NotNull TeamMode mode,
            @NotNull List<String> unmanagedPlayers,
            boolean noAffectedPlayers) {

        switch (mode) {
            case CREATE, UPDATE -> {
                if (!unmanagedPlayers.isEmpty() || noAffectedPlayers) {
                    this.sendClientTeam(viewerId, channel, packet.teamName(), mode, packet.info(), unmanagedPlayers);
                }
            }
            case ADD_ENTITIES, REMOVE_ENTITIES -> {
                if (!unmanagedPlayers.isEmpty()) {
                    this.sendClientTeam(viewerId, channel, packet.teamName(), mode, null, unmanagedPlayers);
                }
            }
            case REMOVE -> {
                if (!unmanagedPlayers.isEmpty() || noAffectedPlayers) {
                    this.sendClientTeam(viewerId, channel, packet.teamName(), mode, null, List.of());
                }
            }
        }
    }

    /**
     * Sends external team operations while retaining the client-side membership they produce.
     * In particular, REMOVE_ENTITIES is valid only for entries still in that exact team.
     */
    private void sendClientTeam(int viewerId, @NotNull Object channel, @NotNull String name,
                                @NotNull TeamMode mode,
                                @Nullable ScoreBoardTeamInfo info,
                                @NotNull List<String> players) {
        ClientTeamView view = this.clientTeams.get(viewerId);
        ClientTeam team = view != null ? view.teams.get(name) : null;
        switch (mode) {
            case CREATE, UPDATE -> {
                if (info == null) return;
                if (team == null) {
                    this.sendTeam(channel, name, TeamMode.CREATE, info, players);
                    if (view == null) {
                        view = new ClientTeamView();
                        this.clientTeams.put(viewerId, view);
                    }
                    team = new ClientTeam(info);
                    view.teams.put(name, team);
                    this.addClientMembers(view, team, players);
                    return;
                }

                this.sendTeam(channel, name, TeamMode.UPDATE, info, List.of());
                team.info = info;
                if (mode == TeamMode.UPDATE || players.isEmpty()) return;

                // UPDATE carries no members: a repeated CREATE needs a separate ADD.
                this.sendTeam(channel, name, TeamMode.ADD_ENTITIES, null, players);
                this.addClientMembers(view, team, players);
            }
            case ADD_ENTITIES -> {
                if (team == null || players.isEmpty()) return;
                this.sendTeam(channel, name, mode, null, players);
                this.addClientMembers(view, team, players);
            }
            case REMOVE_ENTITIES -> {
                if (team == null || players.isEmpty()) return;
                final Set<String> removals = new LinkedHashSet<>(players.size());
                for (final var player : players) {
                    if (view.memberships.get(player) == team) {
                        removals.add(player);
                    }
                }
                if (removals.isEmpty()) return;
                this.sendTeam(channel, name, mode, null, removals);
                for (final var player : removals) {
                    view.memberships.remove(player);
                    team.members.remove(player);
                }
            }
            case REMOVE -> {
                if (team == null) return;
                this.sendTeam(channel, name, mode, null, null);
                view.teams.remove(name);
                if (name.equals(view.hiddenTeamName)) view.hiddenTeamName = null;
                for (final var member : team.members) {
                    view.memberships.remove(member, team);
                }
            }
        }
    }

    private void addClientMembers(@NotNull ClientTeamView view, @NotNull ClientTeam team,
                                  @NotNull List<String> members) {
        for (final var member : members) {
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
            this.removeFromHiddenTeam(state.target, state.viewer, state);
            return;
        }
        final int viewerId = state.viewer.getEntityId();
        final ClientTeamView view = this.clientTeams.get(viewerId);
        final ClientTeam team = view != null ? view.memberships.get(state.target.getName()) : null;
        if (team == null) this.addToHiddenTeam(state.target, state.viewer, state.channel);
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
                    TeamMode.CREATE, HIDDEN_TEAM_INFO, List.of(target.getName()));
            view.hiddenTeamName = name;
        } else {
            this.sendClientTeam(viewerId, channel, view.hiddenTeamName,
                    TeamMode.ADD_ENTITIES, null, List.of(target.getName()));
        }
    }

    private void removeFromHiddenTeam(@NotNull Player target, @NotNull Player viewer,
                                      @Nullable ViewerState state) {
        final ClientTeamView view = this.clientTeams.get(viewer.getEntityId());
        if (view == null || view.hiddenTeamName == null) return;
        final Object channel = state != null ? state.channel
                : this.protocolManager.getChannel(viewer.getUniqueId());
        if (channel == null) return;
        final String name = view.hiddenTeamName;
        this.sendClientTeam(viewer.getEntityId(), channel, name,
                TeamMode.REMOVE_ENTITIES, null, List.of(target.getName()));
        if (view.teams.get(name).members.isEmpty()) {
            this.sendClientTeam(viewer.getEntityId(), channel, name,
                    TeamMode.REMOVE, null, List.of());
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

        private ScoreBoardTeamInfo info;
        private final Set<String> members = new HashSet<>();

        private ClientTeam(@NotNull ScoreBoardTeamInfo info) {
            this.info = info;
        }
    }

    private void handlePassengers(@NotNull RenderTask.PassengersUpdate update) {
        final ViewerState state = this.states.get(key(update.target(), update.viewer()));

        final Object channel = state != null ? state.channel
                : this.protocolManager.getChannel(update.viewer().getUniqueId());
        if (channel == null) return;

        int[] passengers = update.incomingPassengers();
        if (this.hideTagWhenHasPassenger) {
            final boolean hasPassengers = passengers.length > 0;
            if (state != null) this.applySuppressChange(state, ViewerState.SUPPRESS_PASSENGER,
                    hasPassengers ? ViewerState.SUPPRESS_PASSENGER : (byte) 0);
        } else if (state != null && state.renderer instanceof ModernRenderer modern) {
            passengers = modern.mergePassengers(state, passengers);
        }

        this.sendPassengers(channel, update.target().getEntityId(), passengers);
    }

    private void handleCleanup(@NotNull Player player) {
        final int playerId = player.getEntityId();

        final LongList viewerKeys = this.viewerIndex.remove(playerId);
        if (viewerKeys != null) {
            for (int i = 0; i < viewerKeys.size(); i++) {
                final long key = viewerKeys.getLong(i);
                this.removePair(key, this.targetIndex, (int) (key >>> 32));
            }
        }

        final LongList targetKeys = this.targetIndex.remove(playerId);
        if (targetKeys != null) {
            for (int i = 0; i < targetKeys.size(); i++) {
                final long key = targetKeys.getLong(i);
                this.removePair(key, this.viewerIndex, (int) key);
            }
        }

        for (final var group : this.groups) {
            for (final var entry : group.entries()) {
                entry.modernRenderer().cleanup(player);
                entry.legacyRenderer().cleanup(player);
            }
        }

        this.teamRegistry.remove(playerId);
        this.clientTeams.remove(playerId);

        // Fallback teams can outlive ViewerState during reload or after death.
        for (final var viewer : Bukkit.getOnlinePlayers()) {
            this.removeFromHiddenTeam(player, viewer, null);
        }

        final String leavingName = player.getName();
        for (final var teams : this.teamRegistry.values()) {
            for (final var members : teams.values()) {
                members.remove(leavingName);
            }
        }
    }

    private void removePair(long key, Int2ObjectMap<LongList> otherIndex, int otherId) {
        final ViewerState state = this.states.remove(key);
        if (state != null && state.rendered && state.renderer != null) {
            state.renderer.stopRendering(state);
        }

        final LongList keys = otherIndex.get(otherId);
        if (keys != null) keys.rem(key);
    }

    @SuppressWarnings("deprecation")
    private void handleInitializeAll() {
        for (final var target : Bukkit.getOnlinePlayers()) {
            // The entity tracker excludes the target itself; preserve permission-gated own tags.
            this.handleRender(this.getOrCreate(target, target));
            for (final var viewer : target.getTrackedPlayers()) {
                final var state = this.getOrCreate(target, viewer);
                state.tracked = true;
                this.handleRender(state);
            }
        }
    }

    private void handleInvalidateAll(boolean shutdown) {
        for (final var state : this.states.values()) {
            if (state.renderer != null && this.needsTeardown(state)) {
                state.renderer.stopRendering(state);
                state.rendered = false;
            }
        }
        this.states.clear();
        this.viewerIndex.clear();
        this.targetIndex.clear();
        this.tickRenderers.clear();
        // Keep name hiding during reload; remove our fallback teams when disabling the plugin.
        if (shutdown) {
            for (final var viewer : Bukkit.getOnlinePlayers()) {
                final int viewerId = viewer.getEntityId();
                final ClientTeamView view = this.clientTeams.get(viewerId);
                if (view == null || view.hiddenTeamName == null) continue;
                final Object channel = this.protocolManager.getChannel(viewer.getUniqueId());
                if (channel != null) {
                    this.sendClientTeam(viewerId, channel, view.hiddenTeamName,
                            TeamMode.REMOVE, null, List.of());
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
        for (final var state : this.states.values()) {
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

        for (final var renderer : this.tickRenderers) {
            renderer.tick(currentTick, this.currentRenderedPair);
        }
    }

    @NotNull
    private ViewerState getOrCreate(@NotNull Player target, @NotNull Player viewer) {
        final long stateKey = key(target, viewer);
        ViewerState state = this.states.get(stateKey);
        if (state == null) {
            state = new ViewerState(target, viewer);
            if (target.isInvisible()) {
                state.addSuppress(ViewerState.SUPPRESS_INVISIBLE);
            }
            this.states.put(stateKey, state);
            this.viewerIndex
                    .computeIfAbsent(viewer.getEntityId(), id -> new LongArrayList(4))
                    .add(stateKey);
            this.targetIndex
                    .computeIfAbsent(target.getEntityId(), id -> new LongArrayList(4))
                    .add(stateKey);
        }
        return state;
    }

    @SuppressWarnings("unused")
    public @Nullable ViewerState getState(@NotNull Player target, @NotNull Player viewer) {
        return this.states.get(key(target, viewer));
    }

    @Nullable
    private TagRenderer resolveRenderer(@NotNull Player target, @NotNull Player viewer) {
        if (this.groups.isEmpty()) return null;
        final var targetContext = new Context(target);
        for (final var group : this.groups) {
            if (!group.passCondition(targetContext)) continue;
            final var viewerContext = new Context(viewer);
            for (final var entry : group.entries()) {
                if (entry.passCondition(viewerContext)) {
                    return entry.rendererFor(viewer);
                }
            }
        }
        return null;
    }

    /**
     * Builds a new {@link WrapperPlayServerTeams.ScoreBoardTeamInfo} identical to {@code original}
     * except with {@code nameTagVisibility} forced to {@code NEVER}.
     */
    private static ScoreBoardTeamInfo makeNeverInfo(@NotNull ScoreBoardTeamInfo original) {
        return new ScoreBoardTeamInfo(
                original.getDisplayName(),
                original.getPrefix(),
                original.getSuffix(),
                NameTagVisibility.NEVER,
                original.getCollisionRule(),
                original.getColor(),
                original.getOptionData()
        );
    }

    private void sendTeam(@NotNull Object channel, @NotNull String name,
                          @NotNull TeamMode mode,
                          @Nullable ScoreBoardTeamInfo info,
                          @Nullable Collection<String> players) {
        this.protocolManager.sendPacketSilently(channel, new WrapperPlayServerTeams(name, mode, info, players));
    }

    private void sendPassengers(@NotNull Object channel, int vehicleEntityId, int @NotNull [] passengers) {
        this.protocolManager.sendPacketSilently(channel,
                new WrapperPlayServerSetPassengers(
                        vehicleEntityId, passengers));
    }

    private static long key(@NotNull Player target, @NotNull Player viewer) {
        return ((long) target.getEntityId() << 32) | (viewer.getEntityId() & 0xFFFFFFFFL);
    }
}
