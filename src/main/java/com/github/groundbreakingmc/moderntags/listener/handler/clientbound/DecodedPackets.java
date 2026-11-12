package com.github.groundbreakingmc.moderntags.listener.handler.clientbound;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * What recently seen packets decoded to, found again by their exact bytes.
 *
 * <p>The server encodes a broadcast separately for every recipient, so the listener of each
 * viewer sees the same bytes. Decoding them once and sharing the result replaces a full
 * PacketEvents decode per viewer with a comparison of bytes, and PacketEvents stays the only
 * decoder. Stored values are read by every EventLoop and must be immutable.
 *
 * @param <T> the decoded value
 */
final class DecodedPackets<T> {

    /** Number of remembered packets, a power of two. A packet keeps its slot until another one hashes to it. */
    private static final int SLOTS = 32;
    /** Longer packets are decoded every time instead of being copied here. */
    private static final int MAX_LENGTH = 4096;

    private final AtomicReferenceArray<Entry<T>> entries = new AtomicReferenceArray<>(SLOTS);

    /**
     * Returns the value remembered for exactly these bytes, or null. Neither index of the
     * buffer is moved and nothing is allocated.
     */
    @Nullable T find(ByteBuf buffer, int start, int length) {
        if (length > MAX_LENGTH) return null;
        final var entry = this.entries.get(slot(buffer, start, length));
        return entry != null && entry.bytes.readableBytes() == length
                && ByteBufUtil.equals(buffer, start, entry.bytes, 0, length) ? entry.value : null;
    }

    /**
     * Remembers the value decoded from these bytes. The bytes are copied, so the buffer may be
     * released or rewritten afterwards; its reader index may already have moved.
     */
    void remember(ByteBuf buffer, int start, int length, T value) {
        if (length > MAX_LENGTH) return;
        final ByteBuf bytes = Unpooled.buffer(length, length);
        bytes.writeBytes(buffer, start, length);
        this.entries.set(slot(buffer, start, length), new Entry<>(bytes, value));
    }

    private static int slot(ByteBuf buffer, int start, int length) {
        int hash = length;
        int index = start;
        final int end = start + length;
        for (; end - index >= Long.BYTES; index += Long.BYTES) {
            hash = 31 * hash + Long.hashCode(buffer.getLong(index));
        }
        for (; index < end; index++) {
            hash = 31 * hash + buffer.getByte(index);
        }
        return (hash ^ hash >>> 16) & SLOTS - 1;
    }

    /** The copied bytes are only read through absolute getters, which is safe from any thread. */
    private record Entry<T>(ByteBuf bytes, T value) {
    }
}
