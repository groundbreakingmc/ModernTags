package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Renders a player's tag on SPAWN_ENTITY or the older SPAWN_PLAYER packet.
 *
 * <p>Self-spawns are skipped — handled by {@link PlayerPositionHandler} via PLAYER_POSITION_AND_LOOK.
 * The render loop registers the spawn immediately and validates its 1 ms delayed render.
 */
public final class SpawnEntityHandler implements ClientBoundPacketHandler {

    private final ModernTags plugin;
    private final RenderLoop renderLoop;
    private final ClientVersion serverVersion;

    public SpawnEntityHandler(@NotNull ModernTags plugin, @NotNull RenderLoop renderLoop) {
        this.plugin = plugin;
        this.renderLoop = renderLoop;
        this.serverVersion = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final Player viewer = event.getPlayer();
        final var wrapper = new PacketWrapper<>(event, false);

        final int entityId = wrapper.readVarInt();
        if (entityId == viewer.getEntityId()) return; // self-spawn → PlayerPositionHandler

        final UUID uniqueId = wrapper.readUUID();

        // SPAWN_PLAYER carries no entity-type field before its coordinates.
        if (event.getPacketType() != PacketType.Play.Server.SPAWN_PLAYER
                && EntityTypes.getById(this.serverVersion, wrapper.readVarInt()) != EntityTypes.PLAYER) return;

        final Player target = Bukkit.getPlayer(uniqueId);
        if (target == null) return;

        this.renderLoop.post(new RenderTask.ScheduleRender(target, viewer, this.plugin));
    }
}
