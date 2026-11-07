package com.github.groundbreakingmc.moderntags.render;

import com.github.groundbreakingmc.moderntags.render.ModernRenderer.CompiledFrame;
import com.github.retrooper.packetevents.util.Vector3d;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Applied state of one display for one viewer. All operations belong to that
 * viewer's EventLoop and must use the same channel. The tracked player that owns the
 * instance controls its lifetime: after {@link #destroy} it drops the reference and
 * never calls this instance again.
 */
public final class ModernDisplay {

    private final int entityId;
    private CompiledFrame frame;
    private Component text;
    private byte opacity;
    private boolean attached;

    /**
     * Spawns a display with full metadata using the owner's shared tag identity.
     * The caller retains this instance while the tag is shown; attachment is separate.
     */
    public ModernDisplay(@NotNull Channel viewer, int entityId, @NotNull UUID uniqueId,
                         @NotNull Vector3d position, @NotNull CompiledFrame frame,
                         @NotNull Component text, byte opacity) {
        ModernRenderer.spawn(viewer, entityId, uniqueId, position, frame, text, opacity);
        this.entityId = entityId;
        this.frame = frame;
        this.text = text;
        this.opacity = opacity;
    }

    /**
     * Removes the display. It must be called at most once, by the owner of this instance.
     */
    public void destroy(@NotNull Channel viewer) {
        ModernRenderer.destroy(viewer, this.entityId);
        this.attached = false;
    }

    /** Applies new metadata, remembering the predecessor even when no packet is needed. */
    public void update(@NotNull Channel viewer, @NotNull CompiledFrame frame,
                       @NotNull Component text, byte opacity) {
        ModernRenderer.updateMetadata(viewer, this.entityId, frame, text, opacity,
                this.frame, this.text, this.opacity);
        this.frame = frame;
        this.text = text;
        this.opacity = opacity;
    }

    /**
     * Attaches a surviving display using the owner's current passengers, excluding this tag.
     * The caller checks native tracking and supplies current passengers at execution time.
     */
    public void attach(@NotNull Channel viewer, int ownerEntityId, int @NotNull [] passengers) {
        ModernRenderer.attach(viewer, ownerEntityId, this.entityId, passengers);
        this.attached = true;
    }

    /** Updates only opacity using the last applied frame and text. */
    public void updateSneaking(@NotNull Channel viewer, boolean sneaking) {
        this.update(viewer, this.frame, this.text, this.frame.opacity(sneaking));
    }

    /** Returns the virtual entity ID for editing outgoing passenger packets. */
    public int entityId() {
        return this.entityId;
    }

    /** Whether initial attachment has completed; native packets must not attach it early. */
    public boolean isAttached() {
        return this.attached;
    }
}
