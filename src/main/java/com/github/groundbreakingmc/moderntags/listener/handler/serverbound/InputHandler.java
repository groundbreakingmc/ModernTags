
package com.github.groundbreakingmc.moderntags.listener.handler.serverbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ServerBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks the client's sneaking state through the PLAYER_INPUT packet.
 *
 * <p>Reuses a previously decoded wrapper to preserve earlier input changes.
 * Otherwise reads the input flags directly without creating a wrapper and
 * restores the buffer position for subsequent packet listeners.
 */
public final class InputHandler implements ServerBoundPacketHandler {

    private static final int SNEAK_FLAG = 1 << 5;

    private final RenderLoop renderLoop;

    public InputHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketReceiveEvent event) {
        final var previous = event.getLastUsedWrapper();

        final boolean sneaking;
        if (previous instanceof WrapperPlayClientPlayerInput packet) {
            sneaking = packet.isShift();
        } else {
            final Object buffer = event.getByteBuf();
            final int readerIndex = ByteBufHelper.readerIndex(buffer);

            try {
                // PLAYER_INPUT contains a single byte of input flags.
                sneaking = (ByteBufHelper.readByte(buffer) & SNEAK_FLAG) != 0;
            } finally {
                // PacketEvents and later listeners must see the original buffer position.
                ByteBufHelper.readerIndex(buffer, readerIndex);
            }
        }

        // Serverbound sneaking actions always belong to the sender.
        final Player player = event.getPlayer();

        this.renderLoop.post(new RenderTask.SuppressChange(
                player, player, ViewerState.SUPPRESS_SNEAK, sneaking));
    }
}
