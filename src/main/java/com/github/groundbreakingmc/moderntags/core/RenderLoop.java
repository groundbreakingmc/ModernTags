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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jctools.queues.MpscArrayQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single owner of all {@link ViewerState} objects and the only writer to them.
 *
 * <h3>Threading model</h3>
 * <p>Any thread may call {@link #post(RenderTask)} — it is lock-free (MPSC offer).
 * Drain runs on whichever thread wins the {@link #draining} CAS. All state
 * ({@link #states}, renderer internals) is accessed exclusively during drain, so
 * <b>no synchronisation is needed anywhere inside the process methods</b>.
 *
 * <h3>Key encoding</h3>
 * <p>Map keys are {@code long}: {@code (target.entityId << 32) | viewer.entityId}.
 * Entity IDs are unique 32-bit ints for the duration of a session, so there are no
 * collisions. This avoids boxing and object allocation on the hot path.
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
     * viewer.entityId → list of keys where that viewer appears.
     * Enables O(viewers) cleanup instead of a full-map scan.
     */
    private final Int2ObjectOpenHashMap<LongArrayList> viewerIndex = new Int2ObjectOpenHashMap<>(64);

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
            case RenderTask.TeamUpdate t -> this.handleTeamUpdate(t);
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
        final ViewerState state = this.states.get(key(s.target(), s.viewer()));
        if (state == null) return;

        if (s.add()) {
            if (state.hasSuppress(s.reason())) return;
            state.addSuppress(s.reason());
        } else {
            if (!state.hasSuppress(s.reason())) return;
            state.removeSuppress(s.reason());
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

    private void handleTeamUpdate(@NotNull RenderTask.TeamUpdate t) {
        final ViewerState state = this.getOrCreate(t.target(), t.viewer());

        state.teamSnapshot = new ViewerState.TeamSnapshot(
                t.teamName(),
                t.info().getPrefix(),
                t.info().getSuffix(),
                t.info().getColor(),
                t.info().getTagVisibility(),
                t.info().getCollisionRule(),
                t.info().getOptionData()
        );

        final Object channel = this.protocolManager.getChannel(t.viewer().getUniqueId());
        if (channel == null) return;

        switch (state.renderer) {
            case null -> {
                // Renderer not assigned yet; packet was already cancelled, nothing to re-send.
            }
            case ModernRenderer ignored -> {
                // Re-send with NameTagVisibility=NEVER to suppress the vanilla nametag.
                this.sendNeverVisibility(channel, t.teamName(), t.info(), t.target());
                state.renderer.updatePlaceholders(state);
            }
            case LegacyRenderer ignored -> {
                // LegacyRenderer owns its own team packets; drop the server's version.
                state.renderer.updatePlaceholders(state);
            }
        }
    }

    private void handlePassengers(@NotNull RenderTask.PassengersUpdate p) {
        final ViewerState state = this.states.get(key(p.target(), p.viewer()));

        final Object channel = this.protocolManager.getChannel(p.viewer().getUniqueId());
        if (channel == null) return;

        if (this.hideTagWhenHasPassenger) {
            final boolean hasReal = p.incomingPassengers().length > 0;
            this.post(new RenderTask.SuppressChange(
                    p.target(), p.viewer(), ViewerState.SUPPRESS_PASSENGER, hasReal));
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
        final int viewerId = player.getEntityId();

        // Remove all states where the player is a viewer (fast path via reverse index).
        final LongArrayList viewerKeys = this.viewerIndex.remove(viewerId);
        if (viewerKeys != null) {
            for (int i = 0; i < viewerKeys.size(); i++) {
                final long k = viewerKeys.getLong(i);
                final ViewerState st = this.states.remove(k);
                if (st != null && st.rendered && st.renderer != null) {
                    st.renderer.stopRendering(st);
                }
            }
        }

        // Remove all states where the player is a target.
        final long targetPrefix = (long) player.getEntityId() << 32;
        final var iter = this.states.long2ObjectEntrySet().fastIterator();
        final LongArrayList toRemove = new LongArrayList(8);
        while (iter.hasNext()) {
            final var entry = iter.next();
            if ((entry.getLongKey() & 0xFFFF_FFFF_0000_0000L) == targetPrefix) {
                final ViewerState st = entry.getValue();
                if (st.rendered && st.renderer != null) {
                    st.renderer.stopRendering(st);
                }
                toRemove.add(entry.getLongKey());
                final int vid = (int) entry.getLongKey();
                final LongArrayList vkeys = this.viewerIndex.get(vid);
                if (vkeys != null) vkeys.rem(entry.getLongKey());
            }
        }
        toRemove.forEach(this.states::remove);

        // Delegate renderer-level cleanup (frame state, objective cache, etc.).
        for (final TagGroup group : this.groups) {
            for (final TagEntry entry : group.entries()) {
                entry.modernRenderer().cleanup(player);
                entry.legacyRenderer().cleanup(player);
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
    }

    private void handleTick(int currentTick) {
        // Deduplicate by renderer identity — updateFrame/updatePlaceholders must be called
        // once per target, not once per (target, viewer) pair.
        final var seen = new IdentityHashMap<TagRenderer, Player>(32);

        for (final ViewerState state : this.states.values()) {
            if (!state.rendered || state.renderer == null) continue;
            if (seen.containsKey(state.renderer)) continue;
            seen.put(state.renderer, state.target);

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

    private void sendNeverVisibility(@NotNull Object channel,
                                     @NotNull String teamName,
                                     @NotNull WrapperPlayServerTeams.ScoreBoardTeamInfo info,
                                     @NotNull Player target) {
        final var neverInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                info.getDisplayName(), info.getPrefix(), info.getSuffix(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                info.getCollisionRule(), info.getColor(), info.getOptionData()
        );
        final var packet = new WrapperPlayServerTeams(
                teamName, WrapperPlayServerTeams.TeamMode.UPDATE,
                neverInfo, java.util.List.of(target.getName())
        );
        this.protocolManager.sendPacketSilently(channel, packet);
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
