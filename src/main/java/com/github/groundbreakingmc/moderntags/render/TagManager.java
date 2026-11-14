package com.github.groundbreakingmc.moderntags.render;

import com.github.groundbreakingmc.moderntags.ModernTags;

import com.github.groundbreakingmc.moderntags.config.model.LegacyTag;
import com.github.groundbreakingmc.moderntags.config.model.ModernTag;
import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.groundbreakingmc.moderntags.util.PaperApi;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Bukkit;
import com.github.groundbreakingmc.moderntags.render.ModernRenderer.CompiledFrame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import io.netty.channel.Channel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class TagManager {

    private final ModernTags plugin;
    private final RenderTicker ticker;

    /**
     * Creates the manager; configuration is read from the current published generation.
     * Natively written packets are first compared with PacketEvents' encoding, so an
     * unsupported server protocol fails here, during startup.
     *
     * @throws IllegalStateException when this server's protocol lays a packet out differently
     */
    public TagManager(@NotNull ModernTags plugin) {
        ModernRenderer.verify();
        LegacyRenderer.verify();
        this.plugin = plugin;
        this.ticker = new RenderTicker(plugin);
    }

    /** Starts the common ticker after listener registration. */
    public void start() {
        this.ticker.start();
    }

    /**
     * Stops preparation and schedules cleanup on the EventLoop of every online connection.
     * The sweep also covers connections the ticker never watched, such as an owner nobody
     * has tracked yet. Cleanup is asynchronous; this method does not wait for EventLoops.
     */
    public void close() {
        this.ticker.close();
        for (final var player : Bukkit.getOnlinePlayers()) {
            final var channel = (Channel) PacketEvents.getAPI().getPlayerManager().getChannel(player);
            if (channel == null) continue;
            try {
                channel.eventLoop().execute(() -> this.clear(channel));
            } catch (RejectedExecutionException ignored) {
                // A closed connection has no client resources to remove.
            }
        }
    }

    /**
     * Records a player spawn in the recipient's EventLoop. The channel owns the
     * tracking map; no other thread may access its entries. Repeated spawns of
     * the same player reuse the entry and reset native flags, while a changed UUID
     * replaces the entry for that entity ID.
     */
    public void track(@NotNull Channel viewer, int entityId, @NotNull UUID ownerId, @NotNull ConfigValues config) {
        final var target = this.getOrCreate(viewer, entityId, ownerId);
        target.dead = false;
        target.invisible = false;
        target.sneaking = false;
        target.passengers = IntArrays.EMPTY_ARRAY;
        if (target.display != null) {
            target.display.updateSneaking(viewer, false);
            this.attachAfterSpawn(viewer, entityId, target, config);
        }
        this.refreshTarget(viewer, entityId, target, config);
    }

    /**
     * Removes the team and ends tracking in the recipient's EventLoop, while its native entity
     * is being removed. Unknown IDs are ignored; the map is retained for subsequent spawns.
     *
     * <p>The display is not removed here: the caller adds its entity ID to the native removal
     * packet, which saves a packet of its own and removes the tag with its owner.
     *
     * @param viewer   the recipient's connection
     * @param entityId the native entity being removed
     * @return the display the caller must remove in the same packet, or null when there is none
     */
    public @Nullable ModernDisplay untrack(@NotNull Channel viewer, int entityId) {
        final var state = ChannelState.get(viewer);
        if (state == null) return null;
        final var target = state.targets.get(entityId);
        if (target == null) return null;
        this.stopSubscription(target);
        target.destroyTeam(viewer);
        state.targets.remove(entityId);
        final var display = target.display;
        target.display = null;
        return display;
    }

    /**
     * Checks tracking in the recipient's EventLoop before decoding entity data.
     */
    public boolean isTracked(@NotNull Channel viewer, int entityId) {
        final var state = ChannelState.get(viewer);
        return state != null && state.targets.containsKey(entityId);
    }

    /**
     * Removes all displays, teams and our objective in the viewer's EventLoop, then removes
     * the connection's state. Without that state delayed attachments cannot reuse these
     * entries, and the channel keeps no object of this class loader. Repeated calls are no-ops.
     */
    public void clear(@NotNull Channel viewer) {
        final var state = ChannelState.get(viewer);
        if (state == null) return;
        for (final var target : state.targets.values()) {
            this.stopSubscription(target);
            target.destroyDisplay(viewer);
            target.destroyTeam(viewer);
        }
        state.targets.clear();
        if (state.belowName != null) LegacyRenderer.destroyBelowName(viewer);
        ChannelState.discard(viewer);
        viewer.flush();
    }

    private void ensureBelowName(Channel viewer, ConfigValues config) {
        final var state = ChannelState.of(viewer, this);
        final var previous = state.belowName;
        final var label = config.belowNameChar();
        if (previous == null) LegacyRenderer.createBelowName(viewer, label);
        else if (!previous.equals(label)) LegacyRenderer.updateBelowName(viewer, label);
        else return;
        state.belowName = label;
    }

    /** Creates or updates the target's team. A new team takes its name from the resolved owner state. */
    private void renderTeam(Channel viewer, TrackedPlayer target, String playerName,
                            LegacyTag definition, NamedTextColor fallbackColor,
                            Component prefix, Component suffix, boolean visible) {
        final var color = this.resolveColor(viewer, playerName, definition, fallbackColor);
        if (target.team == null) {
            final String name = target.ownerState.teamName;
            LegacyRenderer.create(viewer, name, playerName, prefix, suffix, color, visible);
            target.team = new ClientTeam(name, playerName, prefix, suffix, color, visible);
        } else {
            target.team.update(viewer, prefix, suffix, color, visible);
        }
        target.team.definition = definition;
        target.team.fallbackColor = fallbackColor;
        if (!visible) target.team.removeScore(viewer);
    }

    private NamedTextColor resolveColor(Channel viewer, String playerName,
                                        LegacyTag definition, NamedTextColor fallbackColor) {
        if (definition != null && !definition.preservePlayerNameColor()) return fallbackColor;
        final var source = this.sourceTeamColor(viewer, playerName);
        return source == null || definition != null && definition.ignoredColors().contains(source)
                ? fallbackColor : source;
    }

    /**
     * Restores the recipient's own tracking entry after a position packet.
     * Recreates its permitted tag after a possible world change, once its Bukkit player
     * is available. Runs in its EventLoop and preserves flags across ordinary teleports.
     */
    public void positionSelf(@NotNull Channel viewer, int entityId, @NotNull UUID ownerId, @NotNull ConfigValues config) {
        final var self = this.getOrCreate(viewer, entityId, ownerId);
        self.dead = false;
        this.stopSubscription(self);
        self.destroyDisplay(viewer);
        this.refreshTarget(viewer, entityId, self, config);
    }

    /**
     * Marks an already tracked player as dead in the recipient's EventLoop.
     * Death preserves tracking; a subsequent spawn resets the flag.
     */
    public void markDead(@NotNull Channel viewer, int entityId) {
        final var target = this.tracked(viewer, entityId);
        if (target == null) return;
        target.dead = true;
        this.stopSubscription(target);
        target.hideTag(viewer);
    }

    /**
     * Applies partial metadata in the recipient's EventLoop.
     * Null values leave the corresponding native state unchanged.
     */
    public void updateMetadata(@NotNull Channel viewer, int entityId,
                               @Nullable Boolean invisible, @Nullable Boolean sneaking, @NotNull ConfigValues config) {
        final var target = this.tracked(viewer, entityId);
        if (target == null) return;
        if (sneaking != null) target.sneaking = sneaking;
        if (invisible != null && target.invisible != invisible) {
            // A spectator viewer sees invisible players, so only a changed verdict needs a new selection.
            final boolean rendered = this.canRender(viewer, target, config);
            target.invisible = invisible;
            if (rendered != this.canRender(viewer, target, config)) this.refreshTarget(viewer, entityId, target, config);
        }
        // A pending selection returns before hiding, so an invisible target is hidden here as well.
        if (target.invisible && !this.canRender(viewer, target, config)) target.hideTag(viewer);
        else if (target.display != null && sneaking != null) target.display.updateSneaking(viewer, target.sneaking);
    }

    /**
     * Records passengers excluding our tag, then returns the outgoing passenger list.
     * The input array is never modified. The handler replaces it only when necessary.
     * Runs in the recipient's EventLoop and respects hide-tag-when-has-passenger.
     */
    public int @NotNull [] updatePassengers(@NotNull Channel viewer, int entityId, int @NotNull [] passengers, @NotNull ConfigValues config) {
        final var target = this.tracked(viewer, entityId);
        if (target == null) return passengers;
        final var previousDisplay = target.display;
        final int[] nativePassengers = previousDisplay == null ? passengers
                : withoutPassenger(passengers, previousDisplay.entityId());
        final boolean passengersChanged = !Arrays.equals(target.passengers, nativePassengers);
        if (passengersChanged) {
            target.passengers = nativePassengers.length == 0 ? IntArrays.EMPTY_ARRAY
                    : nativePassengers == passengers ? nativePassengers.clone() : nativePassengers;
        }
        if (passengersChanged) this.refreshTarget(viewer, entityId, target, config);
        if (!this.canRender(viewer, target, config)) {
            target.hideTag(viewer);
            return nativePassengers;
        }
        // Selection may have removed the display; use the state that survived refreshTarget.
        final var display = target.display;
        if (display == null || !display.isAttached()) return nativePassengers;
        if (nativePassengers != passengers) return passengers;
        final int[] attached = Arrays.copyOf(passengers, passengers.length + 1);
        attached[passengers.length] = display.entityId();
        return attached;
    }

    /**
     * Records an advertised game mode and refreshes matching tracked players in the
     * recipient's EventLoop. Profile additions/removals do not affect tag visibility.
     * The last advertised mode survives native despawn and profile refreshes.
     * Only players whose visibility verdict may change are refreshed: a spectator also sees
     * invisible and spectator players, so a change of the recipient's own mode refreshes
     * those and itself, while other players' modes do not matter to a spectator recipient.
     *
     * @param viewer   the recipient's connection
     * @param viewerId the recipient's own profile ID, or null when not known yet
     * @param ownerId  the profile whose game mode is advertised
     */
    public void updateSpectator(@NotNull Channel viewer, @Nullable UUID viewerId, @NotNull UUID ownerId,
                                boolean spectator, @NotNull ConfigValues config) {
        final boolean modeChanged = this.setSpectatorProfile(viewer, ownerId, spectator);
        final boolean self = ownerId.equals(viewerId);
        final var state = self && spectator ? ChannelState.of(viewer, this) : ChannelState.get(viewer);
        if (state == null) return;
        final boolean viewerChanged = self && (state.spectatorSelf != null) != spectator;
        if (self) state.spectatorSelf = spectator ? ownerId : null;
        if (!viewerChanged && (!modeChanged || state.spectatorSelf != null)) return;
        final var spectators = state.spectators;
        final var iterator = Int2ObjectMaps.fastIterator(state.targets);
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final var target = entry.getValue();
            if (!target.ownerId.equals(ownerId) && (!viewerChanged || !target.invisible
                    && (spectators == null || !spectators.contains(target.ownerId)))) continue;
            this.refreshTarget(viewer, entry.getIntKey(), target, config);
        }
    }

    /**
     * Records source team colors and membership in the recipient's EventLoop.
     * Other team properties are ignored. Membership survives native despawn.
     */
    public void updateTeam(@NotNull Channel viewer, @NotNull String name, @NotNull TeamMode mode,
                           @Nullable NamedTextColor color, @NotNull Collection<String> players) {
        final var state = mode == TeamMode.CREATE ? ChannelState.of(viewer, this) : ChannelState.get(viewer);
        if (state == null) return;
        var teams = state.teams;
        if (teams == null) {
            if (mode != TeamMode.CREATE) return;
            teams = new SourceTeams();
            state.teams = teams;
        }

        if (mode != TeamMode.CREATE && !teams.colors.containsKey(name)) return;
        switch (mode) {
            case CREATE -> {
                if (teams.colors.containsKey(name)) teams.remove(name);
                teams.colors.put(name, color);
                for (final var player : players) teams.membership.put(player, name);
            }
            case REMOVE -> teams.remove(name);
            case UPDATE -> teams.colors.put(name, color);
            case ADD_ENTITIES -> {
                for (final var player : players) teams.membership.put(player, name);
            }
            case REMOVE_ENTITIES -> {
                for (final var player : players) teams.membership.remove(player, name);
            }
        }
        for (final var target : state.targets.values()) {
            final var team = target.team;
            if (team == null) continue;
            final var resolved = this.resolveColor(viewer, team.playerName, team.definition, team.fallbackColor);
            team.update(viewer, team.prefix, team.suffix, resolved, team.visible);
        }
    }

    /**
     * Returns a player's source team color in the recipient's EventLoop.
     * Null means no source color; the renderer chooses its configured fallback.
     */
    public @Nullable NamedTextColor sourceTeamColor(@NotNull Channel viewer, @NotNull String player) {
        final var state = ChannelState.get(viewer);
        final var teams = state == null ? null : state.teams;
        if (teams == null) return null;
        final var name = teams.membership.get(player);
        return name == null ? null : teams.colors.get(name);
    }

    /**
     * Reconciles published conditions and applies new frames in one viewer-EventLoop pass.
     * No conditions or text providers run here; the ticker publishes their immutable results.
     */
    void checkUpdates(ChannelState state) {
        final var channel = state.channel;
        if (this.ticker.isClosing() || !channel.isActive() || ChannelState.get(channel) != state) return;
        final var config = this.plugin.configValues();
        final var viewer = state.viewerSnapshot;
        final var iterator = Int2ObjectMaps.fastIterator(state.targets);
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final var target = entry.getValue();
            try {
                final var owner = target.ownerState == null ? null : target.ownerState.ownerSnapshot;
                if (viewer != target.seenViewer || owner != target.seenOwner) {
                    target.seenViewer = viewer;
                    target.seenOwner = owner;
                    this.reselect(channel, entry.getIntKey(), target, viewer, owner, config);
                }
                final var subscription = target.subscription;
                if (subscription == null || subscription.config != config) continue;
                final var frame = subscription.latest;
                if (frame == null || frame == subscription.applied) continue;
                if (this.apply(subscription, target, frame, config)) subscription.applied = frame;
            } catch (RuntimeException failure) {
                this.plugin.getLogger().log(Level.WARNING, "Failed to apply tag update", failure);
            }
        }
        // One system call for the whole pass; no native packet follows to carry these.
        channel.flush();
    }

    /**
     * Selects a tag at a render trigger such as spawn, visibility or passenger changes. Conditions
     * are evaluated now, not taken from a snapshot, so the choice matches this moment. Players that
     * are not available yet stay pending and are retried from this EventLoop.
     */
    private void refreshTarget(Channel channel, int entityId, TrackedPlayer target, ConfigValues config) {
        final var user = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        final var owner = Bukkit.getPlayer(target.ownerId);
        if (owner != null && owner.getEntityId() != entityId) {
            // Packet-only copies may reuse a real player's UUID, but not their entity ID.
            target.pendingSelection = false;
            target.ownerState = null;
            this.stopSubscription(target);
            target.hideTag(channel);
            return;
        }
        final var viewer = user == null || user.getUUID() == null ? null : Bukkit.getPlayer(user.getUUID());
        final var ownerChannel = owner == null ? null : (Channel) PacketEvents.getAPI().getPlayerManager().getChannel(owner);
        final var ownerUser = ownerChannel == null ? null : PacketEvents.getAPI().getProtocolManager().getUser(ownerChannel);
        target.pendingSelection = viewer == null || ownerChannel == null || !ownerChannel.isActive()
                || ownerUser == null || !target.ownerId.equals(ownerUser.getUUID());
        if (target.pendingSelection) {
            this.scheduleRetry(channel);
            return;
        }
        target.ownerState = ChannelState.of(ownerChannel, this);
        final var viewerState = ChannelState.get(channel);
        target.seenViewer = viewerState == null ? null : viewerState.viewerSnapshot;
        target.seenOwner = target.ownerState.ownerSnapshot;
        TagEntry entry = null;
        if (this.canRender(channel, target, config)
                && (viewer != owner || viewer.hasPermission("moderntags.see.own"))) {
            entry = config.selector().select(owner, viewer, user.getClientVersion());
        }
        this.applySelection(channel, entityId, target, entry, viewer.getUniqueId(), user.getClientVersion(), config);
    }

    /**
     * Selects a tag from published snapshots after a condition pass changed them. The first group
     * in the owner's eligible list that offers this viewer an entry wins, as in the selector.
     */
    private void reselect(Channel channel, int entityId, TrackedPlayer target,
                          ChannelState.ViewerSnapshot viewer, ChannelState.OwnerSnapshot owner, ConfigValues config) {
        if (viewer == null || owner == null || viewer.config() != config || owner.config() != config) return;
        TagEntry entry = null;
        if (this.canRender(channel, target, config)
                && (viewer.seesOwn() || !viewer.viewerId().equals(target.ownerId))) {
            for (final var group : owner.eligible()) {
                entry = viewer.entries().get(group);
                if (entry != null) break;
            }
        }
        this.applySelection(channel, entityId, target, entry, viewer.viewerId(), viewer.version(), config);
    }

    /** Hides the tag when nothing is selected; otherwise keeps or replaces the subscription for the chosen entry. */
    private void applySelection(Channel channel, int entityId, TrackedPlayer target, TagEntry entry,
                                UUID viewerId, ClientVersion version, ConfigValues config) {
        if (entry == null) {
            this.stopSubscription(target);
            target.hideTag(channel);
            return;
        }
        final Object definition = version.isNewerThanOrEquals(ClientVersion.V_1_19_4) ? entry.modern() : entry.legacy();
        final var current = target.subscription;
        if (current != null && current.config == config && current.definition == definition) return;
        this.stopSubscription(target);
        target.subscription = new RenderTicker.Subscription(ChannelState.of(channel, this), target.ownerState,
                entityId, target.ownerId, viewerId, config, definition);
        this.ticker.subscribe(target.subscription);
    }

    /** Schedules one retry of pending selections, replacing per-tick polling. */
    private void scheduleRetry(Channel channel) {
        final var state = ChannelState.get(channel);
        if (state == null || state.retrying) return;
        state.retrying = true;
        try {
            channel.eventLoop().schedule(() -> this.retryPending(channel), 50, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            state.retrying = false;
        }
    }

    private void retryPending(Channel channel) {
        final var state = ChannelState.get(channel);
        if (state == null) return;
        state.retrying = false;
        if (!channel.isActive()) return;
        final var config = this.plugin.configValues();
        for (final var entry : state.targets.int2ObjectEntrySet()) {
            if (entry.getValue().pendingSelection) {
                this.refreshTarget(channel, entry.getIntKey(), entry.getValue(), config);
            }
        }
        channel.flush();
    }

    private void stopSubscription(TrackedPlayer target) {
        if (target.subscription == null) return;
        this.ticker.unsubscribe(target.subscription);
        target.subscription = null;
    }

    /**
     * Applies a prepared result to the current subscription in the viewer's EventLoop.
     * Returns false when no display could be created, so the frame is not recorded as applied.
     */
    private boolean apply(RenderTicker.Subscription subscription, TrackedPlayer target,
                          RenderTicker.Frame frame, ConfigValues config) {
        final var channel = subscription.viewer.channel;
        if (!this.canRender(channel, target, config)) {
            this.stopSubscription(target);
            target.hideTag(channel);
            return false;
        }
        if (subscription.definition instanceof ModernTag modern) {
            return this.renderModern(subscription, target, config.modernFrames(modern).get(frame.index()),
                    frame.encodedText());
        }
        return this.renderLegacy(subscription, target, (LegacyTag) subscription.definition, frame);
    }

    /**
     * Shows or updates the modern tag of a verified, renderable target in the viewer's EventLoop.
     * The tag identity and spawn position are needed only when the display is created.
     */
    private boolean renderModern(RenderTicker.Subscription subscription, TrackedPlayer target,
                                 CompiledFrame frame, byte[] text) {
        final var channel = subscription.viewer.channel;
        final byte opacity = frame.opacity(target.sneaking);
        if (target.display != null) {
            target.display.update(channel, frame, text, opacity);
            return true;
        }
        final var owner = Bukkit.getPlayer(subscription.ownerId);
        final var identity = target.ownerState;
        if (owner == null || identity == null) return false;
        this.renderTeam(channel, target,
                target.team == null ? owner.getName() : target.team.playerName,
                null, NamedTextColor.WHITE, Component.empty(), Component.empty(), false);
        // Only Spigot needs a Location; Paper reads the coordinates directly.
        final var location = PaperApi.ENTITY_COORDINATES ? null : owner.getLocation();
        final double x = location == null ? owner.getX() : location.getX();
        final double y = location == null ? owner.getY() : location.getY();
        final double z = location == null ? owner.getZ() : location.getZ();
        target.display = new ModernDisplay(channel, identity.tagEntityId, identity.tagUniqueId,
                x, y + 1.8, z, frame, text, opacity);
        // This pass runs after the owner's native spawn was written, so the client knows it.
        target.display.attach(channel, subscription.entityId, target.passengers);
        return true;
    }

    /**
     * Applies the legacy team and below-name score of a verified, renderable target in the
     * viewer's EventLoop, reusing the hiding team when switching from a modern tag.
     */
    private boolean renderLegacy(RenderTicker.Subscription subscription, TrackedPlayer target,
                                 LegacyTag definition, RenderTicker.Frame frame) {
        final var channel = subscription.viewer.channel;
        final String playerName;
        if (target.team != null) {
            playerName = target.team.playerName;
        } else {
            final var owner = Bukkit.getPlayer(subscription.ownerId);
            if (owner == null || target.ownerState == null) return false;
            playerName = owner.getName();
        }
        target.destroyDisplay(channel);
        this.renderTeam(channel, target, playerName, definition,
                definition.frames().get(frame.index()).nameColor(), frame.text(), frame.suffix(), true);
        this.ensureBelowName(channel, subscription.config);
        target.team.updateScore(channel, frame.score());
        return true;
    }

    private TrackedPlayer tracked(Channel viewer, int entityId) {
        final var state = ChannelState.get(viewer);
        return state == null ? null : state.targets.get(entityId);
    }

    /**
     * Whether the target's tag may be shown to this viewer. As in vanilla, a spectator viewer
     * sees invisible and spectator players with their tags; other viewers see neither. A
     * spectator's own tag stays hidden: it would follow the camera while spectating someone.
     */
    private boolean canRender(Channel viewer, TrackedPlayer target, ConfigValues config) {
        if (target.dead || config.hideTagWhenHasPassenger() && target.passengers.length != 0) return false;
        final var state = ChannelState.get(viewer);
        if (state == null) return !target.invisible;
        if (state.spectatorSelf != null) return !target.ownerId.equals(state.spectatorSelf);
        return !target.invisible && (state.spectators == null || !state.spectators.contains(target.ownerId));
    }

    /**
     * Reattaches a surviving display once the native spawn being handled has been written.
     * Packets written now would reach the client before the spawn, which ignores passengers
     * of an entity it does not know; a task queued now runs right after that write.
     */
    private void attachAfterSpawn(Channel viewer, int ownerEntityId, TrackedPlayer target, ConfigValues config) {
        final var display = target.display;
        viewer.eventLoop().execute(() -> {
            if (!viewer.isActive() || this.tracked(viewer, ownerEntityId) != target || target.display != display) return;
            if (!this.canRender(viewer, target, config)) {
                this.stopSubscription(target);
                target.destroyDisplay(viewer);
            } else {
                display.attach(viewer, ownerEntityId, target.passengers);
            }
            viewer.flush();
        });
    }

    private static int[] withoutPassenger(int[] passengers, int entityId) {
        int count = 0;
        for (final int passenger : passengers) {
            if (passenger != entityId) count++;
        }
        if (count == passengers.length) return passengers;
        if (count == 0) return IntArrays.EMPTY_ARRAY;
        final int[] filtered = new int[count];
        int index = 0;
        for (final int passenger : passengers) {
            if (passenger != entityId) filtered[index++] = passenger;
        }
        return filtered;
    }

    /** Records the advertised mode and returns whether it differs from the previous one. */
    private boolean setSpectatorProfile(Channel viewer, UUID ownerId, boolean spectator) {
        final var state = spectator ? ChannelState.of(viewer, this) : ChannelState.get(viewer);
        if (state == null) return false;
        if (spectator) {
            if (state.spectators == null) state.spectators = new ObjectOpenHashSet<>();
            return state.spectators.add(ownerId);
        }
        return state.spectators != null && state.spectators.remove(ownerId);
    }

    private TrackedPlayer getOrCreate(Channel viewer, int entityId, UUID ownerId) {
        final var state = ChannelState.of(viewer, this);
        final var targets = state.targets;
        if (targets.isEmpty()) this.ticker.watch(state);
        final var target = targets.get(entityId);
        if (target != null && target.ownerId.equals(ownerId)) return target;
        if (target != null) {
            this.stopSubscription(target);
            target.destroyDisplay(viewer);
            target.destroyTeam(viewer);
        }
        final var created = new TrackedPlayer(ownerId);
        targets.put(entityId, created);
        return created;
    }

    static final class SourceTeams {

        private final Map<String, NamedTextColor> colors = new HashMap<>();
        private final Map<String, String> membership = new HashMap<>();

        private void remove(String name) {
            this.colors.remove(name);
            final var iterator = this.membership.values().iterator();
            while (iterator.hasNext()) {
                if (name.equals(iterator.next())) iterator.remove();
            }
        }
    }

    private static final class ClientTeam {

        private final String name;
        private final String playerName;
        private Component prefix;
        private Component suffix;
        private NamedTextColor color;
        private boolean visible;
        private LegacyTag definition;
        private NamedTextColor fallbackColor;
        private boolean scorePresent;
        private int score;

        private ClientTeam(String name, String playerName, Component prefix, Component suffix,
                           NamedTextColor color, boolean visible) {
            this.name = name;
            this.playerName = playerName;
            this.prefix = prefix;
            this.suffix = suffix;
            this.color = color;
            this.visible = visible;
        }

        private void update(Channel viewer, Component prefix, Component suffix, NamedTextColor color, boolean visible) {
            if (this.visible == visible && this.color == color
                    && this.prefix.equals(prefix) && this.suffix.equals(suffix)) return;
            LegacyRenderer.update(viewer, this.name, prefix, suffix, color, visible);
            this.prefix = prefix;
            this.suffix = suffix;
            this.color = color;
            this.visible = visible;
        }

        private void updateScore(Channel viewer, int value) {
            if (this.scorePresent && this.score == value) return;
            LegacyRenderer.updateScore(viewer, this.playerName, value);
            this.score = value;
            this.scorePresent = true;
        }

        private void removeScore(Channel viewer) {
            if (!this.scorePresent) return;
            LegacyRenderer.removeScore(viewer, this.playerName);
            this.scorePresent = false;
        }
    }

    static final class TrackedPlayer {

        private final UUID ownerId;
        private boolean dead;
        private boolean invisible;
        private boolean sneaking;
        private int[] passengers = IntArrays.EMPTY_ARRAY;
        private ModernDisplay display;
        private ClientTeam team;
        private RenderTicker.Subscription subscription;
        private boolean pendingSelection = true;
        /** Owner connection state, resolved by the last trigger-time selection. */
        private ChannelState ownerState;
        /** Snapshots the current selection was reconciled with; a different published one means reselect. */
        private ChannelState.OwnerSnapshot seenOwner;
        private ChannelState.ViewerSnapshot seenViewer;

        private TrackedPlayer(UUID ownerId) {
            this.ownerId = ownerId;
        }

        private void destroyDisplay(Channel viewer) {
            if (this.display == null) return;
            this.display.destroy(viewer);
            this.display = null;
        }

        private void hideTag(Channel viewer) {
            this.destroyDisplay(viewer);
            if (this.team != null) {
                this.team.update(viewer, this.team.prefix, this.team.suffix, this.team.color, false);
                this.team.removeScore(viewer);
            }
        }

        private void destroyTeam(Channel viewer) {
            if (this.team == null) return;
            this.team.removeScore(viewer);
            LegacyRenderer.destroy(viewer, this.team.name);
            this.team = null;
        }
    }
}
