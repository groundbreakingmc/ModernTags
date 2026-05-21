package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Posts a {@link RenderTask.Cleanup} when a DISCONNECT packet is sent to the player.
 */
public final class DisconnectHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public DisconnectHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        this.renderLoop.post(new RenderTask.Cleanup(event.getPlayer()));
    }
}
