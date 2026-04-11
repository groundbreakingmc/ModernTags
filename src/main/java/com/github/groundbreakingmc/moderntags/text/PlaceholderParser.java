package com.github.groundbreakingmc.moderntags.text;

import com.github.groundbreakingmc.moderntags.utils.NumberUtils;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class PlaceholderParser {

    private final Map<Player, TagResolver> tagCache = new Reference2ObjectOpenHashMap<>();
    private final boolean supportsVault;
    private final boolean supportsPapi;

    private Chat chat;
    private boolean useMinimessageColorizer;

    public PlaceholderParser(boolean supportsVault, boolean supportsPapi) {
        this.supportsVault = supportsVault;
        this.supportsPapi = supportsPapi;
    }

    public Component parsePlaceholders(@NotNull Player player, @NotNull String text) {
        TagResolver tagResolver = this.tagCache.get(player);
        if (tagResolver != null) {
            return MiniMessage.miniMessage().deserialize(text, tagResolver);
        }

        synchronized (this.tagCache) {
            tagResolver = this.tagCache.get(player);
            if (tagResolver != null) {
                return MiniMessage.miniMessage().deserialize(text, tagResolver);
            }

            tagResolver = this.createResolver(player);
            this.tagCache.put(player, tagResolver);
            return MiniMessage.miniMessage().deserialize(text, tagResolver);
        }
    }

    public void cleanup(@NotNull Player player) {
        synchronized (this.tagCache) {
            this.tagCache.remove(player);
        }
    }

    private TagResolver createResolver(@NotNull Player player) {
        return TagResolver.resolver("placeholder", (argumentQueue, context) -> {
            final String placeholder = argumentQueue.popOr("placeholder tag requires an argument").value();
            switch (placeholder) {
                case "prefix" -> {
                    if (!this.supportsVault || this.chat == null) {
                        return Tag.selfClosingInserting(Component.text("Vault not found"));
                    }
                    final String prefix = this.chat.getPlayerPrefix(player);
                    return Tag.selfClosingInserting(
                            this.useMinimessageColorizer
                                    ? MiniMessage.miniMessage().deserialize(prefix)
                                    : LegacyComponentSerializer.legacyAmpersand().deserialize(prefix)
                    );
                }
                case "name" -> {
                    return Tag.selfClosingInserting(player.name());
                }
                case "display_name" -> {
                    return Tag.selfClosingInserting(player.displayName());
                }
                case "suffix" -> {
                    if (!this.supportsVault || this.chat == null) {
                        return Tag.selfClosingInserting(Component.text("Vault not found"));
                    }
                    final String suffix = this.chat.getPlayerSuffix(player);
                    return Tag.selfClosingInserting(
                            this.useMinimessageColorizer
                                    ? MiniMessage.miniMessage().deserialize(suffix)
                                    : LegacyComponentSerializer.legacyAmpersand().deserialize(suffix)
                    );
                }
                case "health" -> {
                    final double health = player.getHealth();
                    return Tag.selfClosingInserting(Component.text(
                            // 19.3 -> 19.0
                            // 20.0 -> 20
                            NumberUtils.doubleToStr(health)
                    ));
                }
                default -> {
                    if (!this.supportsPapi) {
                        return Tag.selfClosingInserting(Component.text("PlaceholderAPI not found"));
                    }
                    final int i = placeholder.indexOf('_');
                    final String identifier = i != -1
                            ? placeholder.substring(0, i)
                            : placeholder;
                    final PlaceholderExpansion expansion = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier);
                    if (expansion == null) {
                        return Tag.selfClosingInserting(Component.text(placeholder));
                    }
                    final String params = i != -1
                            ? placeholder.substring(i + 1)
                            : "";
                    final String result = expansion.onRequest(player, params);
                    return Tag.selfClosingInserting(Component.text(
                            result != null ? result : "null"
                    ));
                }
            }
        });
    }

    public void useMinimessageColorizer(boolean use) {
        this.useMinimessageColorizer = use;
    }

    public void chat(Chat chat) {
        this.chat = chat;
    }
}
