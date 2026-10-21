
package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class SelfInvisibilityHandler implements ClientBoundPacketHandler {

    private static final int INVISIBILITY_EFFECT_ID = PotionTypes.INVISIBILITY.getId(
            PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());

    private final RenderLoop renderLoop;

    public SelfInvisibilityHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        // Legacy clients handle invisibility through native nametags.
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;

        final Player viewer = event.getPlayer();
        final int entityId = viewer.getEntityId();
        final boolean added = event.getPacketType() == PacketType.Play.Server.ENTITY_EFFECT;
        final var wrapper = event.getLastUsedWrapper();

        // Reuse previously decoded wrappers to preserve modifications made by earlier listeners.
        final boolean matches;

        if (added && wrapper instanceof WrapperPlayServerEntityEffect packet) {
            matches = packet.getEntityId() == entityId
                    && packet.getPotionType() == PotionTypes.INVISIBILITY;

        } else if (!added && wrapper instanceof WrapperPlayServerRemoveEntityEffect packet) {
            matches = packet.getEntityId() == entityId
                    && packet.getPotionType() == PotionTypes.INVISIBILITY;

        } else {
            // Read directly when no compatible wrapper is available.
            matches = isSelfInvisibility(event.getByteBuf(), entityId);
        }

        if (!matches) return;

        this.renderLoop.post(new RenderTask.SuppressChange(
                viewer, viewer, ViewerState.SUPPRESS_INVISIBLE, added));
    }

    private static boolean isSelfInvisibility(Object buffer, int entityId) {
        final int index = ByteBufHelper.readerIndex(buffer);

        try {
            return ByteBufHelper.readVarInt(buffer) == entityId
                    && ByteBufHelper.readVarInt(buffer) == INVISIBILITY_EFFECT_ID;
        } finally {
            // Restore the reader index so subsequent listeners can decode the packet correctly.
            ByteBufHelper.readerIndex(buffer, index);
        }
    }
}
