package com.github.groundbreakingmc.moderntags.requirement.impl.logic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.Context;
import org.jetbrains.annotations.NotNull;

public final class NotCondition implements Condition {

    private final Condition condition;

    public NotCondition(Condition condition) {
        this.condition = condition;
    }

    @Override
    public boolean test(@NotNull Context context) {
        return !this.condition.test(context);
    }
}
