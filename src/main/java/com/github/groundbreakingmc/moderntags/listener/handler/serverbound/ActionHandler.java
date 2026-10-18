package com.github.groundbreakingmc.moderntags.listener.handler.serverbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ServerBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ActionHandler implements ServerBoundPacketHandler {

    private final RenderLoop renderLoop;

    public ActionHandler(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketReceiveEvent event) {
        final var packet = new WrapperPlayClientEntityAction(event);

        final var action = packet.getAction();
        if (action != WrapperPlayClientEntityAction.Action.START_SNEAKING
                && action != WrapperPlayClientEntityAction.Action.STOP_SNEAKING) return;

        final var target = PlayerLookup.playerById(packet.getEntityId());
        if (target == null) return;
        final Player viewer = event.getPlayer();
        this.renderLoop.post(new RenderTask.SuppressChange(target, viewer, ViewerState.SUPPRESS_SNEAK,
                action == WrapperPlayClientEntityAction.Action.START_SNEAKING));
    }
}
