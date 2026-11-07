package com.github.groundbreakingmc.moderntags.config.model;

import com.github.groundbreakingmc.moderntags.text.TagTextResolver.Template;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Shared, immutable TextDisplay configuration. Contains no entities, viewers,
 * animation progress or protocol-specific metadata indices.
 * Animation groups must use definition reference identity rather than record equality:
 * equal settings from different configuration sections still have separate timelines.
 *
 * @param frameUpdateRate frame interval in plugin ticks, or -1 when disabled or the frame is static
 * @param placeholdersUpdateRate placeholder interval in plugin ticks, or -1 to disable updates
 * @param frames the nonempty frame sequence, copied into an immutable list
 */
public record ModernTag(int frameUpdateRate, int placeholdersUpdateRate, @NotNull List<Frame> frames) {

    /**
     * Creates a definition from settings validated by the configuration loader.
     */
    public ModernTag {
        frames = List.copyOf(frames);
        if (frames.isEmpty()) throw new IllegalArgumentException("Modern tag requires at least one frame");
        frameUpdateRate = frames.size() > 1 ? frameUpdateRate : -1;
    }

    /**
     * Immutable frame constants; brightness and text flags are encoded once at load.
     * Spawn and transition metadata must still handle client defaults, including
     * explicitly restoring defaults when switching from a different frame.
     *
     * @param text the compiled text and its owner/viewer sharing scope
     * @param xOffset horizontal translation in blocks
     * @param yOffset vertical translation in blocks
     * @param zOffset horizontal translation in blocks
     * @param scale the uniform display scale
     * @param verticalBillboard whether rotation is restricted to the vertical axis
     * @param brightness encoded block/sky brightness
     * @param viewRange display view range
     * @param shadowRadius display shadow radius
     * @param shadowStrength display shadow strength
     * @param lineWidth maximum text line width
     * @param backgroundColor the ARGB background color
     * @param textOpacity normal text opacity
     * @param sneakOpacity crouching text opacity
     * @param flags shadow, see-through, default-background and alignment bits
     */
    public record Frame(@NotNull Template text, float xOffset, float yOffset, float zOffset,
                        float scale, boolean verticalBillboard, int brightness, float viewRange,
                        float shadowRadius, float shadowStrength, int lineWidth, int backgroundColor,
                        byte textOpacity, byte sneakOpacity, byte flags) {
    }
}
