
package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class SpawnEntityHandler implements ClientBoundPacketHandler {

    private static final int PLAYER_ENTITY_TYPE_ID = EntityTypes.PLAYER.getId(
            PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());

    private final ModernTags plugin;
    private final RenderLoop renderLoop;

    public SpawnEntityHandler(@NotNull ModernTags plugin, @NotNull RenderLoop renderLoop) {
        this.plugin = plugin;
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final Player viewer = event.getPlayer();
        final var wrapper = event.getLastUsedWrapper();
        final var packetType = event.getPacketType();

        final Player target;

        if (packetType == PacketType.Play.Server.SPAWN_ENTITY
                && wrapper instanceof WrapperPlayServerSpawnEntity packet) {
            if (packet.getEntityType() != EntityTypes.PLAYER) return;
            target = findTarget(packet.getEntityId(), packet.getUUID().orElse(null), viewer);

        } else if (packetType == PacketType.Play.Server.SPAWN_PLAYER
                && wrapper instanceof WrapperPlayServerSpawnPlayer packet) {
            target = findTarget(packet.getEntityId(), packet.getUUID(), viewer);

        } else {
            target = readTarget(event, viewer);
        }

        if (target == null) return;
        this.renderLoop.post(new RenderTask.ScheduleRender(target, viewer, this.plugin));
    }

    private static @Nullable Player readTarget(PacketSendEvent event, Player viewer) {
        final Object buffer = event.getByteBuf();
        final int index = ByteBufHelper.readerIndex(buffer);

        try {
            final int entityId = ByteBufHelper.readVarInt(buffer);
            if (entityId == viewer.getEntityId()) return null;

            final Player target = PlayerLookup.playerById(entityId);
            if (target == null) return null;

            final UUID uuid = target.getUniqueId();

            if (ByteBufHelper.readLong(buffer) != uuid.getMostSignificantBits()
                    || ByteBufHelper.readLong(buffer) != uuid.getLeastSignificantBits()) {
                return null;
            }

            if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY
                    && ByteBufHelper.readVarInt(buffer) != PLAYER_ENTITY_TYPE_ID) {
                return null;
            }

            return target;
        } finally {
            ByteBufHelper.readerIndex(buffer, index);
        }
    }

    private static @Nullable Player findTarget(int entityId, @Nullable UUID uuid, Player viewer) {
        if (entityId == viewer.getEntityId()) return null;

        final Player target = PlayerLookup.playerById(entityId);
        return target != null && target.getUniqueId().equals(uuid) ? target : null;
    }
}
