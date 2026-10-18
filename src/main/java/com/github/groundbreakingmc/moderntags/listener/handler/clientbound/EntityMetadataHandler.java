package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Synchronizes modern tag visibility and sneak opacity with owner metadata.
 * Legacy clients handle native nametags without this synchronization.
 */
public final class EntityMetadataHandler implements ClientBoundPacketHandler {

    private static final int FLAGS_INDEX = 0;
    private static final byte INVISIBLE_FLAG = 0x20;

    private final RenderLoop renderLoop;

    /**
     * Creates a handler that forwards visibility changes to the render loop.
     *
     * @param renderLoop the owner of tracked tag state
     */
    public EntityMetadataHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    /**
     * Tracks invisibility and crouching for TextDisplay tags on clients 1.19.4 and newer.
     * Older clients use native nametags, so Minecraft handles these states itself.
     *
     * @param event the outgoing entity-metadata packet
     */
    @Override
    public void handle(@NotNull PacketSendEvent event) {
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;

        final var packet = new WrapperPlayServerEntityMetadata(event);

        final Player viewer = event.getPlayer();
        if (viewer.getEntityId() == packet.getEntityId()) return;

        byte changedMask = 0;
        byte values = 0;

        for (final var data : packet.getEntityMetadata()) {
            if ((changedMask & ViewerState.SUPPRESS_INVISIBLE) == 0 && data.getIndex() == FLAGS_INDEX
                    && data.getType() == EntityDataTypes.BYTE) {
                changedMask |= ViewerState.SUPPRESS_INVISIBLE;
                if (((byte) data.getValue() & INVISIBLE_FLAG) != 0) values |= ViewerState.SUPPRESS_INVISIBLE;
            } else if ((changedMask & ViewerState.SUPPRESS_SNEAK) == 0
                    && data.getType() == EntityDataTypes.ENTITY_POSE) {
                changedMask |= ViewerState.SUPPRESS_SNEAK;
                if (data.getValue() == EntityPose.CROUCHING) values |= ViewerState.SUPPRESS_SNEAK;
            }
            if (changedMask == (ViewerState.SUPPRESS_INVISIBLE | ViewerState.SUPPRESS_SNEAK)) break;
        }

        if (changedMask == 0) return;
        final var target = PlayerLookup.playerById(packet.getEntityId());
        if (target == null) return;

        // Flags and pose belong to one packet; apply them without an intermediate visibility state.
        this.renderLoop.post(new RenderTask.SuppressChange(target, viewer, changedMask, values));
    }
}
