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
        final var value = this.provider.value(player);

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        throw new IllegalStateException(
                "Expected boolean value but got: " +
                        (value == null ? "null" : value.getClass().getSimpleName())
        );
    }
}
