package com.github.groundbreakingmc.moderntags.util;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

@FunctionalInterface
public interface ValueProvider<T> {

    @Nullable T value(@UnknownNullability Player context);
}
