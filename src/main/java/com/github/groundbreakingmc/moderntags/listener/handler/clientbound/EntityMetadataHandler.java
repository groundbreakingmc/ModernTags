package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
import com.github.groundbreakingmc.moderntags.listener.handler.ClientBoundPacketHandler;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks invisibility and sneak state from ENTITY_METADATA packets.
 * Only relevant on 1.19.4+ — older clients handle metadata themselves without sending this data.
 */
public final class EntityMetadataHandler implements ClientBoundPacketHandler {

    private static final int FLAGS_INDEX = 0;
    private static final byte INVISIBLE_FLAG = 0x20;

    private final RenderLoop renderLoop;

    public EntityMetadataHandler(@NotNull RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @Override
    public void handle(@NotNull PacketSendEvent event) {
        // Pre-1.19.4 clients process metadata themselves; the packet lacks the needed fields.
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;

        final var packet = new WrapperPlayServerEntityMetadata(event);

        final Player viewer = event.getPlayer();
        if (viewer.getEntityId() == packet.getEntityId()) return;

        final Player target = PlayerLookup.playerById(packet.getEntityId());
        if (target == null) return;

        boolean invisFound = false, sneakFound = false;

        for (final EntityData<?> data : packet.getEntityMetadata()) {
            if (!invisFound && data.getIndex() == FLAGS_INDEX
                    && data.getType() == EntityDataTypes.BYTE) {
                final boolean invis = ((byte) data.getValue() & INVISIBLE_FLAG) != 0;
                this.renderLoop.post(new RenderTask.SuppressChange(
                        target, viewer, ViewerState.SUPPRESS_INVISIBLE, invis));
                invisFound = true;
                if (sneakFound) break;
            } else if (!sneakFound && data.getType() == EntityDataTypes.ENTITY_POSE) {
                final boolean sneak = data.getValue() == EntityPose.CROUCHING;
                this.renderLoop.post(new RenderTask.SuppressChange(
                        target, viewer, ViewerState.SUPPRESS_SNEAK, sneak));
                sneakFound = true;
                if (invisFound) break;
            }
        }
    }
}
