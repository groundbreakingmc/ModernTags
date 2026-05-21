package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Stops rendering a player's tag on death (entity status byte {@code 3}).
 */
public final class DeathHandler implements ClientBoundPacketHandler {

    private static final byte DEATH_STATUS = 3;

    private final RenderLoop renderLoop;

    public DeathHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new WrapperPlayServerEntityStatus(event);
        if (packet.getStatus() != DEATH_STATUS) return;

        final Player viewer = event.getPlayer();
        final Player target = viewer.getEntityId() == packet.getEntityId()
                ? viewer
                : PlayerLookup.playerById(packet.getEntityId());
        if (target == null) return;

        this.renderLoop.post(new RenderTask.StopRendering(target, viewer));
    }
}
