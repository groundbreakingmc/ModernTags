package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class NotEqualsCondition implements Condition {

    private final ValueProvider<?> left;
    private final ValueProvider<?> right;

    public NotEqualsCondition(
            @NotNull ValueProvider<?> left,
            @NotNull ValueProvider<?> right
    ) {
        this.left = Objects.requireNonNull(left, "left can't be null");
        this.right = Objects.requireNonNull(right, "right can't be null");
    }

    @Override
    public boolean test(@NotNull Context context) {
        final Object left = this.left.value(context);
        final Object right = this.right.value(context);
        return left == null || right == null ? left != right : !left.equals(right);
    }
}
