package com.github.groundbreakingmc.moderntags.text;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.gikymessage.ValueResolver;
import com.github.groundbreakingmc.moderntags.util.NumberUtils;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.ToIntBiFunction;
import java.util.logging.Logger;

/** Compiles owner, viewer and relational placeholders for GikyMessage. */
public final class TagTextResolver {

    private static final ToIntBiFunction<Player, Player> ZERO = (owner, viewer) -> 0;

    private final Logger logger;
    private final @Nullable Chat chat;
    private final boolean supportsPapi;

    /**
     * Creates a resolver with integrations captured at startup.
     *
     * @param logger receives placeholder compilation warnings
     * @param chat Vault chat provider, or null when unavailable
     * @param supportsPapi whether PlaceholderAPI expansions can be resolved
     */
    public TagTextResolver(Logger logger, @Nullable Chat chat, boolean supportsPapi) {
        this.logger = logger;
        this.chat = chat;
        this.supportsPapi = supportsPapi;
    }

    /** Binds each key and its Vault formatting mode to the configuration that compiled the template. */
    public @NotNull Template compile(@NotNull String pattern, boolean miniMessageVault) {
        final boolean[] viewerDependent = {false};
        final var compiled = Text.cacheableOf(pattern, key -> {
            if (dependsOnViewer(key)) {
                viewerDependent[0] = true;
            }
            return this.bindText(key, miniMessageVault);
        });
        return new Template(compiled, viewerDependent[0]);
    }

    public @NotNull Component resolve(@NotNull Context players, @NotNull Template template) {
        return template.compiled().render(players);
    }

    /**
     * Compiles a numeric expression once. Built-in health and plain PAPI results
     * do not need an intermediate Component or a plain-text serialization.
     * The result retains its viewer dependency and captures Vault formatting from
     * the compiling configuration.
     */
    public @NotNull NumericTemplate compileNumeric(@NotNull String pattern, boolean miniMessageVault) {
        final ToIntBiFunction<Player, Player> compiled = switch (pattern) {
            case "" -> ZERO;
            case "owner:health" -> (owner, viewer) -> NumberUtils.healthToInt(owner.getHealth());
            case "viewer:health" -> (owner, viewer) -> NumberUtils.healthToInt(viewer.getHealth());

            case "owner:name" -> (owner, viewer) -> parseInt(owner.getName());
            case "viewer:name" -> (owner, viewer) -> parseInt(viewer.getName());

            case "owner:display_name" -> (owner, viewer) -> componentToInt(owner.displayName());
            case "viewer:display_name" -> (owner, viewer) -> componentToInt(viewer.displayName());

            case "owner:prefix" -> (owner, viewer) -> componentToInt(this.vaultComponent(owner, true, miniMessageVault));
            case "owner:suffix" -> (owner, viewer) -> componentToInt(this.vaultComponent(owner, false, miniMessageVault));
            case "viewer:prefix" -> (owner, viewer) -> componentToInt(this.vaultComponent(viewer, true, miniMessageVault));
            case "viewer:suffix" -> (owner, viewer) -> componentToInt(this.vaultComponent(viewer, false, miniMessageVault));

            default -> this.bindNumericPapi(pattern);
        };
        return new NumericTemplate(compiled, dependsOnViewer(pattern));
    }

    private static boolean dependsOnViewer(String key) {
        return key.startsWith("viewer:") || key.startsWith("rel:");
    }

    private @NotNull ValueResolver<Context> bindText(@NotNull String key, boolean miniMessageVault) {
        return switch (key) {
            case "owner:name" -> (pair, value) -> value.text(pair.owner().getName());
            case "viewer:name" -> (pair, value) -> value.text(pair.viewer().getName());

            case "owner:health" -> (pair, value) -> value.number(NumberUtils.healthToInt(pair.owner().getHealth()));
            case "viewer:health" -> (pair, value) -> value.number(NumberUtils.healthToInt(pair.viewer().getHealth()));

            case "owner:display_name" -> (pair, value) -> value.component(pair.owner().displayName());
            case "viewer:display_name" -> (pair, value) -> value.component(pair.viewer().displayName());

            case "owner:prefix" -> (pair, value) -> value.component(this.vaultComponent(pair.owner(), true, miniMessageVault));
            case "owner:suffix" -> (pair, value) -> value.component(this.vaultComponent(pair.owner(), false, miniMessageVault));
            case "viewer:prefix" -> (pair, value) -> value.component(this.vaultComponent(pair.viewer(), true, miniMessageVault));
            case "viewer:suffix" -> (pair, value) -> value.component(this.vaultComponent(pair.viewer(), false, miniMessageVault));

            default -> this.bindTextPapi(key);
        };
    }

