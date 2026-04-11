package com.github.groundbreakingmc.moderntags.manager;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.config.model.TagFrame;
import com.github.groundbreakingmc.moderntags.config.model.TagTemplate;
import com.github.groundbreakingmc.moderntags.text.PlaceholderParser;
import com.github.groundbreakingmc.moderntags.utils.PlayerLookup;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.google.common.collect.ImmutableList;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;

public final class PlayerTagManager {

    private static final int PASSENGER_DELAY_TICKS = 2;

    private final ModernTags plugin;
    private final PlaceholderParser placeholderParser;

    private final Map<Player, PlayerData> playerDataCache = new Reference2ObjectOpenHashMap<>();

    private TagTemplate defaultTag;
    private List<TagTemplate> permissionTags;
    private boolean hideTagWhenHasPassenger;

    public PlayerTagManager(@NotNull ModernTags plugin,
                            @NotNull PlaceholderParser placeholderParser) {
        this.plugin = plugin;
        this.placeholderParser = placeholderParser;
    }

    public void initializeAll() {
        Bukkit.getOnlinePlayers().forEach(holder -> {
            for (final Player viewer : holder.getTrackedBy()) {
                this.showPlayerTag(viewer, holder);
            }
            this.showPlayerTag(holder, holder);
        });
    }

    public void setTags(Map<String, TagTemplate> tags) {
        this.defaultTag = Objects.requireNonNull(
                tags.get("default"),
                "default tag can't be null!"
        );

        this.permissionTags = tags.values().stream()
                .filter(tag -> tag != this.defaultTag)
                .sorted(Comparator.comparingInt(TagTemplate::priority).reversed())
                .collect(ImmutableList.toImmutableList());
    }

    public void hideTagWhenHasPassenger(boolean hide) {
        this.hideTagWhenHasPassenger = hide;
    }

    public void showPlayerTag(@NotNull Player target, @NotNull Player viewer) {
        this.hidePlayerTag(target, viewer);

        if (!this.canSeeTag(target, viewer)) {
            return;
        }

        final PlayerData targetData = this.getPlayerData(target);

        final var spawnPacket = PacketFactory.createSpawnPacket(targetData.tagEntityId, target.getLocation());
        final var metadataPacket = this.createMetadataPacket(targetData);

        final var playerManager = PacketEvents.getAPI().getPlayerManager();
        final var channel = playerManager.getChannel(viewer);

        final var protocolManager = PacketEvents.getAPI().getProtocolManager();
        protocolManager.sendPacketSilently(channel, spawnPacket);
        protocolManager.sendPacketSilently(channel, metadataPacket);

        // run task sync to be sure, that we don't cause any issue with passengers
        target.getScheduler().runDelayed(this.plugin, (task) -> {
            if (target.isOnline() && target.isValid() && viewer.isOnline() && viewer.isValid()) {
                final var setPassengerPacket = PacketFactory.createSetPassengersPacket(target, targetData.tagEntityId);
                protocolManager.sendPacketSilently(channel, setPassengerPacket);
            }
        }, null, PASSENGER_DELAY_TICKS);

        final PlayerData viewerData = this.getPlayerData(viewer);
        viewerData.visibleTags.add(target);
        viewerData.temporarilyHiddenDueToPassengers.remove(target);
    }

    public void hidePlayerTag(@NotNull Player target, @NotNull Player viewer) {
        final PlayerData viewerData = this.getPlayerDataIfExists(viewer);
        if (viewerData == null || !viewerData.visibleTags.contains(target))
            return; // return if viewer doesn't actually see target's tag

        final PlayerData targetData = this.getPlayerData(target);

        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                PacketFactory.createDestroyPacket(targetData.tagEntityId)
        );

