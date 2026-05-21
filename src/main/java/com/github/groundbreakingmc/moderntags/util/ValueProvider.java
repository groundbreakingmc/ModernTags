package com.github.groundbreakingmc.moderntags.util;

import com.github.groundbreakingmc.moderntags.requirement.Context;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

@FunctionalInterface
public interface ValueProvider<T> {

    @Nullable T value(@UnknownNullability Context context);
}
