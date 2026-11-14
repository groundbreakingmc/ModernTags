package com.github.groundbreakingmc.moderntags.requirement.impl.basic;

import com.github.groundbreakingmc.moderntags.requirement.Condition;
import org.bukkit.entity.Player;
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
    public boolean test(@NotNull Player player) {
        return !Comparison.equal(this.left.value(player), this.right.value(player));
    }
}
