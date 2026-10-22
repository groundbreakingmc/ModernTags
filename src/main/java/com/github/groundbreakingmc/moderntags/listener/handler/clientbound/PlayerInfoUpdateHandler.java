
package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Refreshes game-mode visibility independently of tab-list profile membership.
 */
public final class PlayerInfoUpdateHandler implements ClientBoundPacketHandler {

    private static final int GAME_MODE_ACTION =
            1 << WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE.ordinal();

    // readEnumSet uses more than one byte if the library adds a ninth action.
    private static final boolean SINGLE_ACTION_BYTE =
            WrapperPlayServerPlayerInfoUpdate.Action.VALUES.length <= 8;

    private final RenderLoop renderLoop;

    /**
     * Creates a handler that forwards player-info changes to the render loop.
     *
     * @param renderLoop the owner of tracked tag state
     */
    public PlayerInfoUpdateHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    /**
     * Refreshes tracked tags for game-mode changes; profile membership does not change tracking.
     *
     * @param event the outgoing player-info update
     */
    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var previous = event.getLastUsedWrapper();

        if (previous instanceof WrapperPlayServerPlayerInfoUpdate packet) {
            // Preserve earlier changes to the action set or individual entries.
            this.handleDecoded(packet, event);
            return;
        }

        if (!handleNative(event, this.renderLoop)) {
            this.handleDecoded(new WrapperPlayServerPlayerInfoUpdate(event), event);
        }
    }

    private void handleDecoded(WrapperPlayServerPlayerInfoUpdate packet, PacketSendEvent event) {
        if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return;

        final Player viewer = event.getPlayer();

        for (final var entry : packet.getEntries()) {
            final var target = Bukkit.getPlayer(entry.getGameProfile().getUUID());
            if (target == null) continue;

            this.renderLoop.post(new RenderTask.GameModeUpdate(target, viewer));
        }
    }

    /**
     * Handles packets with no game-mode action or only UPDATE_GAME_MODE without
     * decoding profiles, chat sessions, or an entry collection.
     *
     * @return {@code false} if other actions require the full PacketEvents decoder
     */
    private static boolean handleNative(PacketSendEvent event, RenderLoop renderLoop) {
        if (!SINGLE_ACTION_BYTE) return false;

        final Object buffer = event.getByteBuf();
        final int index = ByteBufHelper.readerIndex(buffer);

        try {
            final int actions = ByteBufHelper.readByte(buffer) & 0xFF;
            if ((actions & GAME_MODE_ACTION) == 0) return true;
            if (actions != GAME_MODE_ACTION) return false;

            final int count = ByteBufHelper.readVarInt(buffer);
            if (count < 0) return false;
            if (count == 0) return true;

            final Player viewer = event.getPlayer();

            for (int i = 0; i < count; i++) {
                final long most = ByteBufHelper.readLong(buffer);
                final long least = ByteBufHelper.readLong(buffer);

                // The actual game-mode value is not needed for visibility refresh.
                ByteBufHelper.readVarInt(buffer);

                final Player target = Bukkit.getPlayer(new UUID(most, least));
                if (target != null) {
                    renderLoop.post(new RenderTask.GameModeUpdate(target, viewer));
                }
            }

            return true;
        } finally {
            // Subsequent listeners must observe the original packet position.
            ByteBufHelper.readerIndex(buffer, index);
        }
    }
}
