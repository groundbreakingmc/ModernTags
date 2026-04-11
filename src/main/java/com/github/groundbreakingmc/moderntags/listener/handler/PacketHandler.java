package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Interface for handling specific packet types.
 */
@FunctionalInterface
public interface PacketHandler {

    /**
     * Handles a packet send event.
     *
     * @param event The packet send event
     */
    void handle(@NotNull PacketSendEvent event);
}
