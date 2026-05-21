package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Re-renders a player's own tag on PLAYER_POSITION_AND_LOOK.
 * Handles self-spawns that {@link SpawnEntityHandler} intentionally skips.
 */
public final class PlayerPositionHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;

    public PlayerPositionHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final Player player = event.getPlayer();
        this.renderLoop.post(new RenderTask.StopRendering(player, player));
        this.renderLoop.post(new RenderTask.Render(player, player));
    }
}
