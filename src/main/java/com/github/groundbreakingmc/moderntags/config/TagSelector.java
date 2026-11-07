package com.github.groundbreakingmc.moderntags.config;

import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
import org.bukkit.entity.Player;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Immutable selection rules for one configuration generation.
 */
public final class TagSelector {

    private final List<TagGroup> groups;

    /**
     * Creates selection rules using groups and entries already sorted by priority.
     *
     * @param groups configuration groups in descending priority, with stable ordering for ties
     */
    public TagSelector(@NotNull List<TagGroup> groups) {
        this.groups = List.copyOf(groups);
    }

    /** @return immutable configuration groups in selection order */
    public @NotNull List<TagGroup> groups() {
        return this.groups;
    }

    /**
     * Selects one entry for an initial tracking event without allocating evaluation maps.
     * Periodic batch evaluation can instead reuse forOwner/forViewer results.
     */
    public @Nullable TagEntry select(@NotNull Player owner, @NotNull Player viewer,
                                     @NotNull ClientVersion version) {
        for (final var group : this.groups) {
            if (!group.passCondition(owner)) continue;
            for (final var entry : group.entries()) {
                if (entry.supports(version) && entry.passCondition(viewer)) return entry;
            }
        }
        return null;
    }

    /**
     * Checks each group's owner condition once for an evaluation pass. The returned
     * immutable list can be shared with viewer event loops; callers start a new pass
     * when conditions need rechecking. No owner or other mutable context is retained.
     * If every condition passes, the existing configuration list is reused.
     *
     * @param owner the owner-only condition context
     * @return eligible groups, still in descending priority
     */
    public @NotNull List<TagGroup> forOwner(@NotNull Player owner) {
        List<TagGroup> eligible = null;
        for (int i = 0; i < this.groups.size(); i++) {
            final var group = this.groups.get(i);
            if (group.passCondition(owner)) {
                if (eligible != null) eligible.add(group);
            } else if (eligible == null) {
                eligible = new ArrayList<>(this.groups.size());
                for (int previous = 0; previous < i; previous++) eligible.add(this.groups.get(previous));
            }
        }
        return eligible == null ? this.groups : List.copyOf(eligible);
    }

    /** Checks each group's viewer conditions once for all targets observed by this viewer. */
    public @NotNull Map<TagGroup, TagEntry> forViewer(@NotNull Player viewer, @NotNull ClientVersion version) {
        final var result = new IdentityHashMap<TagGroup, TagEntry>();
        for (final var group : this.groups) {
            for (final var entry : group.entries()) {
                if (entry.supports(version) && entry.passCondition(viewer)) {
                    result.put(group, entry);
                    break;
                }
            }
        }
        return result;
    }

}
