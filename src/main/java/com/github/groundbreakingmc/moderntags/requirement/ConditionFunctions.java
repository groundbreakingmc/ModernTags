package com.github.groundbreakingmc.moderntags.requirement;

import com.github.groundbreakingmc.moderntags.util.ValueProvider;

import java.util.List;
import java.util.Map;

/** Binds built-in functions while loading a condition. */
final class ConditionFunctions {

    private static final Map<String, FunctionDefinition> FUNCTIONS = Map.of(
            "hasPermission", ConditionFunctions::hasPermission
    );

    private ConditionFunctions() {
    }

    static ValueProvider<?> compile(String name, List<ValueProvider<?>> arguments) {
        final var function = FUNCTIONS.get(name);
        if (function == null) {
            throw new IllegalArgumentException("Unknown function: '" + name + "'");
        }
        return function.create(arguments);
    }

    private static ValueProvider<Boolean> hasPermission(List<ValueProvider<?>> arguments) {
        if (arguments.size() != 1
                || !(arguments.get(0) instanceof ConditionValues.Literal<?>(Object constant))
                || !(constant instanceof String permission)) {
            throw new IllegalArgumentException("Function 'hasPermission' expects exactly one quoted string argument");
        }
        return player -> player.hasPermission(permission);
    }
}
