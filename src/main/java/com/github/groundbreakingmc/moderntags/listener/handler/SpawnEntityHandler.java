package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.manager.PlayerTagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles SPAWN_ENTITY packets to show player tags when players spawn for viewers.
 */
public final class SpawnEntityHandler implements PacketHandler {

    private final ModernTags plugin;
    private final PlayerTagManager tagManager;

    public SpawnEntityHandler(@NotNull ModernTags plugin, @NotNull PlayerTagManager tagManager) {
        this.plugin = plugin;
        this.tagManager = tagManager;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var wrapper = new PacketWrapper<>(event, false);
        wrapper.readVarInt(); // skipping entity ID
        final UUID uniqueId = wrapper.readUUID();
        final EntityType entityType = EntityTypes.getById(
                wrapper.getServerVersion().toClientVersion(),
                wrapper.readVarInt()
        );

        if (entityType != EntityTypes.PLAYER) {
            return;
        }

        final Player packetReceiver = event.getPlayer();
        final Player spawnedPlayer = Bukkit.getPlayer(uniqueId);

        if (spawnedPlayer != null) {
            // Schedule for later to ensure both players are fully loaded for each other
            Bukkit.getAsyncScheduler().runDelayed(this.plugin, (task) -> {
                this.tagManager.showPlayerTag(spawnedPlayer, packetReceiver);
            }, 1L, TimeUnit.MILLISECONDS);
        }
    }
}
