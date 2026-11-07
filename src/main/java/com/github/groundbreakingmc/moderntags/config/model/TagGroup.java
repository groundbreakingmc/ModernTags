package com.github.groundbreakingmc.moderntags.config.model;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Shared group definition, ordered by priority by the configuration loader.
 *
 * @param priority selection priority among groups
 * @param ownerCondition the compiled owner-only condition, or null
 * @param entries immutable entries in descending selection priority
 */
public record TagGroup(
        int priority,
        @Nullable Condition ownerCondition,
        @NotNull List<TagEntry> entries
) {

    /** Copies the entry list so one evaluation can safely share the group with other threads. */
    public TagGroup {
        entries = List.copyOf(entries);
    }

    /** @return whether the owner-only condition passes for this evaluation */
    public boolean passCondition(@NotNull Player context) {
        return this.ownerCondition == null || this.ownerCondition.test(context);
    }
}
