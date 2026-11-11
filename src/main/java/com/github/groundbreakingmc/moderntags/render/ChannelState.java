package com.github.groundbreakingmc.moderntags.render;

import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything ModernTags keeps for one connection, stored in a single channel attribute.
 *
 * <p>A connection plays two roles. As a viewer, only its own EventLoop touches
 * {@link #targets}, {@link #spectators}, {@link #teams} and {@link #belowName}. As a tag
 * owner, other EventLoops read the final {@link #tagEntityId}, {@link #tagUniqueId} and
 * {@link #teamName}, which are safely published by the attribute's atomic update. Condition
 * snapshots are published separately by the ticker through volatile fields; the ticker also
 * keeps the owner's {@link #lastFrame} here for its own use.
 *
 * <p>Netty attribute names are shared by all class loaders. The value must therefore be
 * removed during shutdown cleanup with {@link #discard} to release the plugin's class
 * loader. Cleanup is asynchronous and does not provide a barrier for plugin replacement.
 * Configuration reload keeps the same class loader and state.
 */
final class ChannelState {

    private static final AttributeKey<ChannelState> KEY = AttributeKey.valueOf("moderntags:state");

    final @NotNull Channel channel;
    private final TagManager tags;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable notification = this::checkUpdates;
    /** Set and cleared only by the ticker, before requesting at most one notification per pass. */
    boolean dirty;

    /** Entity ID of this player's tag, fixed for the connection and identical for every viewer. */
    final int tagEntityId = SpigotReflectionUtil.generateEntityId();
    /** UUID of this player's tag entity, fixed for the connection. */
    final @NotNull UUID tagUniqueId = UUID.randomUUID();
    /**
     * Name of the team that carries this player's tag for every viewer. Fixed for the connection
     * like the tag identity it derives from, so no viewer builds the name again.
     */
    final @NotNull String teamName = "MT-" + Integer.toHexString(this.tagEntityId);

    /** Players this connection tracks, by entity ID. Viewer EventLoop only. */
    final @NotNull Int2ObjectMap<TagManager.TrackedPlayer> targets = new Int2ObjectOpenHashMap<>();
    /**
     * Last spectator modes advertised to this connection, created on first use.
     * Retained across profile refreshes and native despawn until a new mode or channel cleanup.
     * Viewer EventLoop only.
     */
    @Nullable Set<UUID> spectators;
    /** Team colors and membership advertised to this connection, created on first use. Viewer EventLoop only. */
    TagManager.@Nullable SourceTeams teams;
    /** Current below-name label of our objective, or null when it does not exist. Viewer EventLoop only. */
    @Nullable Component belowName;
    /** True while a retry of pending selections is scheduled on this connection. Viewer EventLoop only. */
    boolean retrying;

    /**
     * This player's owner-side condition result, written only by the ticker thread and read by
     * the EventLoops of everyone who tracks this player. Null until the first condition pass.
     */
    volatile @Nullable OwnerSnapshot ownerSnapshot;
    /**
     * This player's viewer-side condition result, written only by the ticker thread and read by
     * this connection's EventLoop. Null until the first condition pass.
     */
    volatile @Nullable ViewerSnapshot viewerSnapshot;
    /**
     * Last owner-only frame the ticker prepared for this player's tag. When every viewer has left
     * and one returns, an unchanged tag continues with this frame instead of encoding its text
     * again. Ticker thread only; replaced by each newer frame, so at most one is retained.
     */
    RenderTicker.@Nullable Frame lastFrame;

    private ChannelState(Channel channel, TagManager tags) {
        this.channel = channel;
        this.tags = tags;
    }

    /** Returns the state of the connection, or null when none was created yet. Any thread. */
    static @Nullable ChannelState get(@NotNull Channel channel) {
        return channel.attr(KEY).get();
    }

    /**
     * Returns the state of the connection, creating it when missing. Any thread: viewers on
     * different EventLoops may first need the same owner at the same time, and the loser of
     * that race adopts the winner's instance.
     */
    static @NotNull ChannelState of(@NotNull Channel channel, @NotNull TagManager tags) {
        final var attribute = channel.attr(KEY);
        final var existing = attribute.get();
        if (existing != null) return existing;
        final var created = new ChannelState(channel, tags);
        final var previous = attribute.setIfAbsent(created);
        return previous == null ? created : previous;
    }

    /** Queues the reusable notification unless another notification is already waiting. */
    void requestUpdates() {
        if (!this.queued.compareAndSet(false, true)) return;
        try {
            this.channel.eventLoop().execute(this.notification);
        } catch (RejectedExecutionException ignored) {
            this.queued.set(false);
        }
    }

    private void checkUpdates() {
        // Publications after this reset can queue another pass, including while this one runs.
        this.queued.set(false);
        this.tags.checkUpdates(this);
    }

    /**
     * Owner conditions of one configuration generation: the groups whose owner condition passed,
     * in selection order. Immutable. Compared by reference, so the ticker keeps the previous
     * instance when nothing changed.
     */
    record OwnerSnapshot(@NotNull ConfigValues config, @NotNull List<TagGroup> eligible) {

        /** Returns true when the other snapshot holds the same configuration and the same groups. */
        boolean sameAs(@Nullable OwnerSnapshot other) {
            if (other == null || this.config != other.config || this.eligible.size() != other.eligible.size()) {
                return false;
            }
            for (int i = 0; i < this.eligible.size(); i++) {
                if (this.eligible.get(i) != other.eligible.get(i)) return false;
            }
            return true;
        }
    }

    /**
     * Viewer conditions of one configuration generation: the entry each group offers this viewer,
     * plus the viewer facts selection needs. Never modified after publication.
     */
    record ViewerSnapshot(@NotNull ConfigValues config, @NotNull UUID viewerId, @NotNull ClientVersion version,
                          boolean seesOwn, @NotNull Map<TagGroup, TagEntry> entries) {

        /** Returns true when nothing selection depends on differs. The entries map compares by reference. */
        boolean sameAs(@Nullable ViewerSnapshot other) {
            return other != null && this.config == other.config && this.seesOwn == other.seesOwn
                    && this.version == other.version && this.viewerId.equals(other.viewerId)
                    && this.entries.equals(other.entries);
        }
    }

    /** Removes the state from the connection so that no object of this class loader stays in it. */
    static void discard(@NotNull Channel channel) {
        channel.attr(KEY).set(null);
    }
}
