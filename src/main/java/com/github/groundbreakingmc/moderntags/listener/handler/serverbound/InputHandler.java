package com.github.groundbreakingmc.moderntags.listener.handler.serverbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ServerBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class InputHandler implements ServerBoundPacketHandler {

    private final RenderLoop renderLoop;

    public InputHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketReceiveEvent event) {
        final Player viewer = event.getPlayer();
        final byte flags = ByteBufHelper.readByte(event.getByteBuf());

        if ((flags & (1 << 5)) != 0) {
            this.renderLoop.post(new RenderTask.SuppressChange(viewer, viewer, ViewerState.SUPPRESS_SNEAK, true));
        } else {
            this.renderLoop.post(new RenderTask.SuppressChange(viewer, viewer, ViewerState.SUPPRESS_SNEAK, false));
        }
    }
}
