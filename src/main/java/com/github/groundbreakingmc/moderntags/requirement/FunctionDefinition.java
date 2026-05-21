package com.github.groundbreakingmc.moderntags.requirement;

import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@FunctionalInterface
public interface FunctionDefinition {

    @NotNull ValueProvider<?> create(@NotNull List<ValueProvider<?>> arguments);
}
