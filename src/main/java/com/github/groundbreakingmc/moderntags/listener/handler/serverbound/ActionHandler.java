
package com.github.groundbreakingmc.moderntags.listener.handler.serverbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ServerBoundPacketHandler;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks sneaking actions sent by older Minecraft clients.
 *
 * <p>The packet's entity ID is ignored because sneaking actions
 * always affect the sending player, regardless of the supplied ID.
 *
 * <p>Reuses the previously decoded wrapper when available.
 */
public final class ActionHandler implements ServerBoundPacketHandler {

    private final RenderLoop renderLoop;

    public ActionHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketReceiveEvent event) {
        // Reuse the decoded packet to avoid unnecessary wrapper creation.
        final var previous = event.getLastUsedWrapper();
        final var packet = previous instanceof WrapperPlayClientEntityAction actionPacket
                ? actionPacket
                : new WrapperPlayClientEntityAction(event);

        final var action = packet.getAction();
        final boolean sneaking;

        if (action == WrapperPlayClientEntityAction.Action.START_SNEAKING) {
            sneaking = true;
        } else if (action == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) {
            sneaking = false;
        } else {
            return;
        }

        // Serverbound sneaking actions always belong to the sender.
        final Player player = event.getPlayer();

        this.renderLoop.post(new RenderTask.SuppressChange(
                player, player, ViewerState.SUPPRESS_SNEAK, sneaking));
    }
}
