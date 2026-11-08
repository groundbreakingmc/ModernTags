package com.github.groundbreakingmc.moderntags.requirement;

import com.github.groundbreakingmc.moderntags.util.ValueProvider;
import com.github.retrooper.packetevents.PacketEvents;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Locale;
import java.util.Map;

/** Creates literal, built-in and placeholder value providers for conditions. */
final class ConditionValues {

    private static final Map<String, ValueProvider<?>> VARIABLES = Map.of(
            "protocol_version", player -> PacketEvents.getAPI().getPlayerManager()
                    .getClientVersion(player).getProtocolVersion()
    );

    private ConditionValues() {
    }

    static <T> ValueProvider<T> literal(T value) {
        return new Literal<>(value);
    }

    static @Nullable ValueProvider<?> variable(String name) {
        return VARIABLES.get(name);
    }

    static ValueProvider<String> placeholder(String key) {
        final int separator = key.indexOf('_');
        final var identifier = (separator != -1 ? key.substring(0, separator) : key)
                .toLowerCase(Locale.ROOT);
        final var params = separator != -1 ? key.substring(separator + 1) : "";

        return player -> {
            // Resolve on evaluation so expansion registration changes remain visible.
            final var expansion = PlaceholderAPIPlugin.getInstance()
                    .getLocalExpansionManager()
                    .getExpansion(identifier);
            return expansion != null
                    ? expansion.onRequest(player, params)
                    : "no expansion found for placeholder: %" + key + "%";
        };
    }

    /** A literal remains identifiable during function binding without evaluating a provider. */
    record Literal<T>(T constant) implements ValueProvider<T> {

        /** Returns the captured literal without consulting the player. */
        @Override
        public @Nullable T value(@UnknownNullability Player context) {
            return this.constant;
        }
    }
}
