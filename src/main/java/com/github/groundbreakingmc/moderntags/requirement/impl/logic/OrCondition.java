package com.github.groundbreakingmc.moderntags.requirement.impl.logic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import org.jetbrains.annotations.NotNull;

public final class OrCondition implements Condition {

    private final Condition left;
    private final Condition right;

    public OrCondition(Condition left, Condition right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean test(@NotNull Context context) {
        return this.left.test(context) || this.right.test(context);
    }
}
