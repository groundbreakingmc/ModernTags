package com.github.groundbreakingmc.moderntags.listener.handler;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.jetbrains.annotations.NotNull;

public interface ServerBoundPacketHandler {

    void handle(@NotNull PacketReceiveEvent event);
}
