package com.github.groundbreakingmc.moderntags.requirement.impl.logic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class NotCondition implements Condition {

    private final Condition condition;

    public NotCondition(Condition condition) {
        this.condition = condition;
    }

    @Override
    public boolean test(@NotNull Player player) {
        return !this.condition.test(player);
    }
}
