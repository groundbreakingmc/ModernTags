package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import org.bukkit.entity.Player;
import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

public final class BooleanCondition implements Condition {

    private final ValueProvider<?> provider;

    public BooleanCondition(@NotNull ValueProvider<?> provider) {
        this.provider = provider;
    }

    @Override
    public boolean test(@NotNull Player player) {
        return Comparison.truth(this.provider.value(player));
    }
}
