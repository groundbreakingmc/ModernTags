package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Handles advertised spectator-mode changes independently of profile membership. */
public final class PlayerInfoUpdateHandler {

    private static final int GAME_MODE_ACTION = 1 << WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE.ordinal();
    // PacketEvents reads ceil(action count / 8) bytes before the entry count.
    private static final boolean SINGLE_ACTION_BYTE = WrapperPlayServerPlayerInfoUpdate.Action.VALUES.length <= 8;

    private final TagManager tags;

    /**
     * Connects advertised game modes to the tag manager.
     */
    public PlayerInfoUpdateHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Reads game-mode changes without changing the outgoing packet.
     */
    public void handle(@NotNull PacketSendEvent event, @NotNull ConfigValues config) {
        final var previousWrapper = event.getLastUsedWrapper();
        if (previousWrapper instanceof WrapperPlayServerPlayerInfoUpdate packet) {
            this.handleDecoded(event, packet, config);
            return;
        }
        if (this.handleNative(event, config)) return;

        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        final WrapperPlayServerPlayerInfoUpdate packet;
        try {
            packet = new WrapperPlayServerPlayerInfoUpdate(event);
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
            event.setLastUsedWrapper(previousWrapper);
        }
        this.handleDecoded(event, packet, config);
    }

    private void handleDecoded(PacketSendEvent event, WrapperPlayServerPlayerInfoUpdate packet, ConfigValues config) {
        if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return;
        final var viewer = (Channel) event.getChannel();
        for (final var entry : packet.getEntries()) {
            this.tags.updateSpectator(viewer, entry.getProfileId(), entry.getGameMode() == GameMode.SPECTATOR, config);
        }
    }

    /** Returns false when combined actions require PacketEvents' full decoder. */
    private boolean handleNative(PacketSendEvent event, ConfigValues config) {
        if (!SINGLE_ACTION_BYTE) return false;
        final var buffer = event.getByteBuf();
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            final int actions = ByteBufHelper.readByte(buffer) & 0xFF;
            // Profile membership, display-name and latency updates do not affect tags.
            if ((actions & GAME_MODE_ACTION) == 0) return true;
            if (actions != GAME_MODE_ACTION) return false;

            final int count = ByteBufHelper.readVarInt(buffer);
            if (count < 0) return false;
            final var viewer = (Channel) event.getChannel();
            for (int i = 0; i < count; i++) {
                final var uniqueId = new UUID(ByteBufHelper.readLong(buffer), ByteBufHelper.readLong(buffer));
                final boolean spectator = ByteBufHelper.readVarInt(buffer) == GameMode.SPECTATOR.getId();
                this.tags.updateSpectator(viewer, uniqueId, spectator, config);
            }
            return true;
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
    }
}
