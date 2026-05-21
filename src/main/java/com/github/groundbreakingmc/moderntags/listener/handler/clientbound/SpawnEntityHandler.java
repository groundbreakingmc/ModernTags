package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Renders a player's tag when their SPAWN_ENTITY packet arrives at the viewer.
 *
 * <p>Self-spawns are skipped — handled by {@link PlayerPositionHandler} via PLAYER_POSITION_AND_LOOK.
 * A 1 ms async delay is applied so both sides are fully tracked before the render is posted.
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

        if (EntityTypes.getById(this.serverVersion, wrapper.readVarInt()) != EntityTypes.PLAYER) return;

        final Player target = Bukkit.getPlayer(uniqueId);
        if (target == null) return;

        Bukkit.getAsyncScheduler().runDelayed(this.plugin, task ->
                        this.renderLoop.post(new RenderTask.Render(target, viewer)),
                1L, TimeUnit.MILLISECONDS);
    }
}
