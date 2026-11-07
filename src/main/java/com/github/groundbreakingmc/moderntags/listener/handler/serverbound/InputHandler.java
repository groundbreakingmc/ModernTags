package com.github.groundbreakingmc.moderntags.listener.handler.serverbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

/** Records own crouching from PLAYER_INPUT. */
public final class InputHandler {

    private static final byte SHIFT_FLAG = 1 << 5;

    private final TagManager tags;

    /**
     * Connects player input to the recipient's own tracking state.
     */
    public InputHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Records own crouching without changing the incoming packet.
     */
    public void handle(@NotNull PacketReceiveEvent event, @NotNull ConfigValues config) {
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;
        final var wrapper = event.getLastUsedWrapper();
        final boolean sneaking = wrapper instanceof WrapperPlayClientPlayerInput packet
                ? packet.isShift() : readSneaking(event.getByteBuf());
        this.tags.updateMetadata((Channel) event.getChannel(), event.getUser().getEntityId(), null, sneaking, config);
    }

    private static boolean readSneaking(Object buffer) {
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            return (ByteBufHelper.readByte(buffer) & SHIFT_FLAG) != 0;
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
    }
}
