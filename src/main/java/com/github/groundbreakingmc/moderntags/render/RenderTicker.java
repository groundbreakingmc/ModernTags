package com.github.groundbreakingmc.moderntags.render;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.groundbreakingmc.moderntags.config.model.LegacyTag;
import com.github.groundbreakingmc.moderntags.config.model.ModernTag;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.retrooper.packetevents.PacketEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * One thread owns animation clocks, owner/definition sources and their subscriptions.
 * It prepares a source only when its frame or placeholder deadline changes, then publishes
 * complete immutable results through Subscription.latest. EventLoops own applied state and
 * reconcile the latest conditions and frames through one reusable notification per connection.
 *
 * <p>A source belongs to one owner and one definition and is kept in that owner's
 * {@link ChannelState}, so a tag that loses its last viewer and gets one back allocates
 * nothing.
 */
public final class RenderTicker {

    private final ModernTags plugin;
    private final ScheduledThreadPoolExecutor executor;
    private final Runnable tickTask = this::runTick;
    private final Set<ChannelState> viewers = new HashSet<>();
    /** Sources that have a consumer, in no particular order; each knows its position here. */
    private final List<Source> active = new ArrayList<>();
    /** Clocks of definitions whose owners animate together; absent while a definition has no consumer. */
    private final Map<Object, Clock> sharedClocks = new IdentityHashMap<>();
    private final Players players = new Players();
    private ConfigValues configuration;
    private long tick;
    private volatile boolean closing;

