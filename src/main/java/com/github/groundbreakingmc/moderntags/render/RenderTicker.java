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
import java.util.HashMap;
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
 */
public final class RenderTicker {

    private final ModernTags plugin;
    private final ScheduledThreadPoolExecutor executor;
    private final Runnable tickTask = this::runTick;
    private final Set<ChannelState> viewers = new HashSet<>();
    private final Map<UUID, IdentityHashMap<Object, Animation>> animations = new HashMap<>();
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
        this.execute(() -> {
            if (!subscription.viewer.channel.isActive() || subscription.config != this.plugin.configValues()) return;
            this.viewers.add(subscription.viewer);
            final UUID scope = subscription.config.syncAnimationOnEachTarget() ? subscription.ownerId : null;
            final var definitions = this.animations.computeIfAbsent(scope, ignored -> new IdentityHashMap<>());
            final var animation = definitions.computeIfAbsent(subscription.definition, ignored -> new Animation(this.tick));
            final var source = animation.owners.computeIfAbsent(subscription.ownerId, ignored -> new OwnerFrames());
            subscription.animation = animation;
            subscription.ownerFrames = source;
            source.subscriptions.add(subscription);
            final int index = this.frameIndex(subscription.definition, animation);
            final boolean due = this.isDue(source, index);
            this.prepareSafely(subscription.ownerId, source, subscription.definition,
                    index, subscription.config, subscription);
            if (due) {
                for (final var consumer : source.subscriptions) this.notifyViewer(consumer.viewer);
            } else {
                this.notifyViewer(subscription.viewer);
            }
        });
    }

    /** Removes a consumer; the last consumer releases its source and animation clock. */
    void unsubscribe(Subscription subscription) {
        this.execute(() -> this.remove(subscription));
    }

    private void remove(Subscription subscription) {
        final var source = subscription.ownerFrames;
        if (source == null || !source.subscriptions.remove(subscription)) return;
        final var animation = subscription.animation;
        subscription.ownerFrames = null;
        subscription.animation = null;
        if (source.subscriptions.isEmpty()) animation.owners.remove(subscription.ownerId);
        if (!animation.owners.isEmpty()) return;
        final UUID scope = subscription.config.syncAnimationOnEachTarget() ? subscription.ownerId : null;
        final var definitions = this.animations.get(scope);
        definitions.remove(subscription.definition);
        if (definitions.isEmpty()) this.animations.remove(scope);
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
        for (final var definitions : this.animations.values()) {
            for (final var entry : definitions.entrySet()) {
                final var definition = entry.getKey();
                final var animation = entry.getValue();
                final int index = this.frameIndex(definition, animation);
                for (final var owner : animation.owners.entrySet()) {
                    this.prepareSafely(owner.getKey(), owner.getValue(), definition, index, config, null);
                }
            }
        }
        this.notifyViewers();
    }

    private void prune(ConfigValues config) {
        final var scopes = this.animations.values().iterator();
        while (scopes.hasNext()) {
            final var definitions = scopes.next();
            final var clocks = definitions.values().iterator();
            while (clocks.hasNext()) {
                final var animation = clocks.next();
                final var owners = animation.owners.values().iterator();
                while (owners.hasNext()) {
                    final var source = owners.next();
                    for (int i = source.subscriptions.size() - 1; i >= 0; i--) {
                        final var subscription = source.subscriptions.get(i);
                        if (subscription.viewer.channel.isActive() && subscription.config == config) continue;
                        source.subscriptions.remove(i);
                        subscription.ownerFrames = null;
                        subscription.animation = null;
                    }
                    if (source.subscriptions.isEmpty()) owners.remove();
                }
                if (animation.owners.isEmpty()) clocks.remove();
            }
            if (definitions.isEmpty()) scopes.remove();
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

    private boolean isDue(OwnerFrames source, int index) {
        return source.commonFrame == null || source.commonFrame.index != index || this.tick >= source.nextTextTick;
    }

    private int frameIndex(Object definition, Animation animation) {
        final int rate;
        final int size;
        if (definition instanceof ModernTag modern) {
            rate = modern.frameUpdateRate();
            size = modern.frames().size();
        } else {
            final var legacy = (LegacyTag) definition;
            rate = legacy.frameUpdateRate();
            size = legacy.frames().size();
        }
        return rate > 0 ? (int) ((this.tick - animation.started) / rate % size) : 0;
    }

    private void prepareSafely(UUID ownerId, OwnerFrames source, Object definition, int index,
                               ConfigValues config, Subscription added) {
        try {
            this.prepare(ownerId, source, definition, index, config, added);
        } catch (RuntimeException failure) {
            // Leave the source due so a failed initial preparation can retry on the next tick.
            source.nextTextTick = this.tick;
            this.plugin.getLogger().log(Level.WARNING, "Failed to resolve tag for " + ownerId, failure);
        } finally {
            this.players.owner = null;
            this.players.viewer = null;
        }
    }

    private void prepare(UUID ownerId, OwnerFrames source, Object definition, int index,
                         ConfigValues config, Subscription added) {
        final boolean due = this.isDue(source, index);
        if (!due && added == null) return;
        final TagTextResolver.Template textTemplate;
        final TagTextResolver.Template suffixTemplate;
        final boolean legacy;
        final int textRate;
        if (definition instanceof ModernTag modern) {
            textTemplate = modern.frames().get(index).text();
            suffixTemplate = null;
            legacy = false;
            textRate = modern.placeholdersUpdateRate();
        } else {
            final var tag = (LegacyTag) definition;
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
        final var owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return;
        this.players.owner = owner;
        if (due) {
            this.players.viewer = owner;
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
            this.animations.clear();
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

    /** Identity is immutable; source links are ticker-only, latest is published, applied is EventLoop-only. */
    static final class Subscription {
        final ChannelState viewer;
        final int entityId;
        final UUID ownerId;
        final UUID viewerId;
        final ConfigValues config;
        final Object definition;
        private Animation animation;
        private OwnerFrames ownerFrames;
        /** Complete result for this viewer; no viewer-dependent parts remain unresolved. */
        volatile Frame latest;
        Frame applied;

        Subscription(ChannelState viewer, int entityId, UUID ownerId, UUID viewerId,
                     ConfigValues config, Object definition) {
            this.viewer = viewer;
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

    private static final class Animation {
        private final long started;
        /** Sources are removed with their last subscription; an empty map ends this clock. */
        private final Map<UUID, OwnerFrames> owners = new HashMap<>();

        private Animation(long started) {
            this.started = started;
        }
    }

    private static final class OwnerFrames {
        private final List<Subscription> subscriptions = new ArrayList<>(1);
        private long nextTextTick;
        /** Owner-only parts; dependent text/suffix are null and a dependent score is zero until completion. */
        private Frame commonFrame;
    }
}