        viewerData.visibleTags.remove(target);
        viewerData.temporarilyHiddenDueToPassengers.remove(target);
    }

    public boolean fixTagMounting(@NotNull Player target, @NotNull Player viewer, int[] passengerIds) {
        final PlayerData viewerData = this.getPlayerDataIfExists(viewer);
        if (viewerData == null || !viewerData.visibleTags.contains(target))
            return false; // return if viewer doesn't actually see target's tag

        final PlayerData targetData = this.getPlayerData(target);

        if (this.hideTagWhenHasPassenger) {
            if (passengerIds.length == 0) {
                // No passengers - restore tag if it was temporarily hidden
                if (viewerData.temporarilyHiddenDueToPassengers.contains(target)) {

                    final var spawnPacket = PacketFactory.createSpawnPacket(targetData.tagEntityId, target.getLocation());
                    final var metadataPacket = this.createMetadataPacket(targetData);
                    final var setPassengerPacket = PacketFactory.createSetPassengersPacket(target, targetData.tagEntityId);

                    final var playerManager = PacketEvents.getAPI().getPlayerManager();
                    playerManager.sendPacketSilently(viewer, spawnPacket);
                    playerManager.sendPacketSilently(viewer, metadataPacket);
                    playerManager.sendPacketSilently(viewer, setPassengerPacket);

                    viewerData.temporarilyHiddenDueToPassengers.remove(target);
                }
            } else {
                // Has passengers - temporarily hide tag (mark as temporarily hidden)
                if (!viewerData.temporarilyHiddenDueToPassengers.contains(target)) {

                    final var playerManager = PacketEvents.getAPI().getPlayerManager();
                    playerManager.sendPacketSilently(viewer,
                            PacketFactory.createDestroyPacket(targetData.tagEntityId));
                    playerManager.sendPacketSilently(viewer,
                            PacketFactory.createSetPassengersPacket(target, passengerIds));
                    viewerData.temporarilyHiddenDueToPassengers.add(target);
                }
            }
            return true;
        }

        final int[] newRidersIds = Arrays.copyOf(passengerIds, passengerIds.length + 1);
        newRidersIds[newRidersIds.length - 1] = targetData.tagEntityId;
        final var setPassengersPacket = PacketFactory.createSetPassengersPacket(target, newRidersIds);

        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, setPassengersPacket);

        return true;
    }

    public void cleanup(@NotNull Player player) {
        this.removePlayerTagFromAllViewers(player);

        this.removeOtherTagsFromPlayer(player);

        synchronized (this.playerDataCache) {
            this.playerDataCache.remove(player);
        }
    }

    private void removePlayerTagFromAllViewers(@NotNull Player player) {
        final PlayerData playerData = this.getPlayerData(player);
        final var removePacket = PacketFactory.createDestroyPacket(playerData.tagEntityId);
        final var playerManager = PacketEvents.getAPI().getPlayerManager();

        synchronized (this.playerDataCache) {
            this.playerDataCache.entrySet().removeIf(entry -> {
                final Player viewer = entry.getKey();
                final PlayerData viewerData = entry.getValue();

                if (viewer == null || !viewer.isOnline()) {
                    return true;
                }

                if (viewer.isValid() && viewerData.visibleTags.remove(player)) {
                    playerManager.sendPacketSilently(viewer, removePacket);
                }
                viewerData.temporarilyHiddenDueToPassengers.remove(player);

                return viewerData.visibleTags.isEmpty()
                        && viewerData.temporarilyHiddenDueToPassengers.isEmpty();
            });
        }
    }

    private void removeOtherTagsFromPlayer(@NotNull Player player) {
        final PlayerData playerData = this.getPlayerDataIfExists(player);
        if (playerData == null) {
            return;
        }

        final User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) {
            return;
        }

        playerData.visibleTags.forEach(target -> {
            final PlayerData targetData = this.getPlayerData(target);
            final var removePacket = PacketFactory.createDestroyPacket(targetData.tagEntityId);
            user.sendPacketSilently(removePacket);
        });
    }

    void updateVisibility(@NotNull Player target) {
        target.getTrackedBy().forEach(viewer -> this.updateVisibility(target, viewer));
        this.updateVisibility(target, target);
    }

    public boolean updateTag(@NotNull Player target) {
        final PlayerData targetData = this.getPlayerData(target);
        final TagTemplate template = this.tagByPermission(target);

        if (!targetData.tagTemplate.equals(template)) {
            targetData.tagTemplate = template;
            final var metadataPacket = this.createMetadataPacket(targetData);

            // Send updates to viewers who actually see the tag
            synchronized (this.playerDataCache) {
                this.playerDataCache.entrySet().removeIf(entry -> {
                    final Player viewer = entry.getKey();
                    final PlayerData viewerData = entry.getValue();

                    if (!viewer.isOnline()) {
                        this.cleanup(viewer);
                        return true; // Remove this entry
                    } else if (viewer.isValid() && viewerData.visibleTags.contains(target)
                            && !viewerData.temporarilyHiddenDueToPassengers.contains(target)) {
                        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, metadataPacket);
                    }
                    return false;
                });
            }

            return true;
        }

        return false;
    }

    void updateFrame(@NotNull Player target) {
        final PlayerData targetData = this.getPlayerData(target);

        final List<TagFrame> frames = targetData.tagTemplate.frames();
        if (frames.size() <= 1) return;

        final int nextFrame = (targetData.currentFrame + 1) % frames.size();
        final TagFrame frame = frames.get(nextFrame);

        this.updateCachedMetadata(targetData, target, frame);
        targetData.currentFrame = nextFrame;

        this.broadcastMetadataUpdate(target, targetData);
    }

    void updatePlaceholders(@NotNull Player target) {
        final PlayerData targetData = this.getPlayerData(target);

        final TagFrame frame = targetData.tagTemplate.frames().get(targetData.currentFrame);

        this.updateCachedMetadata(targetData, target, frame);
        this.broadcastMetadataUpdate(target, targetData);
    }

    private @NotNull TagTemplate tagByPermission(@NotNull Player player) {
        if (this.permissionTags.isEmpty()) {
            return this.defaultTag;
        }

        for (final TagTemplate tag : this.permissionTags) {
            if (player.hasPermission("moderns.tags." + tag.key())) {
                return tag;
            }
        }

        return this.defaultTag;
    }

    // ===== HELPER METHODS =====

    private WrapperPlayServerEntityMetadata createMetadataPacket(PlayerData playerData) {
        final TagFrame frame = playerData.tagTemplate.frames().get(playerData.currentFrame);
        return PacketFactory.createMetadataPacket(playerData.tagEntityId, frame, playerData.text);
    }

    public boolean canSeeTag(Player target, Player viewer) {
        if (!target.isOnline() || !target.isValid() || target.isDead()
                || !PlayerLookup.canSee(viewer, target)) {
            return false;
        }

        return viewer.hasPermission(target == viewer ? "moderntags.see.own" : "moderntags.see.other");
    }

    public boolean seeTag(Player target, Player viewer) {
        final PlayerData viewerData = this.getPlayerDataIfExists(viewer);
        return viewerData != null && viewerData.visibleTags.contains(target);
    }

    public void updateVisibility(@NotNull Player target, @NotNull Player viewer) {
        final boolean shouldSee = this.canSeeTag(target, viewer);
        final boolean doesSee = this.seeTag(target, viewer);

        // Don't interfere with tags temporarily hidden due to passengers
        final PlayerData viewerData = this.getPlayerDataIfExists(viewer);
        final boolean isTemporarilyHidden = viewerData != null && viewerData.temporarilyHiddenDueToPassengers.contains(target);

        if (shouldSee != doesSee && !isTemporarilyHidden) {
            if (shouldSee) {
                this.showPlayerTag(target, viewer);
            } else {
                this.hidePlayerTag(target, viewer);
            }
        }
    }

    private void updateCachedMetadata(PlayerData playerData, Player player, TagFrame frame) {
        playerData.text = this.placeholderParser.parsePlaceholders(player, frame.text());
    }

    private void broadcastMetadataUpdate(Player player, PlayerData playerData) {
        if (!player.isOnline()) {
            this.cleanup(player);
            return;
        }
        if (!player.isValid()) return;

        final var metadataPacket = this.createMetadataPacket(playerData);
        final var playerManager = PacketEvents.getAPI().getPlayerManager();

        // Send updates to viewers who actually see the tag (not temporarily hidden)
        synchronized (this.playerDataCache) {
            this.playerDataCache.entrySet().removeIf(entry -> {
                final Player viewer = entry.getKey();
                final PlayerData viewerData = entry.getValue();

                if (!viewer.isOnline()) {
                    this.cleanup(viewer);
                    return true; // Remove this entry
                } else if (viewer.isValid() && viewerData.visibleTags.contains(player)
                        && !viewerData.temporarilyHiddenDueToPassengers.contains(player)) {
                    playerManager.sendPacket(viewer, metadataPacket);
                }
                return false;
            });
        }
    }

    // ===== CACHE ACCESS WITH DOUBLE-CHECKED LOCKING =====

    @SuppressWarnings("UnstableApiUsage")
    @NotNull
    PlayerData getPlayerData(Player player) {
        PlayerData data = this.playerDataCache.get(player);
        if (data != null) return data;

        synchronized (this.playerDataCache) {
            data = this.playerDataCache.get(player);
            if (data != null) return data;

            this.playerDataCache.put(player, data = new PlayerData(
                    this.tagByPermission(player),
                    SpigotReflectionUtil.generateEntityId(),
                    frame -> this.placeholderParser.parsePlaceholders(player, frame.text())
            ));

            return data;
        }
    }

    private PlayerData getPlayerDataIfExists(Player player) {
        return this.playerDataCache.get(player);
    }

    static final class PlayerData {

        private TagTemplate tagTemplate;
        private final int tagEntityId;
        private Component text;
        private int currentFrame;
        private int ticksSinceLastUpdate;

        private final ReferenceSet<Player> visibleTags;
        private final ReferenceSet<Player> temporarilyHiddenDueToPassengers;

        PlayerData(TagTemplate tagTemplate, int tagEntityId, Function<TagFrame, Component> textFunction) {
            // CacheEntry fields
            this.tagTemplate = tagTemplate;
            this.tagEntityId = tagEntityId;
            this.text = textFunction.apply(tagTemplate.frames().get(0));
            this.currentFrame = 0;
            this.ticksSinceLastUpdate = 0;

            // ViewerState fields
            this.visibleTags = ReferenceSets.synchronize(new ReferenceOpenHashSet<>());
            this.temporarilyHiddenDueToPassengers = ReferenceSets.synchronize(new ReferenceOpenHashSet<>());
        }

        public TagTemplate tagTemplate() {
            return this.tagTemplate;
        }

        public int ticksSinceLastUpdate() {
            return this.ticksSinceLastUpdate;
        }

        public void incrementTicks() {
            this.ticksSinceLastUpdate++;
            if (this.ticksSinceLastUpdate < 0) {
                this.ticksSinceLastUpdate = 0;
            }
        }

        public void resetTicks() {
            this.ticksSinceLastUpdate = 0;
        }
    }
}