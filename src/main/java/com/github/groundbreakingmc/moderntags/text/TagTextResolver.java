package com.github.groundbreakingmc.moderntags.text;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.core.ViewerState;
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

/**
 * Resolves player-specific placeholders at render time.
 * <p>
 * Supports two-context syntax: <code>{owner:key}</code> resolves against the tag's target player,
 * <code>{viewer:key}</code> resolves against the observing player. Relational placeholders use
 * {@code rel:identifier_params} with the viewer first and the owner second.
 * <p>
 * Built-in keys: {@code name}, {@code display_name}, {@code health}, {@code prefix}, {@code suffix}.
 * Other owner/viewer keys are forwarded to PlaceholderAPI, where the identifier is the part before
 * the first underscore and the remainder is passed as params. Unprefixed keys remain literal.
 * <p>
 * Templates are compiled once via {@link #compile(String)} at config load. Handlers are selected
 * then; player values, Vault services and PAPI expansions are read at render time.
 */
public final class TagTextResolver {

    private final Logger logger;
    private final boolean supportsVault;
    private final boolean supportsPapi;

    private @Nullable Chat chat;
    private boolean miniMessageVault;

    public TagTextResolver(Logger logger, boolean supportsVault, boolean supportsPapi) {
        this.logger = logger;
        this.supportsVault = supportsVault;
        this.supportsPapi = supportsPapi;
    }

    /**
     * Compiles a template with handlers bound to its unique placeholder keys.
     * Keeps GikyMessage's last-result cache per template and physical thread.
     */
    public @NotNull Text<ViewerState> compile(@NotNull String pattern) {
        return Text.cacheableOf(pattern, key -> {
            final var provider = this.bind(key);
            return (state, value) -> value.component(provider.apply(state.target, state.viewer));
        });
    }

    /**
     * Renders with the existing pair state as context; the compiled text does not retain it.
     */
    public Component resolve(@NotNull ViewerState state, @NotNull Text<ViewerState> compiled) {
        return compiled.render(state);
    }

    /**
     * Returns a lazy function that resolves {@code pattern} against the chosen player at call time
     * and parses the result as an integer.
     * <p>
     * Pattern format: {@code owner:key}, {@code viewer:key}, or {@code rel:identifier_params}.
     *
     * @param pattern placeholder pattern string from config (e.g. {@code "owner:health"})
     * @return {@code ToIntBiFunction<target, viewer>}
     */
    public @NotNull ToIntBiFunction<Player, Player> intFromPlaceholder(@NotNull String pattern) {
        if (pattern.isEmpty()) return (target, viewer) -> 0;

        final var provider = this.bind(pattern);
        return (target, viewer) -> {
            final var component = provider.apply(target, viewer);
            if (component == null) return 0;
            final String raw = PlainTextComponentSerializer.plainText().serialize(component).trim();
            try {
                return (int) Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        };
    }

    private @NotNull BiFunction<Player, Player, Component> bind(@NotNull String key) {
        return switch (key) {
            case "owner:name" -> (owner, viewer) -> owner.name();
            case "owner:display_name" -> (owner, viewer) -> owner.displayName();
            case "owner:health" -> (owner, viewer) -> Component.text(NumberUtils.healthToStr(owner.getHealth()));
            case "owner:prefix" -> (owner, viewer) -> this.vaultComponent(owner, true);
            case "owner:suffix" -> (owner, viewer) -> this.vaultComponent(owner, false);

            case "viewer:name" -> (owner, viewer) -> viewer.name();
            case "viewer:display_name" -> (owner, viewer) -> viewer.displayName();
            case "viewer:health" -> (owner, viewer) -> Component.text(NumberUtils.healthToStr(viewer.getHealth()));
            case "viewer:prefix" -> (owner, viewer) -> this.vaultComponent(viewer, true);
            case "viewer:suffix" -> (owner, viewer) -> this.vaultComponent(viewer, false);

            default -> {
                if (key.startsWith("owner:")) {
                    yield this.bindPapi(key.substring(6), false);
                }

                if (key.startsWith("viewer:")) {
                    yield this.bindPapi(key.substring(7), true);
                }

                if (key.startsWith("rel:")) {
                    final String relationalKey = key.substring(4);
                    final int sepIdx = relationalKey.indexOf('_');
                    if (sepIdx == -1) yield (owner, viewer) -> null;
                    final String identifier = relationalKey.substring(0, sepIdx);
                    final String params = relationalKey.substring(sepIdx + 1);
                    yield (owner, viewer) -> this.relationalComponent(viewer, owner, identifier, params);
                }

                final var fallback = Component.text('{' + key + '}');
                yield (owner, viewer) -> {
                    this.logger.warning(
                            "Unknown placeholder '" + key + "'. " +
                                    "If this is intended to be plain text and not a placeholder, " +
                                    "escape it using '\\'."
                    );
                    return fallback;
                };
            }
        };
    }

    private @Nullable Component vaultComponent(@NotNull Player player, boolean prefix) {
        if (!this.supportsVault || this.chat == null) return null;

        final String raw = prefix
                ? this.chat.getPlayerPrefix(player)
                : this.chat.getPlayerSuffix(player);

        if (raw == null || raw.isEmpty()) return Component.empty();

        return this.miniMessageVault
                ? MiniMessage.miniMessage().deserialize(raw)
                : LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    private @NotNull BiFunction<Player, Player, Component> bindPapi(@NotNull String key, boolean viewerContext) {
        final int sepIdx = key.indexOf('_');
        final String identifier = sepIdx != -1 ? key.substring(0, sepIdx) : key;
        final String params = sepIdx != -1 ? key.substring(sepIdx + 1) : "";
        return (owner, viewer) -> this.papiComponent(viewerContext ? viewer : owner, identifier, params);
    }

    private @Nullable Component papiComponent(@NotNull Player player, @NotNull String identifier,
                                              @NotNull String params) {
        final var expansion = this.findExpansion(identifier);

        if (expansion == null) return null;

        final String result = expansion.onRequest(player, params);
        return result != null ? Component.text(result) : null;
    }

    private @Nullable Component relationalComponent(
            @NotNull Player viewer,
            @NotNull Player owner,
            @NotNull String identifier,
            @NotNull String params
    ) {
        final var expansion = this.findExpansion(identifier);

        if (!(expansion instanceof Relational relational)) return null;

        final String result = relational.onPlaceholderRequest(
                viewer, owner, params
        );
        return result != null ? Component.text(result) : null;
    }

    private @Nullable PlaceholderExpansion findExpansion(@NotNull String identifier) {
        if (!this.supportsPapi) return null;
        // Keep the lookup at render time so changes to registered expansions take effect.
        return PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier);
    }

    public void chat(@Nullable Chat chat) {
        this.chat = chat;
    }

    public void miniMessageVault(boolean use) {
        this.miniMessageVault = use;
    }
}
