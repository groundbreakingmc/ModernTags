package com.github.groundbreakingmc.moderntags.config.model;

import com.github.groundbreakingmc.moderntags.text.TagTextResolver.Template;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Shared, immutable native-nametag configuration. Team membership and source colors
 * belong to viewer connections, not to this configuration or its frames.
 * Animation groups must use definition reference identity rather than record equality:
 * equal settings from different configuration sections still have separate timelines.
 *
 * @param frameUpdateRate frame interval in plugin ticks, or -1 when disabled or the frame is static
 * @param placeholdersUpdateRate placeholder interval in plugin ticks, or -1 to disable updates
 * @param preservePlayerNameColor whether to prefer the source team's raw color
 * @param ignoredColors source colors that should use the frame fallback instead
 * @param frames the nonempty frame sequence, copied into an immutable list
 */
public record LegacyTag(int frameUpdateRate, int placeholdersUpdateRate, boolean preservePlayerNameColor,
                        @NotNull Set<NamedTextColor> ignoredColors, @NotNull List<Frame> frames) {

    /**
     * Creates a definition from settings validated by the configuration loader.
     */
    public LegacyTag {
        frames = List.copyOf(frames);
        if (frames.isEmpty()) throw new IllegalArgumentException("Legacy tag requires at least one frame");
        frameUpdateRate = frames.size() > 1 ? frameUpdateRate : -1;
        ignoredColors = Set.copyOf(ignoredColors);
    }

    /**
     * One immutable legacy frame. Both templates can share an owner-wide result
     * when neither depends on the viewer; the source color is resolved separately.
     *
     * @param prefix the compiled prefix
     * @param nameColor the fallback player-name color
     * @param suffix the compiled suffix
     */
    public record Frame(@NotNull Template prefix, @NotNull NamedTextColor nameColor, @NotNull Template suffix) {
    }
}
