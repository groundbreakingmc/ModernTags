package com.github.groundbreakingmc.moderntags.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.util.Arrays;

/**
 * Raw packet bytes for the packets a renderer writes itself.
 *
 * <p>A wrapper sent through PacketEvents is encoded again for every recipient: the wrapper,
 * its values and, for text, a whole NBT tree. A renderer can instead write its packet
 * straight into a pooled buffer and copy in the parts PacketEvents encoded once.
 * PacketEvents stays the reference for every layout: {@link #expect} compares a natively
 * written packet with the wrapper's own encoding at startup.
 */
final class PacketBytes {

    private PacketBytes() {
    }

    /** Returns the packet as PacketEvents sends the wrapper: native packet ID, then content. */
    static byte[] of(PacketWrapper<?> wrapper) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            wrapper.buffer = buffer;
            wrapper.writeVarInt(wrapper.getNativePacketId());
            wrapper.write();
            return ByteBufUtil.getBytes(buffer);
        } finally {
            wrapper.buffer = null;
            buffer.release();
        }
    }

    /**
     * Requires a natively written packet to equal PacketEvents' encoding of the same values,
     * then releases it.
     *
     * @throws IllegalStateException when this server's protocol lays the packet out differently
     */
    static void expect(PacketWrapper<?> expected, ByteBuf written) {
        final byte[] actual = ByteBufUtil.getBytes(written);
        written.release();
        final byte[] reference = of(expected);
        if (Arrays.equals(reference, actual)) return;
        throw new IllegalStateException("This server version is not supported: PacketEvents encodes "
                + expected.getClass().getSimpleName() + " as " + ByteBufUtil.hexDump(reference)
                + ", ModernTags writes " + ByteBufUtil.hexDump(actual));
    }

    /**
     * Writes a complete packet past PacketEvents' listeners, in the recipient's EventLoop,
     * without flushing it. A flush per packet is a system call per packet, which a pass over
     * many tags turns into a burst that delays every connection on the EventLoop.
     *
     * <p>Whoever starts the work flushes once at its end: an outgoing native packet is flushed
     * by its own sender, so packets written while handling it leave together with it; tasks of
     * our own and handlers of incoming packets call {@link Channel#flush()} when they finish.
     */
    static void write(Channel viewer, ByteBuf packet) {
        PacketEvents.getAPI().getProtocolManager().writePacketSilently(viewer, packet);
    }
}
