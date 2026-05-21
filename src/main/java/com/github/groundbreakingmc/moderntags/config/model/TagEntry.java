package com.github.groundbreakingmc.moderntags.config.model;

import com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer;
import com.github.groundbreakingmc.moderntags.renderer.ModernRenderer;
import com.github.groundbreakingmc.moderntags.renderer.TagRenderer;
import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entry inside a {@link TagGroup}.
 *
 * <p>Each entry bundles a {@link ModernRenderer} (TextDisplay, 1.19.4+) and an optional
 * {@link LegacyRenderer} (Scoreboard Teams, all versions) into a single config object.
 * The viewer's client version decides which renderer is used at runtime — no version
 * checks in YAML needed.
 *
 * <p>If {@code legacyRenderer} is {@code null} the entry is simply invisible to
 * pre-1.19.4 clients; the next lower-priority entry (or no tag) applies instead.
 */
public record TagEntry(
        int priority,
        @Nullable Condition viewerCondition,
        @NotNull ModernRenderer modernRenderer,
        @NotNull LegacyRenderer legacyRenderer
) {

    /**
     * Minimum protocol version that supports TEXT_DISPLAY (1.19.4 = 762).
     */
    private static final ClientVersion MIN_MODERN_VERSION = ClientVersion.V_1_19_4;

    public boolean passCondition(@NotNull Context context) {
        return this.viewerCondition == null || this.viewerCondition.test(context);
    }

    /**
     * Returns the correct renderer for {@code viewer}'s client version.
     */
    @NotNull
    public TagRenderer rendererFor(@NotNull Player viewer) {
        final ClientVersion version = PacketEvents.getAPI()
                .getPlayerManager()
                .getClientVersion(viewer);

        if (version.isNewerThanOrEquals(MIN_MODERN_VERSION)) {
            return this.modernRenderer;
        }
        return this.legacyRenderer;
    }
}
