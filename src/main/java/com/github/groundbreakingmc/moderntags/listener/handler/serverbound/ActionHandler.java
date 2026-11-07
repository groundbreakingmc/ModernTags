package com.github.groundbreakingmc.moderntags.listener.handler.serverbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

/** Records own crouching using PacketEvents' versioned sneak-action mapping. */
public final class ActionHandler {

    private final TagManager tags;

    /**
     * Connects player input to the recipient's own tracking state.
     */
    public ActionHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Records own crouching without changing the incoming packet.
     */
    public void handle(@NotNull PacketReceiveEvent event, @NotNull ConfigValues config) {
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;
        final var wrapper = event.getLastUsedWrapper();
        final WrapperPlayClientEntityAction.Action action = wrapper instanceof WrapperPlayClientEntityAction packet
                ? packet.getAction() : readAction(event);
        if (action != WrapperPlayClientEntityAction.Action.START_SNEAKING
                && action != WrapperPlayClientEntityAction.Action.STOP_SNEAKING) return;
        final boolean sneaking = action == WrapperPlayClientEntityAction.Action.START_SNEAKING;
        this.tags.updateMetadata((Channel) event.getChannel(), event.getUser().getEntityId(), null, sneaking, config);
    }

    private static WrapperPlayClientEntityAction.Action readAction(PacketReceiveEvent event) {
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            // Sneaking belongs to the sender, regardless of the supplied entity ID.
            ByteBufHelper.readVarInt(buffer);
            // Sneak action IDs changed in 1.21.6; retain PacketEvents' versioned mapping.
            return WrapperPlayClientEntityAction.Action.getById(
                    event.getServerVersion(), ByteBufHelper.readVarInt(buffer));
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
    }
}
