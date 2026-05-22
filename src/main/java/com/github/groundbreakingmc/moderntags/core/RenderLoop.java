package com.github.groundbreakingmc.moderntags.core;

import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
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
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jctools.queues.MpscArrayQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <h3>Team packet routing</h3>
 * <p>All TEAMS packets are cancelled by {@link com.github.groundbreakingmc.moderntags.listener.handler.clientbound.UpdateTeamHandler}
 * and posted as {@link RenderTask.TeamPacket}. During drain, {@link #handleTeamPacket} classifies
 * each player in the packet into one of three buckets:
 * <ol>
 *   <li><b>ModernRenderer</b> — re-sent with {@code nameTagVisibility=NEVER}. The first send
 *       uses {@code TeamMode.CREATE} (tracked in {@link #modernTeamsForwarded}); subsequent sends
 *       use the original mode so the client always has a valid team before any UPDATE arrives.</li>
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

    private final MpscArrayQueue<RenderTask> queue = new MpscArrayQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean draining = new AtomicBoolean(false);

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
     * viewer.entityId → set of teamNames for which we have forwarded a CREATE packet
     * to the client with {@code nameTagVisibility=NEVER} (ModernRenderer path).
     *
     * <p>Ensures we never send UPDATE before CREATE: if the original packet mode is
     * UPDATE but the client has not yet seen CREATE, we promote the send to CREATE.
     * Entries are removed when the team is REMOVE'd so a future CREATE resets cleanly.
     *
     * <p>Drain-only access — no synchronisation needed.
     */
    private final Int2ObjectOpenHashMap<Set<String>> modernTeamsForwarded =
            new Int2ObjectOpenHashMap<>(64);

    // ── Per-call reusable buffers (drain-only, never escape handleTeamPacket) ─────────────────

    /**
     * Reused across every {@link #handleTeamPacket} call to avoid allocating three
     * {@code ArrayList} instances per intercepted TEAMS packet.
     * Safe because drain is single-threaded and these lists are consumed before the
     * method returns (they are never stored or passed to code that retains them).
     */
    private final ArrayList<String> reuseModernNames    = new ArrayList<>();
    private final ArrayList<Player> reuseLegacyTargets  = new ArrayList<>();
    private final ArrayList<String> reuseUnmanagedNames = new ArrayList<>();

    /**
     * Reused across every {@link #handleTick} call to deduplicate renderer visits.
     * Allocated once; cleared at the start of each tick instead of re-created.
     */
    private final Set<TagRenderer> tickSeen =
            Collections.newSetFromMap(new IdentityHashMap<>(32));

    private final ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

    private ImmutableList<TagGroup> groups = ImmutableList.of();
    private boolean hideTagWhenHasPassenger = true;

    // ── Configuration ─────────────────────────────────────────────────────────

    public void groups(@NotNull List<TagGroup> groups) {
        this.groups = ImmutableList.copyOf(groups);
    }

    public void hideTagWhenHasPassenger(boolean v) {
        this.hideTagWhenHasPassenger = v;
    }

    // ── Public API (any thread) ───────────────────────────────────────────────

    /**
     * Posts a task for processing during the next drain. Lock-free, never blocks.
     * Tasks dropped when the queue is full (capacity {@value QUEUE_CAPACITY}) — acceptable
     * for visual-only updates.
     */
    public void post(@NotNull RenderTask task) {
        this.queue.offer(task);
        this.tryDrain();
    }

    // ── Drain ─────────────────────────────────────────────────────────────────

    private void tryDrain() {
        if (!this.draining.compareAndSet(false, true)) {
            return; // another thread is already draining; our task will be picked up
        }
        do {
            RenderTask task;
            while ((task = this.queue.poll()) != null) {
                this.processTask(task);
            }
            // Release. If a task was enqueued between the last poll and this CAS,
            // the CAS fails and we loop again to ensure nothing is left behind.
        } while (!this.draining.compareAndSet(true, false));
    }

    // ── Task dispatch ─────────────────────────────────────────────────────────

    private void processTask(@NotNull RenderTask task) {
        switch (task) {
            case RenderTask.Render(var target, var viewer) -> this.handleRender(target, viewer);
            case RenderTask.StopRendering(var target, var viewer) -> this.handleStop(target, viewer);
            case RenderTask.SuppressChange s -> this.handleSuppress(s);
            case RenderTask.TeamPacket t -> this.handleTeamPacket(t);
            case RenderTask.PassengersUpdate p -> this.handlePassengers(p);
            case RenderTask.Cleanup(var player) -> this.handleCleanup(player);
            case RenderTask.InitializeAll() -> this.handleInitializeAll();
            case RenderTask.InvalidateAll() -> this.handleInvalidateAll();
            case RenderTask.Tick(var tick) -> this.handleTick(tick);
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void handleRender(@NotNull Player target, @NotNull Player viewer) {
        final ViewerState state = this.getOrCreate(target, viewer);

        // Tear down whatever was rendering before (renderer may have changed on reload).
        if (state.rendered && state.renderer != null) {
            state.renderer.stopRendering(state);
            state.rendered = false;
        }

        if (target == viewer && !viewer.hasPermission("moderntags.see.own")) {
            return;
        }

        final TagRenderer renderer = this.resolveRenderer(target, viewer);
        state.renderer = renderer;

        if (state.isSuppressed()) return; // applyCurrentState will re-render when suppression clears

        renderer.render(state);
        state.rendered = true;
    }

    private void handleStop(@NotNull Player target, @NotNull Player viewer) {
        final long key = key(target, viewer);
        final ViewerState state = this.states.get(key);
        if (state == null) return;

        if (state.rendered && state.renderer != null) {
            state.renderer.stopRendering(state);
            state.rendered = false;
        }
    }

    private void handleSuppress(@NotNull RenderTask.SuppressChange s) {
        this.applySuppressChange(s.target(), s.viewer(), s.reason(), s.add());
    }

    /**
     * Core suppress logic, extracted so it can be called directly from
     * {@link #handlePassengers} without posting a task back to the queue.
     */
    private void applySuppressChange(
            @NotNull Player target, @NotNull Player viewer, byte reason, boolean add) {
        final ViewerState state = this.states.get(key(target, viewer));
        if (state == null) return;

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
     * Applies the visual state dictated by {@code suppressMask}:
     * <ul>
     *   <li>mask == 0               → show at full opacity</li>
     *   <li>mask == SUPPRESS_SNEAK  → show with sneak opacity (ModernRenderer only)</li>
     *   <li>any other non-zero mask → hide</li>
     * </ul>
     */
    private void applyCurrentState(@NotNull ViewerState state) {
        if (state.renderer == null) return;

        final boolean fullyHidden = (state.suppressMask & ~ViewerState.SUPPRESS_SNEAK) != 0;

        if (fullyHidden) {
            if (state.rendered) {
                state.renderer.hide(state);
                state.rendered = false;
            }
            return;
        }

        if (!state.rendered) {
            state.renderer.render(state);
            state.rendered = true;
        }

        if (state.renderer instanceof ModernRenderer modern) {
            if (state.isSneakOnly()) {
                modern.applySneakOpacity(state);
            } else {
                modern.removeSneakOpacity(state);
            }
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
     *   <li>Update {@link #teamRegistry} based on mode.</li>
     *   <li>Classify every affected player into <em>modern</em>, <em>legacy</em>, or
     *       <em>unmanaged</em> based on the active renderer in their {@link ViewerState}.</li>
     *   <li>Re-send packets:
     *     <ul>
     *       <li>Modern  → {@link #resendForModernPlayers}</li>
     *       <li>Legacy  → {@link ViewerState#teamColor} stored; {@code updatePlaceholders} triggered</li>
     *       <li>Unmanaged / empty-player-list → {@link #resendForUnmanagedPlayers}</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>Allocation notes</h3>
     * <p>The three classification lists ({@link #reuseModernNames}, {@link #reuseLegacyTargets},
     * {@link #reuseUnmanagedNames}) are instance-level buffers cleared at the top of this method.
     * The REMOVE path no longer copies the registry {@code Set} into an {@code ArrayList} — it
     * holds the {@code Set} reference directly, which is safe because {@code REMOVE} in step 2
     * removes the set from the map without modifying its contents.
     */
    private void handleTeamPacket(@NotNull RenderTask.TeamPacket t) {
        final int viewerId = t.viewer().getEntityId();
        final WrapperPlayServerTeams.TeamMode mode = t.mode();

        // ── Step 1: Resolve affected players ─────────────────────────────────
        // For REMOVE the protocol sends no player list; read the registry before clearing.
        // For any mode with an empty player list (e.g. UPDATE, info-only CREATE) we also
        // pull the current membership so classification can proceed correctly.
        // No copy is needed: REMOVE only removes the Set from the map (step 2), it does
        // not modify the Set itself, so a direct reference is safe to iterate afterward.
        final Collection<String> affectedPlayers;
        if (mode == WrapperPlayServerTeams.TeamMode.REMOVE || t.players().isEmpty()) {
            final Map<String, Set<String>> viewerTeams = this.teamRegistry.get(viewerId);
            affectedPlayers = (viewerTeams != null)
                    ? viewerTeams.getOrDefault(t.teamName(), Set.of())
                    : Set.of();
        } else {
            affectedPlayers = t.players();
        }

        // ── Step 2: Update team registry ──────────────────────────────────────
        switch (mode) {
            case CREATE, ADD_ENTITIES -> {
                if (!t.players().isEmpty()) {
                    this.teamRegistry
                            .computeIfAbsent(viewerId, $ -> new HashMap<>())
                            .computeIfAbsent(t.teamName(), $ -> new HashSet<>())
                            .addAll(t.players());
                }
            }
            case REMOVE_ENTITIES -> {
                final Map<String, Set<String>> viewerTeams = this.teamRegistry.get(viewerId);
                if (viewerTeams != null) {
                    final Set<String> members = viewerTeams.get(t.teamName());
                    if (members != null) {
                        t.players().forEach(members::remove);
                        if (members.isEmpty()) viewerTeams.remove(t.teamName());
                    }
                }
            }
            case REMOVE -> {
                final Map<String, Set<String>> viewerTeams = this.teamRegistry.get(viewerId);
                if (viewerTeams != null) viewerTeams.remove(t.teamName());
            }
            case UPDATE -> { /* no membership change */ }
        }

        // ── Step 3: Classify affected players (reusable lists, no per-call allocation) ──
        this.reuseModernNames.clear();
        this.reuseLegacyTargets.clear();
        this.reuseUnmanagedNames.clear();

        for (final String name : affectedPlayers) {
            final Player target = Bukkit.getPlayerExact(name);
            if (target == null) {
                this.reuseUnmanagedNames.add(name);
                continue;
            }
            final ViewerState state = this.states.get(key(target, t.viewer()));
            if (state == null || state.renderer == null) {
                this.reuseUnmanagedNames.add(name);
            } else if (state.renderer instanceof ModernRenderer) {
                this.reuseModernNames.add(name);
            } else {
                // LegacyRenderer (or any future renderer that manages its own team)
                this.reuseLegacyTargets.add(target);
            }
        }

        // ── Step 4: Obtain viewer channel ─────────────────────────────────────
        final Object channel = this.protocolManager.getChannel(t.viewer().getUniqueId());
        if (channel == null) return;

        // ── Step 5: Re-send for ModernRenderer players ────────────────────────
        this.resendForModernPlayers(t, viewerId, channel, mode, this.reuseModernNames);

        // ── Step 6: Update color for LegacyRenderer players ───────────────────
        // The packet itself is NOT forwarded — LegacyRenderer owns these teams.
        // Only the resolved NamedTextColor is persisted (not the full packet wrapper).
        for (final Player target : this.reuseLegacyTargets) {
            final ViewerState state = this.states.get(key(target, t.viewer()));
            if (state == null) continue;

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

            // Re-render so the new (or cleared) color is applied immediately.
            if (state.rendered) {
                state.renderer.updatePlaceholders(state);
            }
        }

        // ── Step 7: Re-send for unmanaged players (and info-only packets) ─────
        this.resendForUnmanagedPlayers(t, channel, mode, this.reuseUnmanagedNames, affectedPlayers.isEmpty());
    }

    /**
     * Re-sends the team packet for ModernRenderer-managed players with
     * {@code nameTagVisibility=NEVER} to suppress the vanilla nametag.
     *
     * <h3>CREATE tracking</h3>
     * <p>The client must receive {@code TeamMode.CREATE} before any {@code UPDATE}.
     * {@link #modernTeamsForwarded} tracks which (viewer, teamName) pairs have already
     * had CREATE forwarded.  If the original mode is UPDATE but CREATE has not been
     * forwarded yet, this method promotes the send to CREATE automatically.
     *
     * <h3>REMOVE_ENTITIES</h3>
     * <p>Intentionally not forwarded: keeping the player in the NEVER-visibility team
     * suppresses the vanilla nametag without interruption. When the player joins a
     * different team (ADD_ENTITIES), that team will receive NEVER visibility too.
     */
    private void resendForModernPlayers(
            @NotNull RenderTask.TeamPacket t,
            int viewerId,
            @NotNull Object channel,
            @NotNull WrapperPlayServerTeams.TeamMode mode,
            @NotNull List<String> modernPlayers) {

        if (mode == WrapperPlayServerTeams.TeamMode.REMOVE) {
            // Forward REMOVE only if we previously forwarded CREATE for this team.
            // Remove from tracker so a future CREATE resets cleanly.
            final Set<String> forwarded = this.modernTeamsForwarded.get(viewerId);
            if (forwarded != null && forwarded.remove(t.teamName())) {
                this.protocolManager.sendPacketSilently(channel, buildTeamRemovePacket(t.teamName()));
            }
            return;
        }

        if (modernPlayers.isEmpty()) return;

        switch (mode) {
            case CREATE, UPDATE -> {
                if (t.info() == null) return; // shouldn't happen, but guard anyway

                final Set<String> forwarded = this.modernTeamsForwarded
                        .computeIfAbsent(viewerId, $ -> new HashSet<>());
                final boolean alreadyCreated = forwarded.contains(t.teamName());

                // Promote UPDATE → CREATE if the client has not yet seen CREATE for this team.
                final WrapperPlayServerTeams.TeamMode sendMode = alreadyCreated
                        ? WrapperPlayServerTeams.TeamMode.UPDATE
                        : WrapperPlayServerTeams.TeamMode.CREATE;

                if (!alreadyCreated) forwarded.add(t.teamName());

                this.protocolManager.sendPacketSilently(channel,
                        new WrapperPlayServerTeams(t.teamName(), sendMode,
                                makeNeverInfo(t.info()), modernPlayers));
            }
            case ADD_ENTITIES -> {
                // The team must already exist on the client (CREATE was forwarded earlier).
                // ADD_ENTITIES carries no info, so we just forward players into the existing team;
                // it already has nameTagVisibility=NEVER from the CREATE.
                final Set<String> forwarded = this.modernTeamsForwarded.get(viewerId);
                if (forwarded != null && forwarded.contains(t.teamName())) {
                    this.protocolManager.sendPacketSilently(channel,
                            new WrapperPlayServerTeams(t.teamName(),
                                    WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, modernPlayers));
                }
                // Edge case: if CREATE was never forwarded (shouldn't happen in practice),
                // we silently skip — the player will be caught on the next CREATE/UPDATE.
            }
            case REMOVE_ENTITIES -> {
                // Not forwarded intentionally — see Javadoc above.
            }
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
            @NotNull Object channel,
            @NotNull WrapperPlayServerTeams.TeamMode mode,
            @NotNull List<String> unmanagedPlayers,
            boolean noAffectedPlayers) {

        switch (mode) {
            case CREATE, UPDATE -> {
                // Re-send if there are unmanaged players, OR if the packet had no players at all
                // (info-only packet — e.g. TAB sidebar/tab-list display team).
                if (!unmanagedPlayers.isEmpty() || noAffectedPlayers) {
                    this.protocolManager.sendPacketSilently(channel,
                            new WrapperPlayServerTeams(t.teamName(), mode, t.info(), unmanagedPlayers));
                }
            }
            case ADD_ENTITIES, REMOVE_ENTITIES -> {
                if (!unmanagedPlayers.isEmpty()) {
                    this.protocolManager.sendPacketSilently(channel,
                            new WrapperPlayServerTeams(t.teamName(), mode,
                                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, unmanagedPlayers));
                }
            }
            case REMOVE -> {
                // Forward REMOVE if:
                //  a) There were unmanaged members in this team (we forwarded their CREATE), OR
                //  b) The original packet had no affected players at all (empty-player-list team
                //     whose CREATE we forwarded verbatim in a previous packet).
                //
                // Note: ModernRenderer REMOVE is handled in resendForModernPlayers; sending REMOVE
                // a second time for the same teamName is harmless (client ignores unknown team removal).
                if (!unmanagedPlayers.isEmpty() || noAffectedPlayers) {
                    this.protocolManager.sendPacketSilently(channel,
                            buildTeamRemovePacket(t.teamName()));
                }
            }
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
            this.applySuppressChange(p.target(), p.viewer(), ViewerState.SUPPRESS_PASSENGER, hasReal);
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
        this.modernTeamsForwarded.remove(playerId);

        // Also scrub the player's name from every other viewer's registry entries
        // to avoid stale lookups if the player rejoins with the same name.
        final String leavingName = player.getName();
        for (final Map<String, Set<String>> teams : this.teamRegistry.values()) {
            for (final Set<String> members : teams.values()) {
                members.remove(leavingName);
            }
        }
    }

    private void handleInitializeAll() {
        final var players = Bukkit.getOnlinePlayers();
        for (final Player target : players) {
            for (final Player viewer : players) {
                this.handleRender(target, viewer);
            }
        }
    }

    private void handleInvalidateAll() {
        for (final ViewerState state : this.states.values()) {
            if (state.rendered && state.renderer != null) {
                state.renderer.stopRendering(state);
                state.rendered = false;
            }
        }
        this.states.clear();
        this.viewerIndex.clear();
        this.targetIndex.clear();
        this.teamRegistry.clear();
        this.modernTeamsForwarded.clear();
    }

    private void handleTick(int currentTick) {
        // Deduplicate by renderer identity — updateFrame/updatePlaceholders must be called
        // once per target, not once per (target, viewer) pair.
        // tickSeen is an instance field cleared here instead of being re-allocated every tick.
        this.tickSeen.clear();

        for (final ViewerState state : this.states.values()) {
            if (!state.rendered || state.renderer == null) continue;
            if (!this.tickSeen.add(state.renderer)) continue;

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

        // Clear to release renderer references between ticks.
        this.tickSeen.clear();
    }

    // ── State access (drain-only) ─────────────────────────────────────────────

    @NotNull
    private ViewerState getOrCreate(@NotNull Player target, @NotNull Player viewer) {
        final long k = key(target, viewer);
        ViewerState state = this.states.get(k);
        if (state == null) {
            state = new ViewerState(target, viewer);
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

    @Nullable
    public ViewerState getState(@NotNull Player target, @NotNull Player viewer) {
        return this.states.get(key(target, viewer));
    }

    // ── Renderer resolution ───────────────────────────────────────────────────

    @NotNull
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
        throw new IllegalStateException("No matching TagGroup — ensure a catch-all group exists");
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

    private static WrapperPlayServerTeams buildTeamRemovePacket(@NotNull String teamName) {
        return new WrapperPlayServerTeams(
                teamName, WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                (Collection<String>) null
        );
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