    /** Creates a stopped ticker. Call start after registering the packet listener. */
    public RenderTicker(ModernTags plugin) {
        this.plugin = plugin;
        this.executor = new ScheduledThreadPoolExecutor(1, task -> {
            final var thread = new Thread(task, "ModernTags-render");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** Starts the common tick chain; call once. */
    public void start() {
        this.execute(this.tickTask);
    }

    /** Registers a connection for condition rechecks, including while it has no visible tags. */
    void watch(ChannelState viewer) {
        this.execute(() -> this.viewers.add(viewer));
    }

    /** Adds a consumer and prepares its current result without waiting for the next tick. */
    void subscribe(Subscription subscription) {
        this.execute(() -> this.add(subscription));
    }

    /** Removes a consumer; the last consumer stops its source and animation clock. */
    void unsubscribe(Subscription subscription) {
        this.execute(() -> this.remove(subscription));
    }

    private void add(Subscription subscription) {
        if (!subscription.viewer.channel.isActive() || subscription.config != this.plugin.configValues()) return;
        this.viewers.add(subscription.viewer);
        final var source = this.source(subscription);
        subscription.source = source;
        source.subscriptions.add(subscription);
        if (source.activeIndex < 0) this.activate(source);
        final int index = this.frameIndex(source);
        final boolean due = this.isDue(source, index);
        this.prepareSafely(source, index, subscription);
        if (due) {
            for (int i = 0; i < source.subscriptions.size(); i++) {
                this.notifyViewer(source.subscriptions.get(i).viewer);
            }
        } else {
            this.notifyViewer(subscription.viewer);
        }
    }

    private void remove(Subscription subscription) {
        final var source = subscription.source;
        if (source == null || !source.subscriptions.remove(subscription)) return;
        subscription.source = null;
        if (source.subscriptions.isEmpty()) this.deactivate(source);
    }

    /**
     * Returns the owner's source for the subscription's definition, creating it on first use.
     * Idle sources of a replaced configuration are dropped on the way: nothing asks for them again.
     */
    private Source source(Subscription subscription) {
        var sources = subscription.owner.sources;
        if (sources == null) {
            // An owner usually has one definition in use, sometimes a second for other viewers.
            sources = new ArrayList<>(2);
            subscription.owner.sources = sources;
        }
        for (int i = sources.size() - 1; i >= 0; i--) {
            final var source = sources.get(i);
            if (source.definition == subscription.definition) return source;
            if (source.config != subscription.config && source.activeIndex < 0) sources.remove(i);
        }
        final var created = new Source(subscription.ownerId, subscription.config, subscription.definition);
        sources.add(created);
        return created;
    }

    /**
     * Starts a source for its first consumer: the animation begins at frame zero, or joins the
     * clock its definition shares, and the text is resolved again whatever the idle source holds.
     */
    private void activate(Source source) {
        source.activeIndex = this.active.size();
        this.active.add(source);
        source.nextTextTick = 0;
        if (source.config.syncAnimationOnEachTarget()) {
            source.started = this.tick;
            return;
        }
        var clock = this.sharedClocks.get(source.definition);
        if (clock == null) {
            clock = new Clock(this.tick);
            this.sharedClocks.put(source.definition, clock);
        }
        clock.sources++;
        source.sharedClock = clock;
    }

    /** Stops a source that lost its last consumer; the last source of a shared clock ends that clock. */
    private void deactivate(Source source) {
        final int last = this.active.size() - 1;
        final var moved = this.active.get(last);
        this.active.set(source.activeIndex, moved);
        moved.activeIndex = source.activeIndex;
        this.active.remove(last);
        source.activeIndex = -1;
        final var clock = source.sharedClock;
        if (clock == null) return;
        source.sharedClock = null;
        if (--clock.sources == 0) this.sharedClocks.remove(source.definition);
    }

    private void runTick() {
        final var config = this.plugin.configValues();
        try {
            this.tick++;
            this.tick(config);
        } catch (RuntimeException failure) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to prepare tag tick", failure);
        } finally {
            if (!this.executor.isShutdown()) {
                this.executor.schedule(this.tickTask, config.tickRate(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private void tick(ConfigValues config) {
        final boolean changed = this.configuration != config;
        this.configuration = config;
        final boolean disconnected = this.viewers.removeIf(viewer -> !viewer.channel.isActive());
        // Static sources are not visited through their consumers every tick. Sweep only when
        // a connection actually leaves, or a configuration generation becomes obsolete.
        if (disconnected || changed) this.prune(config);
        final int recheckRate = config.conditionsRecheckRate();
        final boolean recheck = changed || recheckRate > 0 && this.tick % recheckRate == 0;
        final boolean rescanAll = recheck && (this.evaluate(config) || changed);
        if (rescanAll) {
            for (final var viewer : this.viewers) viewer.dirty = true;
        }
        for (int i = 0; i < this.active.size(); i++) {
            final var source = this.active.get(i);
            this.prepareSafely(source, this.frameIndex(source), null);
        }
        this.notifyViewers();
    }

    private void prune(ConfigValues config) {
        // Backwards: stopping a source moves the last one, already visited, into its place.
        for (int i = this.active.size() - 1; i >= 0; i--) {
            final var source = this.active.get(i);
            for (int j = source.subscriptions.size() - 1; j >= 0; j--) {
                final var subscription = source.subscriptions.get(j);
                if (subscription.viewer.channel.isActive() && subscription.config == config) continue;
                source.subscriptions.remove(j);
                subscription.source = null;
            }
            if (source.subscriptions.isEmpty()) this.deactivate(source);
        }
        // Idle sources of a replaced configuration would keep its definitions alive.
        for (final var state : this.viewers) {
            final var sources = state.sources;
            if (sources == null) continue;
            for (int i = sources.size() - 1; i >= 0; i--) {
                final var source = sources.get(i);
                if (source.config != config && source.activeIndex < 0) sources.remove(i);
            }
        }
    }

    /** Publishes condition snapshots and marks changed viewers without allocating a change set. */
    private boolean evaluate(ConfigValues config) {
        boolean ownerChanged = false;
        final var selector = config.selector();
        for (final var state : this.viewers) {
            final var user = PacketEvents.getAPI().getProtocolManager().getUser(state.channel);
            if (user == null || user.getUUID() == null) continue;
            final var player = Bukkit.getPlayer(user.getUUID());
            if (player == null) continue;
            try {
                final var owner = new ChannelState.OwnerSnapshot(config, selector.forOwner(player));
                if (!owner.sameAs(state.ownerSnapshot)) {
                    state.ownerSnapshot = owner;
                    ownerChanged = true;
                }
                final var viewer = new ChannelState.ViewerSnapshot(config, player.getUniqueId(),
                        user.getClientVersion(), player.hasPermission("moderntags.see.own"),
                        selector.forViewer(player, user.getClientVersion()));
                if (!viewer.sameAs(state.viewerSnapshot)) {
                    state.viewerSnapshot = viewer;
                    state.dirty = true;
                }
            } catch (RuntimeException failure) {
                this.plugin.getLogger().log(Level.WARNING, "Failed to evaluate tag conditions", failure);
            }
        }
        return ownerChanged;
    }

    private void notifyViewers() {
        for (final var viewer : this.viewers) this.notifyViewer(viewer);
    }

    private void notifyViewer(ChannelState viewer) {
        if (!viewer.dirty) return;
        viewer.dirty = false;
        viewer.requestUpdates();
    }

    private boolean isDue(Source source, int index) {
        return source.commonFrame == null || source.commonFrame.index != index || this.tick >= source.nextTextTick;
    }

    private int frameIndex(Source source) {
        final int rate;
        final int size;
        if (source.definition instanceof ModernTag modern) {
            rate = modern.frameUpdateRate();
            size = modern.frames().size();
        } else {
            final var legacy = (LegacyTag) source.definition;
            rate = legacy.frameUpdateRate();
            size = legacy.frames().size();
        }
        if (rate <= 0) return 0;
        final long started = source.sharedClock != null ? source.sharedClock.started : source.started;
        return (int) ((this.tick - started) / rate % size);
    }

    private void prepareSafely(Source source, int index, Subscription added) {
        try {
            this.prepare(source, index, added);
        } catch (RuntimeException failure) {
            // Leave the source due so a failed initial preparation can retry on the next tick.
            source.nextTextTick = this.tick;
            this.plugin.getLogger().log(Level.WARNING, "Failed to resolve tag for " + source.ownerId, failure);
        } finally {
            this.players.owner = null;
            this.players.viewer = null;
        }
    }

    private void prepare(Source source, int index, Subscription added) {
        final boolean due = this.isDue(source, index);
        if (!due && added == null) return;
        final var config = source.config;
        final TagTextResolver.Template textTemplate;
        final TagTextResolver.Template suffixTemplate;
        final boolean legacy;
        final int textRate;
        if (source.definition instanceof ModernTag modern) {
            textTemplate = modern.frames().get(index).text();
            suffixTemplate = null;
            legacy = false;
            textRate = modern.placeholdersUpdateRate();
        } else {
            final var tag = (LegacyTag) source.definition;
            final var frame = tag.frames().get(index);
            textTemplate = frame.prefix();
            suffixTemplate = frame.suffix();
            legacy = true;
            textRate = tag.placeholdersUpdateRate();
        }
        final boolean scoreDependent = legacy && config.belowNameValue().viewerDependent();
        final boolean viewerDependent = textTemplate.viewerDependent()
                || suffixTemplate != null && suffixTemplate.viewerDependent() || scoreDependent;
        // An existing common result can initialize another consumer without any player lookup.
        if (!due && !viewerDependent) {
            this.publish(added, source.commonFrame);
            return;
        }
        final var owner = Bukkit.getPlayer(source.ownerId);
        if (owner == null) return;
        this.players.owner = owner;
        if (due) {
            this.players.viewer = owner;
            // The frame a source held before it went idle is still its previous one here, so an
            // unchanged tag keeps that frame and the text already encoded in it.
            source.commonFrame = this.prepareCommonFrame(source.commonFrame, index,
                    textTemplate, suffixTemplate, legacy, config);
        }
        boolean complete = true;
        if (!viewerDependent) {
            for (int i = 0; i < source.subscriptions.size(); i++) {
                this.publish(source.subscriptions.get(i), source.commonFrame);
            }
        } else if (!due) {
            complete = this.prepareForViewer(added, source.commonFrame,
                    textTemplate, suffixTemplate, legacy, scoreDependent, config);
        } else {
            for (int i = 0; i < source.subscriptions.size(); i++) {
                if (!this.prepareForViewer(source.subscriptions.get(i), source.commonFrame,
                        textTemplate, suffixTemplate, legacy, scoreDependent, config)) complete = false;
            }
        }
        if (due || !complete) {
            source.nextTextTick = !complete ? this.tick
                    : textRate > 0 ? this.tick + textRate : Long.MAX_VALUE;
        }
    }

    /** Resolves owner-only parts with both context players set to the owner by prepare. */
    private Frame prepareCommonFrame(Frame previous, int index, TagTextResolver.Template textTemplate,
                                     TagTextResolver.Template suffixTemplate, boolean legacy, ConfigValues config) {
        final var resolver = this.plugin.tagTextResolver();
        final var text = textTemplate.viewerDependent() ? null : resolver.resolve(this.players, textTemplate);
        final var suffix = suffixTemplate == null ? Component.empty()
                : suffixTemplate.viewerDependent() ? null : resolver.resolve(this.players, suffixTemplate);
        final var belowNameValue = config.belowNameValue();
        final int score = !legacy || belowNameValue.viewerDependent() ? 0
                : belowNameValue.compiled().applyAsInt(this.players.owner, this.players.owner);
        return frame(previous, index, text, suffix, score, legacy);
    }

    /**
     * Completes one viewer's result using prepare's owner context. Returns false on failure
     * so other viewers can continue and the coordinator can schedule another attempt.
     */
    private boolean prepareForViewer(Subscription subscription, Frame commonFrame,
                                     TagTextResolver.Template textTemplate, TagTextResolver.Template suffixTemplate,
                                     boolean legacy, boolean scoreDependent, ConfigValues config) {
        try {
            final var viewer = Bukkit.getPlayer(subscription.viewerId);
            if (viewer == null) return false;
            this.players.viewer = viewer;
            final var resolver = this.plugin.tagTextResolver();
            final var text = textTemplate.viewerDependent() ? resolver.resolve(this.players, textTemplate) : commonFrame.text;
            final var suffix = suffixTemplate != null && suffixTemplate.viewerDependent()
                    ? resolver.resolve(this.players, suffixTemplate) : commonFrame.suffix;
            final int score = scoreDependent ? config.belowNameValue().compiled().applyAsInt(this.players.owner, viewer)
                    : commonFrame.score;
            this.publish(subscription, frame(subscription.latest, commonFrame.index, text, suffix, score, legacy));
            return true;
        } catch (RuntimeException failure) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to resolve tag for " + subscription.ownerId, failure);
            return false;
        }
    }

    /**
     * Keeps the previous frame when nothing changed. A new modern frame encodes its text here,
     * on the ticker, so every viewer copies the same bytes instead of serializing the component.
     */
    private static Frame frame(Frame previous, int index, Component text, Component suffix, int score,
                               boolean legacy) {
        if (previous != null && previous.index == index && previous.score == score
                && Objects.equals(previous.text, text) && Objects.equals(previous.suffix, suffix)) return previous;
        return new Frame(index, text, suffix, score,
                legacy || text == null ? null : ModernRenderer.encodeText(text));
    }

    private void publish(Subscription subscription, Frame frame) {
        if (subscription.latest == frame) return;
        subscription.latest = frame;
        subscription.viewer.dirty = true;
    }

    /** Stops production and releases preparation state; TagManager schedules channel cleanup. */
    public void close() {
        if (this.closing) return;
        this.closing = true;
        this.executor.execute(() -> {
            this.executor.shutdown();
            this.active.clear();
            this.sharedClocks.clear();
            this.viewers.clear();
        });
    }

    /** Lets connection notifications reject work after shutdown starts. */
    boolean isClosing() {
        return this.closing;
    }

    private void execute(Runnable message) {
        if (this.closing) return;
        try {
            this.executor.execute(message);
        } catch (RejectedExecutionException failure) {
            if (!this.executor.isShutdown()) throw failure;
        }
    }

    /** Identity is immutable; the source link is ticker-only, latest is published, applied is EventLoop-only. */
    static final class Subscription {
        final ChannelState viewer;
        /** State of the owner's connection, which keeps that owner's sources. */
        final ChannelState owner;
        final int entityId;
        final UUID ownerId;
        final UUID viewerId;
        final ConfigValues config;
        final Object definition;
        private Source source;
        /** Complete result for this viewer; no viewer-dependent parts remain unresolved. */
        volatile Frame latest;
        Frame applied;

        Subscription(ChannelState viewer, ChannelState owner, int entityId, UUID ownerId, UUID viewerId,
                     ConfigValues config, Object definition) {
            this.viewer = viewer;
            this.owner = owner;
            this.entityId = entityId;
            this.ownerId = ownerId;
            this.viewerId = viewerId;
            this.config = config;
            this.definition = definition;
        }
    }

    /**
     * Prepared values for one animation frame. A common frame can leave viewer-dependent
     * text or suffix null and a viewer-dependent score at zero. A frame published to a
     * subscription contains complete values.
     *
     * <p>{@code encodedText} is the text's TextDisplay metadata entry from
     * {@link ModernRenderer#encodeText}, shared by every viewer of this frame and never
     * modified. It is null for legacy definitions and while the text itself is null.
     */
    record Frame(int index, Component text, Component suffix, int score, byte @Nullable [] encodedText) { }

    /** Direct template rendering does not retain this context; only the ticker uses it, synchronously. */
    private static final class Players implements TagTextResolver.Context {
        private Player owner;
        private Player viewer;

        @Override
        public Player owner() {
            return this.owner;
        }

        @Override
        public Player viewer() {
            return this.viewer;
        }
    }

    /**
     * Prepared frames of one owner for one definition. The owner's {@link ChannelState} keeps
     * the source for the whole connection, also while it has no consumer. A first consumer then
     * starts it exactly like a new one; only the objects are reused. Ticker thread only.
     */
    static final class Source {
        private final UUID ownerId;
        private final ConfigValues config;
        private final Object definition;
        private final List<Subscription> subscriptions = new ArrayList<>(1);
        /** Position in the ticker's active list, or -1 while there is no consumer. */
        private int activeIndex = -1;
        /** Tick of the first consumer, when every target animates on its own clock. */
        private long started;
        /** Clock shared with the other owners of this definition, or null when targets animate separately. */
        private Clock sharedClock;
        private long nextTextTick;
        /**
         * Owner-only parts; dependent text/suffix are null and a dependent score is zero until
         * completion. While idle it only serves to keep an unchanged frame on the next start.
         */
        private Frame commonFrame;

        private Source(UUID ownerId, ConfigValues config, Object definition) {
            this.ownerId = ownerId;
            this.config = config;
            this.definition = definition;
        }
    }

    /** Animation start shared by the owners of one definition, with the number of sources on it. */
    private static final class Clock {
        private final long started;
        private int sources;

        private Clock(long started) {
            this.started = started;
        }
    }
}
