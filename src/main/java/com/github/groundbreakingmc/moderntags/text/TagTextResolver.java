package com.github.groundbreakingmc.moderntags.text;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.util.NumberUtils;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntBiFunction;
import java.util.logging.Logger;

/**
 * Resolves player-specific placeholders at render time.
 * <p>
 * Supports two-context syntax: <code>{owner:key}</code> resolves against the tag's target player,
 * <code>{viewer:key}</code> resolves against the observing player. Bare keys (no prefix) fall back
 * to the owner context for backward compatibility.
 * <p>
 * Built-in keys: {@code name}, {@code display_name}, {@code health}, {@code prefix}, {@code suffix}.
 * Any other key is forwarded to PlaceholderAPI, where the identifier is the part before the first
 * underscore and the remainder is passed as params.
 * <p>
 * Templates must be compiled once via {@link Text#cacheableOf(String)} at config load and then
 * passed to {@link #resolve(Player, Player, Text)} at every render tick.
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
     * Renders a pre-compiled template with full two-player context.
     *
     * @param owner    tag target — fills {@code {owner:...}} placeholders
     * @param viewer   observing player — fills {@code {viewer:...}} placeholders
     * @param compiled template produced by {@link Text#cacheableOf(String)}
     */
    public Component resolve(@NotNull Player owner, @NotNull Player viewer, @NotNull Text compiled) {
        return compiled.render(key -> this.dispatch(owner, viewer, key));
    }

    /**
     * Returns a lazy function that resolves {@code pattern} against the chosen player at call time
     * and parses the result as an integer.
     * <p>
     * Pattern format: {@code owner:key} / {@code viewer:key}, or a bare PAPI key resolved against
     * the owner.
     *
     * @param pattern placeholder pattern string from config (e.g. {@code "owner:health"})
     * @return {@code ToIntBiFunction<target, viewer>}
     */
    public @NotNull ToIntBiFunction<Player, Player> intFromPlaceholder(@NotNull String pattern) {
        if (pattern.isEmpty()) return (t, v) -> 0;

        return (target, viewer) -> {
            final Component component = this.dispatch(target, viewer, pattern);
            if (component == null) return 0;
            final String raw = PlainTextComponentSerializer.plainText().serialize(component).trim();
            try {
                return (int) Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        };
    }

    private @Nullable Component dispatch(@NotNull Player owner,
                                         @NotNull Player viewer,
                                         @NotNull String key) {
        return switch (key) {
            case "owner:name" -> owner.name();
            case "owner:display_name" -> owner.displayName();
            case "owner:health" -> Component.text(NumberUtils.healthToStr(owner.getHealth()));
            case "owner:prefix" -> this.vaultComponent(owner, true);
            case "owner:suffix" -> this.vaultComponent(owner, false);

            case "viewer:name" -> viewer.name();
            case "viewer:display_name" -> viewer.displayName();
            case "viewer:health" -> Component.text(NumberUtils.healthToStr(viewer.getHealth()));
            case "viewer:prefix" -> this.vaultComponent(viewer, true);
            case "viewer:suffix" -> this.vaultComponent(viewer, false);

            default -> {
                if (key.startsWith("owner:")) {
                    yield this.papiComponent(owner, key.substring(6));
                }

                if (key.startsWith("viewer:")) {
                    yield this.papiComponent(viewer, key.substring(6));
                }

                this.logger.warning(
                        "Unknown placeholder '" + key + "'. " +
                                "If this is intended to be plain text and not a placeholder, " +
                                "escape it using '\\'."
                );
                yield Component.text('{' + key + '}');
            }
        };
    }

    // ── Vault ─────────────────────────────────────────────────────────────────

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

    // ── PlaceholderAPI ────────────────────────────────────────────────────────

    private @Nullable Component papiComponent(@NotNull Player player, @NotNull String key) {
        if (!this.supportsPapi) return null;

        final int sep = key.indexOf('_');
        final String identifier = sep != -1 ? key.substring(0, sep) : key;
        final PlaceholderExpansion expansion = PlaceholderAPIPlugin.getInstance()
                .getLocalExpansionManager()
                .getExpansion(identifier);

        if (expansion == null) return null;

        final String params = sep != -1 ? key.substring(sep + 1) : "";
        final String result = expansion.onRequest(player, params);
        return result != null ? Component.text(result) : null;
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void chat(@Nullable Chat chat) {
        this.chat = chat;
    }

    public void miniMessageVault(boolean use) {
        this.miniMessageVault = use;
    }
}