    private @NotNull ValueResolver<Context> bindTextPapi(@NotNull String key) {
        final var provider = this.bindPapi(key);
        if (provider != null) {
            return (pair, value) -> value.text(provider.apply(pair.owner(), pair.viewer()));
        }

        final Component fallback = this.unknownPlaceholder(key);
        return (pair, value) -> value.component(fallback);
    }

    private @NotNull ToIntBiFunction<Player, Player> bindNumericPapi(@NotNull String key) {
        final var provider = this.bindPapi(key);
        if (provider != null) {
            return (owner, viewer) -> parseInt(provider.apply(owner, viewer));
        }

        this.warnUnknown(key);
        return ZERO;
    }

    /** Splits the key and specializes the callback once, avoiding a scope switch per render. */
    private @Nullable BiFunction<Player, Player, String> bindPapi(@NotNull String key) {
        final Scope scope;
        final int offset;
        if (key.startsWith("owner:")) {
            scope = Scope.OWNER;
            offset = 6;
        } else if (key.startsWith("viewer:")) {
            scope = Scope.VIEWER;
            offset = 7;
        } else if (key.startsWith("rel:")) {
            scope = Scope.RELATIONAL;
            offset = 4;
        } else {
            return null;
        }

        final String raw = key.substring(offset);
        final int separator = raw.indexOf('_');
        if (scope == Scope.RELATIONAL && separator < 0) {
            return (owner, viewer) -> null;
        }

        final String identifier = separator < 0 ? raw : raw.substring(0, separator);
        final String params = separator < 0 ? "" : raw.substring(separator + 1);

        return switch (scope) {
            case OWNER -> (owner, viewer) -> {
                final var expansion = this.findExpansion(identifier);
                return expansion == null ? null : expansion.onRequest(owner, params);
            };
            case VIEWER -> (owner, viewer) -> {
                final var expansion = this.findExpansion(identifier);
                return expansion == null ? null : expansion.onRequest(viewer, params);
            };
            case RELATIONAL -> (owner, viewer) -> {
                final var expansion = this.findExpansion(identifier);
                return expansion instanceof Relational relational
                        ? relational.onPlaceholderRequest(viewer, owner, params) : null;
            };
        };
    }

    private @Nullable PlaceholderExpansion findExpansion(@NotNull String identifier) {
        if (!this.supportsPapi) return null;
        // Deliberately dynamic: expansion registration can change after compilation.
        return PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier);
    }

    private @Nullable Component vaultComponent(@NotNull Player player, boolean prefix, boolean miniMessageVault) {
        if (this.chat == null) return null;

        final String raw = prefix ? this.chat.getPlayerPrefix(player) : this.chat.getPlayerSuffix(player);
        if (raw == null || raw.isEmpty()) return Component.empty();

        return miniMessageVault
                ? MiniMessage.miniMessage().deserialize(raw)
                : LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    private @NotNull Component unknownPlaceholder(@NotNull String key) {
        this.warnUnknown(key);
        return Component.text('{' + key + '}');
    }

    private void warnUnknown(@NotNull String key) {
        this.logger.warning("Unknown placeholder '" + key + "'. "
                + "If this is intended to be plain text and not a placeholder, escape it using '\\'.");
    }

    private static int componentToInt(@Nullable Component component) {
        return component == null ? 0 : parseInt(PlainTextComponentSerializer.plainText().serialize(component));
    }

    private static int parseInt(@Nullable String value) {
        if (value == null) return 0;
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private enum Scope { OWNER, VIEWER, RELATIONAL }

    /**
     * Player identities used when evaluating a text template.
     */
    public interface Context {

        /**
         * @return the player whose tag is being evaluated
         */
        @NotNull Player owner();

        /**
         * @return the player observing the tag
         */
        @NotNull Player viewer();
    }

    public record Template(@NotNull Text<Context> compiled, boolean viewerDependent) {
    }

    /**
     * An immutable numeric placeholder compiled once for a configuration snapshot.
     *
     * @param compiled integer resolver; owner-only results can be shared among viewers
     * @param viewerDependent whether evaluation needs the actual viewer, including relational placeholders
     */
    public record NumericTemplate(@NotNull ToIntBiFunction<Player, Player> compiled, boolean viewerDependent) {
    }
}
