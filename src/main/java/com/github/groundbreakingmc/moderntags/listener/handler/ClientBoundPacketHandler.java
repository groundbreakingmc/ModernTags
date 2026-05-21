package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.jetbrains.annotations.NotNull;

public interface ClientBoundPacketHandler {

    void handle(@NotNull PacketSendEvent event);
}
