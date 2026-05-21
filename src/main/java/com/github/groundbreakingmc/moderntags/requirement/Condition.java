package com.github.groundbreakingmc.moderntags.requirement;

import org.jetbrains.annotations.NotNull;

public interface Condition {

    boolean test(@NotNull Context context);
}
