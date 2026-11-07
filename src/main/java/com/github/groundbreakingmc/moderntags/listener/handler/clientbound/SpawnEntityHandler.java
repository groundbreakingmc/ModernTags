package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.jetbrains.annotations.NotNull;
import io.netty.channel.Channel;

import java.util.UUID;

public final class SpawnEntityHandler {

    private final TagManager tags;
    private final int playerType;

    /**
     * Connects spawn handling to the manager and resolves the server's player entity type.
     */
    public SpawnEntityHandler(@NotNull TagManager tags) {
        this.tags = tags;
        this.playerType = EntityTypes.PLAYER.getId(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
    }

    /**
     * Reads player spawns without decoding the remaining entity data or changing the packet.
     */
    public void handle(@NotNull PacketSendEvent event, @NotNull ConfigValues config) {
        final var wrapper = event.getLastUsedWrapper();
        if (wrapper instanceof WrapperPlayServerSpawnEntity packet) {
            this.handleDecoded(event, packet, config);
        } else if (wrapper instanceof WrapperPlayServerSpawnPlayer packet) {
            this.tags.track((Channel) event.getChannel(), packet.getEntityId(), packet.getUUID(), config);
        } else {
            this.handleNative(event, config);
        }
    }

    private void handleDecoded(PacketSendEvent event, WrapperPlayServerSpawnEntity packet, ConfigValues config) {
        if (packet.getEntityType() != EntityTypes.PLAYER) return;
        final var uniqueId = packet.getUUID().orElse(null);
        if (uniqueId == null) return;
        this.tags.track((Channel) event.getChannel(), packet.getEntityId(), uniqueId, config);
    }

    private void handleNative(PacketSendEvent event, ConfigValues config) {
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        final int entityId;
        final UUID uniqueId;
        try {
            entityId = ByteBufHelper.readVarInt(buffer);
            final long most = ByteBufHelper.readLong(buffer);
            final long least = ByteBufHelper.readLong(buffer);
            if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY
                    && ByteBufHelper.readVarInt(buffer) != this.playerType) return;
            uniqueId = new UUID(most, least);
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
        this.tags.track((Channel) event.getChannel(), entityId, uniqueId, config);
    }
}
