package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

/** Records the own Modern tag's invisibility from native potion packets. */
public final class SelfInvisibilityHandler {

    private final TagManager tags;
    private final int invisibilityEffectId;

    /**
     * Connects own effects to tag state and resolves the server's invisibility effect ID.
     */
    public SelfInvisibilityHandler(@NotNull TagManager tags) {
        this.tags = tags;
        this.invisibilityEffectId = PotionTypes.INVISIBILITY.getId(
                PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
    }

    /**
     * Records own invisibility changes without decoding unrelated effect fields.
     */
    public void handle(@NotNull PacketSendEvent event, @NotNull ConfigValues config) {
        if (event.getUser().getClientVersion().isOlderThan(ClientVersion.V_1_19_4)) return;
        final int selfEntityId = event.getUser().getEntityId();
        final boolean added = event.getPacketType() == PacketType.Play.Server.ENTITY_EFFECT;
        final var wrapper = event.getLastUsedWrapper();
        final boolean matches;
        if (added && wrapper instanceof WrapperPlayServerEntityEffect packet) {
            matches = packet.getEntityId() == selfEntityId && packet.getPotionType() == PotionTypes.INVISIBILITY;
        } else if (!added && wrapper instanceof WrapperPlayServerRemoveEntityEffect packet) {
            matches = packet.getEntityId() == selfEntityId && packet.getPotionType() == PotionTypes.INVISIBILITY;
        } else {
            matches = this.isSelfInvisibility(event.getByteBuf(), selfEntityId);
        }
        if (!matches) return;
        this.tags.updateMetadata((Channel) event.getChannel(), selfEntityId, added, null, config);
    }

    private boolean isSelfInvisibility(Object buffer, int entityId) {
        final int readerIndex = ByteBufHelper.readerIndex(buffer);
        try {
            return ByteBufHelper.readVarInt(buffer) == entityId
                    && ByteBufHelper.readVarInt(buffer) == this.invisibilityEffectId;
        } finally {
            ByteBufHelper.readerIndex(buffer, readerIndex);
        }
    }
}
