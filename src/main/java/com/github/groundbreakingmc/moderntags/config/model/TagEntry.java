package com.github.groundbreakingmc.moderntags.config.model;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import org.bukkit.entity.Player;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entry inside a {@link TagGroup}.
 *
 * <p>Each entry references a {@link ModernTag} (TextDisplay, 1.19.4+) and an optional
 * {@link LegacyTag} (Scoreboard Teams, all versions). These are shared configuration
 * definitions, not renderer instances. The viewer's client version decides the mode — no version
 * checks in YAML needed.
 *
 * <p>If {@code legacy} is {@code null} the entry is simply invisible to
 * pre-1.19.4 clients; the next lower-priority entry (or no tag) applies instead.
 *
 * @param priority selection priority within the group
 * @param viewerCondition the compiled viewer-specific condition, or null
 * @param modern shared modern definition
 * @param legacy shared legacy definition, or null when unavailable
 */
public record TagEntry(
        int priority,
        @Nullable Condition viewerCondition,
        @NotNull ModernTag modern,
        @Nullable LegacyTag legacy
) {

    /**
     * Minimum protocol version that supports TEXT_DISPLAY (1.19.4 = 762).
     */
    private static final ClientVersion MIN_MODERN_VERSION = ClientVersion.V_1_19_4;

    /** @return whether the viewer-specific condition passes for this evaluation */
    public boolean passCondition(@NotNull Player context) {
        return this.viewerCondition == null || this.viewerCondition.test(context);
    }

    /**
     * Reports whether this entry supplies a definition for the supplied client version.
     * Does not perform a PacketEvents player lookup or evaluate conditions.
     *
     * @param version the already known viewer protocol version
     * @return whether the entry can be selected for this viewer
     */
    public boolean supports(@NotNull ClientVersion version) {
        return version.isNewerThanOrEquals(MIN_MODERN_VERSION) || this.legacy != null;
    }
}
