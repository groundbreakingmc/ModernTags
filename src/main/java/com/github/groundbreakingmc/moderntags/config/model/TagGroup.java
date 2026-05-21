package com.github.groundbreakingmc.moderntags.config.model;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record TagGroup(
        int priority,
        @Nullable Condition ownerCondition,
        @NotNull List<TagEntry> entries
) {

    public boolean passCondition(@NotNull Context context) {
        return this.ownerCondition == null || this.ownerCondition.test(context);
    }
}
