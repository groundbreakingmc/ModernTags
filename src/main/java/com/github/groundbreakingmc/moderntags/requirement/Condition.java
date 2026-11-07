package com.github.groundbreakingmc.moderntags.requirement;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface Condition {

    boolean test(@NotNull Player player);
}
