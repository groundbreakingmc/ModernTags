package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

public final class BooleanCondition implements Condition {

    private final ValueProvider<?> provider;

    public BooleanCondition(@NotNull ValueProvider<?> provider) {
        this.provider = provider;
    }

    @Override
    public boolean test(@NotNull Context context) {
        final Object value = this.provider.value(context);

        if (value instanceof Boolean bool) {
            return bool;
        }

        throw new IllegalStateException(
                "Expected boolean value but got: " +
                        (value == null ? "null" : value.getClass().getSimpleName())
        );
    }
}
