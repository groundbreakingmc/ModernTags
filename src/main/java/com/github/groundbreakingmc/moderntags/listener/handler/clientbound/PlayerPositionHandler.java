package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

public final class PlayerPositionHandler {

    private final TagManager tags;

    /**
     * Connects native positioning to the recipient's own tag state.
     */
    public PlayerPositionHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Restores own tracking without decoding coordinates or changing the packet.
     */
    public void handle(@NotNull PacketSendEvent event, @NotNull ConfigValues config) {
        final var user = event.getUser();
        this.tags.positionSelf((Channel) event.getChannel(), user.getEntityId(), user.getUUID(), config);
    }
}
