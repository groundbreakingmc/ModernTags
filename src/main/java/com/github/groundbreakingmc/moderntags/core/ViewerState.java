package com.github.groundbreakingmc.moderntags.core;

import com.github.groundbreakingmc.moderntags.renderer.TagRenderer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rendering state for a single (target → viewer) pair.
 *
 * <p>Owned exclusively by {@link RenderLoop} — all reads/writes occur during drain,
 * so no synchronisation is needed.
 * <p>
 * <h3>Suppress mask</h3>
 * <pre>
 *   0x01  INVISIBLE  – target is invisible to this viewer
 *   0x02  SNEAK      – target is crouching (reduced opacity)
 *   0x04  PASSENGER  – target has a real passenger; tag hidden
 * </pre>
 * When {@code suppressMask != 0} the tag is hidden. Each cause sets only its own bit.
 */
public final class ViewerState {

    public static final byte SUPPRESS_INVISIBLE = 0x01;
    public static final byte SUPPRESS_SNEAK = 0x02;
    public static final byte SUPPRESS_PASSENGER = 0x04;

    /**
     * The player whose tag is being rendered.
     */
    public final Player target;

    /**
     * The player who sees (or doesn't see) the tag.
     */
    public final Player viewer;

    /**
     * Active renderer for this pair, or {@code null} if the tag has never been rendered / was stopped.
     */
    public @Nullable TagRenderer renderer;

    /**
     * Bitmask of active suppression reasons. Tag is rendered only when this equals {@code 0}.
     */
    public byte suppressMask;

    /**
     * Whether the tag entity is currently visible to the viewer. Tracks actual packet state to avoid redundant sends.
     */
    public boolean rendered;

    /**
     * Last TEAMS ScoreBoardTeamInfo received from the server for this pair.
     * Used by {@link com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer} for name-color resolution.
     */
    public @Nullable TeamSnapshot teamSnapshot;

    public ViewerState(@NotNull Player target, @NotNull Player viewer) {
        this.target = target;
        this.viewer = viewer;
    }

    /**
     * Returns {@code true} if any suppression reason is active.
     */
    public boolean isSuppressed() {
        return this.suppressMask != 0;
    }

    /**
     * Returns {@code true} if sneak is the only active suppression reason.
     */
    public boolean isSneakOnly() {
        return this.suppressMask == SUPPRESS_SNEAK;
    }

    public void addSuppress(byte reason) {
        this.suppressMask |= reason;
    }

    public void removeSuppress(byte reason) {
        this.suppressMask &= (byte) ~reason;
    }

    public boolean hasSuppress(byte reason) {
        return (this.suppressMask & reason) != 0;
    }

    /**
     * Snapshot of the last server-sent TEAMS ScoreBoardTeamInfo for this pair.
     * Stored as plain objects to avoid holding a reference to a PacketEvents wrapper.
     */
    public record TeamSnapshot(
            String teamName,
            Object prefix,
            Object suffix,
            Object color,
            Object nameTagVisibility,
            Object collisionRule,
            Object optionData
    ) {}
}
