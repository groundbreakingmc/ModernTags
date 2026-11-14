package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Handles advertised spectator-mode changes independently of profile membership. */
public final class PlayerInfoUpdateHandler {

    private static final int GAME_MODE_ACTION = 1 << WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE.ordinal();
    // PacketEvents reads ceil(action count / 8) bytes before the entry count.
    private static final boolean SINGLE_ACTION_BYTE = WrapperPlayServerPlayerInfoUpdate.Action.VALUES.length <= 8;
    /** A game-mode entry is a UUID and at least one more byte. */
    private static final int MIN_ENTRY_BYTES = 2 * Long.BYTES + 1;

    private final TagManager tags;
    /** A game mode is advertised to every viewer with the same bytes; it is decoded once. */
    private final DecodedPackets<GameModes> decoded = new DecodedPackets<>();

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
        final var viewer = (Channel) event.getChannel();
        if (event.getLastUsedWrapper() instanceof WrapperPlayServerPlayerInfoUpdate packet) {
            // Another listener decoded this packet and may have edited it; its bytes are stale.
            if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return;
            final var viewerId = event.getUser().getUUID();
            for (final var entry : packet.getEntries()) {
                this.tags.updateSpectator(viewer, viewerId, entry.getProfileId(),
                        entry.getGameMode() == GameMode.SPECTATOR, config);
            }
            return;
        }
        final var buffer = (ByteBuf) event.getByteBuf();
        final int start = buffer.readerIndex();
        final int length = buffer.readableBytes();
        // Profile membership, display-name and latency updates do not affect tags.
        if (SINGLE_ACTION_BYTE && (length == 0 || (buffer.getByte(start) & GAME_MODE_ACTION) == 0)) return;
        var modes = this.decoded.find(buffer, start, length);
        if (modes == null) {
            modes = this.decode(event, buffer, start);
            this.decoded.remember(buffer, start, length, modes);
        }
        final var viewerId = event.getUser().getUUID();
        for (int i = 0; i < modes.profiles.length; i++) {
            this.tags.updateSpectator(viewer, viewerId, modes.profiles[i], modes.spectator[i], config);
        }
    }

    /**
     * Decodes a packet seen for the first time and leaves the event as it was. A lone game-mode
     * action is read directly; any other combination goes through PacketEvents' full decoder.
     */
    private GameModes decode(PacketSendEvent event, ByteBuf buffer, int start) {
        final var previousWrapper = event.getLastUsedWrapper();
        try {
            if (SINGLE_ACTION_BYTE && (buffer.getByte(start) & 0xFF) == GAME_MODE_ACTION) {
                buffer.readerIndex(start + 1);
                final int count = ByteBufHelper.readVarInt(buffer);
                if (count >= 0 && count <= buffer.readableBytes() / MIN_ENTRY_BYTES) {
                    final var profiles = new UUID[count];
                    final boolean[] spectator = new boolean[count];
                    for (int i = 0; i < count; i++) {
                        profiles[i] = new UUID(buffer.readLong(), buffer.readLong());
                        spectator[i] = ByteBufHelper.readVarInt(buffer) == GameMode.SPECTATOR.getId();
                    }
                    return new GameModes(profiles, spectator);
                }
                buffer.readerIndex(start);
            }
            final var packet = new WrapperPlayServerPlayerInfoUpdate(event);
            if (!packet.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE)) return GameModes.NONE;
            final var entries = packet.getEntries();
            final var profiles = new UUID[entries.size()];
            final boolean[] spectator = new boolean[profiles.length];
            for (int i = 0; i < profiles.length; i++) {
                final var entry = entries.get(i);
                profiles[i] = entry.getProfileId();
                spectator[i] = entry.getGameMode() == GameMode.SPECTATOR;
            }
            return new GameModes(profiles, spectator);
        } finally {
            buffer.readerIndex(start);
            // Observation must not introduce a packet rewrite or replace earlier edits.
            event.setLastUsedWrapper(previousWrapper);
        }
    }

    /**
     * The game modes one packet advertises, as parallel arrays that every viewer shares.
     * Neither array is modified after decoding.
     */
    private record GameModes(UUID[] profiles, boolean[] spectator) {

        private static final GameModes NONE = new GameModes(new UUID[0], new boolean[0]);
    }
}
