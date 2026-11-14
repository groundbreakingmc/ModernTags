package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class DestroyEntitiesHandler {

    private final TagManager tags;

    /**
     * Connects entity removal packets to the recipient's tracking state.
     */
    public DestroyEntitiesHandler(@NotNull TagManager tags) {
        this.tags = tags;
    }

    /**
     * Ends tracking of removed players and adds their tag displays to the same packet, so a
     * tag leaves together with its owner instead of in a packet of its own.
     */
    public void handle(@NotNull PacketSendEvent event) {
        final var viewer = (Channel) event.getChannel();
        if (event.getLastUsedWrapper() instanceof WrapperPlayServerDestroyEntities packet) {
            this.handleDecoded(event, viewer, packet);
            return;
        }
        this.handleNative(viewer, (ByteBuf) event.getByteBuf());
    }

    private void handleDecoded(PacketSendEvent event, Channel viewer, WrapperPlayServerDestroyEntities packet) {
        final int[] entityIds = packet.getEntityIds();
        int[] displays = null;
        int found = 0;
        for (int i = 0; i < entityIds.length; i++) {
            final var display = this.tags.untrack(viewer, entityIds[i]);
            if (display == null) continue;
            if (displays == null) displays = new int[entityIds.length - i];
            displays[found++] = display.entityId();
        }
        if (found == 0) return;
        final int[] all = Arrays.copyOf(entityIds, entityIds.length + found);
        System.arraycopy(displays, 0, all, entityIds.length, found);
        packet.setEntityIds(all);
        event.markForReEncode(true);
    }

    private void handleNative(Channel viewer, ByteBuf buffer) {
        final int start = buffer.readerIndex();
        final int count;
        int[] displays = null;
        int found = 0;
        try {
            count = ByteBufHelper.readVarInt(buffer);
            for (int i = 0; i < count; i++) {
                final var display = this.tags.untrack(viewer, ByteBufHelper.readVarInt(buffer));
                if (display == null) continue;
                if (displays == null) displays = new int[count - i];
                displays[found++] = display.entityId();
            }
        } finally {
            buffer.readerIndex(start);
        }
        if (found != 0) append(buffer, start, count, displays, found);
    }

    /**
     * Adds entity IDs to a native removal in place. Without a decoded wrapper PacketEvents
     * passes these bytes on as they are, so no wrapper or second encoding is needed.
     */
    private static void append(ByteBuf buffer, int start, int count, int[] displays, int found) {
        final int total = count + found;
        final int countSize = varIntSize(count);
        final int end = buffer.writerIndex();
        if (varIntSize(total) == countSize) {
            buffer.writerIndex(start);
            ByteBufHelper.writeVarInt(buffer, total);
            buffer.writerIndex(end);
        } else {
            // Rare: the longer count moves every ID after it.
            final byte[] entityIds = ByteBufUtil.getBytes(buffer, start + countSize, end - start - countSize);
            buffer.writerIndex(start);
            ByteBufHelper.writeVarInt(buffer, total);
            buffer.writeBytes(entityIds);
        }
        for (int i = 0; i < found; i++) {
            ByteBufHelper.writeVarInt(buffer, displays[i]);
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
