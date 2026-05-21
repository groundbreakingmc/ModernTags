package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Suppresses a player's own tag when an invisibility effect is applied or removed.
 */
public final class SelfInvisibilityHandler implements ClientBoundPacketHandler {

    private final RenderLoop renderLoop;
    private final ClientVersion serverVersion;

    public SelfInvisibilityHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
        this.serverVersion = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        final var packet = new PacketWrapper<>(event, false);
        final int targetEntityId = packet.readVarInt();
        final Player viewer = event.getPlayer();
        if (viewer.getEntityId() != targetEntityId) return;

        final PotionType potionType = PotionTypes.getById(this.serverVersion, packet.readVarInt());
        if (potionType != PotionTypes.INVISIBILITY) return;

        final boolean added = event.getPacketType() == PacketType.Play.Server.ENTITY_EFFECT;
        this.renderLoop.post(new RenderTask.SuppressChange(
                viewer, viewer, ViewerState.SUPPRESS_INVISIBLE, added));
    }
}
