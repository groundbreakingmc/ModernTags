package com.github.groundbreakingmc.moderntags.requirement;

import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Compiles a function call once while its condition is loaded. */
@FunctionalInterface
public interface FunctionDefinition {

    /**
     * Validates parsed arguments and binds the function's runtime value provider.
     * Implementations retain only the values or providers they need, not the argument list.
     *
     * @param arguments value providers parsed from the function call
     * @return the provider evaluated with the actual player when checking the condition
     * @throws IllegalArgumentException if the argument count or types are invalid
     */
    @NotNull ValueProvider<?> create(@NotNull List<ValueProvider<?>> arguments);
}
