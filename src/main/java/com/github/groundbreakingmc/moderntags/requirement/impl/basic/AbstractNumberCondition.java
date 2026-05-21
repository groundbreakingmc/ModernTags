package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public abstract class AbstractNumberCondition implements Condition {

    protected final ValueProvider<?> left;
    protected final ValueProvider<?> right;

    protected AbstractNumberCondition(
            @NotNull ValueProvider<?> left,
            @NotNull ValueProvider<?> right
    ) {
        this.left = Objects.requireNonNull(left, "left can't be null");
        this.right = Objects.requireNonNull(right, "right can't be null");
    }

    @Override
    public boolean test(@NotNull Context context) {
        final Number leftVal = this.toNumber(this.left.value(context), "left");
        final Number rightVal = this.toNumber(this.right.value(context), "right");
        return this.compare(leftVal, rightVal);
    }

    protected abstract boolean compare(Number left, Number right);

    protected Number toNumber(Object obj, String side) {
        if (obj instanceof Number number) {
            return number;
        }

        if (obj instanceof Boolean bool) {
            return bool ? 1 : 0;
        }

        if (obj instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
            }

            if ("true".equalsIgnoreCase(str)) {
                return 1;
            }
            if ("false".equalsIgnoreCase(str)) {
                return 0;
            }
        }

        throw new IllegalStateException(
                "Unsupported value type on " + side + ": "
                        + obj.getClass().getName()
        );
    }
}
