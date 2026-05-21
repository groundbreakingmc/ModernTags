package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

public final class LessThanOrEqualCondition extends AbstractNumberCondition {

    public LessThanOrEqualCondition(
            @NotNull ValueProvider<?> left,
            @NotNull ValueProvider<?> right
    ) {
        super(left, right);
    }

    @Override
    protected boolean compare(Number left, Number right) {
        return left.doubleValue() <= right.doubleValue();
    }
}
