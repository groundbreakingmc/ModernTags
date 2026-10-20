package com.github.groundbreakingmc.moderntags.core;

import com.github.groundbreakingmc.moderntags.renderer.TagRenderer;
import com.github.retrooper.packetevents.PacketEvents;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rendering state for a single (target → viewer) pair.
 *
 * <p>Owned exclusively by {@link RenderLoop} — all reads/writes occur during drain,
 * so no synchronisation is needed.
 *
 * <h2>Suppress mask</h2>
 * <pre>
 *   0x01  INVISIBLE  – target is invisible to this viewer
 *   0x02  SNEAK      – target is crouching (reduced opacity)
 *   0x04  PASSENGER  – target has a real passenger; tag hidden
 * </pre>
 * Each cause sets only its own bit; RenderLoop resolves visibility and opacity for the viewer.
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
     * Connection already established when this pair is created. A new login creates
     * new pair state; delayed attachment still checks whether the connection is open.
     */
    public final Object channel;

    /**
     * Active renderer for this pair, or {@code null} if the tag has never been rendered / was stopped.
     */
    public @Nullable TagRenderer renderer;

    /**
     * Raw suppression reasons. RenderLoop determines their effect on visibility and opacity.
     */
    public byte suppressMask;

    /**
     * Whether the tag entity is currently visible to the viewer.
     * Tracks actual packet state to avoid redundant sends.
     */
    public boolean rendered;

    /**
     * Whether rendering is requested for this pair, even if no tag currently matches.
     * Cleared by an explicit stop; temporary suppression does not change it.
     */
    public boolean active;

    /**
     * Whether a native spawn or initialization established this viewer's tracking.
     */
    public boolean tracked;

    /**
     * Current delayed native spawn; the reference is owned by the render loop.
     */
    public @Nullable RenderTask.RenderWhenCurrent pendingSpawn;

    /**
     * Current delayed attachment; the reference is owned by the render loop.
     */
    public @Nullable RenderTask.AttachTag pendingAttach;

    /**
     * Resolved name color from the last server-sent TEAMS packet for this (target, viewer) pair.
     * Used by {@link com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer}
     * to honour {@code preserve-player-name-color} without re-reading live packet data.
     *
     * <p>Set when a CREATE / UPDATE / ADD_ENTITIES TEAMS packet is processed for a
     * LegacyRenderer-managed target.  Cleared when the target's team is REMOVE'd or
     * the target is REMOVE_ENTITIES'd from it.
     *
     * <p>Only the color field of {@code ScoreBoardTeamInfo} is stored; the full packet
     * wrapper is intentionally not retained to avoid keeping a reference to PacketEvents
     * internals and to eliminate the allocation of a multi-field snapshot record.
     */
    public @Nullable NamedTextColor teamColor;

    public ViewerState(@NotNull Player target, @NotNull Player viewer) {
        this.target = target;
        this.viewer = viewer;
        this.channel = PacketEvents.getAPI().getProtocolManager().getChannel(viewer.getUniqueId());
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
}
