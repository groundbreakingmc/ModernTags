package com.github.groundbreakingmc.moderntags.text;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.platform.PlayerAdapter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a player's prefix and suffix from LuckPerms' own cached meta data.
 *
 * <p>Both ways start from the query options LuckPerms keeps with the player: it holds them for
 * 50 ms, shares them with every permission check and calculates them again when asked later.
 * LuckPerms' Vault provider then builds options of its own on top for every call: it looks the
 * player up, checks the operator list and creates a new options object. The player adapter
 * stops at the kept ones and reaches the same meta cache with them. LuckPerms' Vault-only
 * settings, {@code use-vault-server} and {@code vault-include-global}, therefore do not apply
 * to these lookups; its general {@code include-global} and {@code apply-global-groups} family
 * of settings applies instead, as it does for permission checks.
 *
 * <p>This is a class of its own so that LuckPerms' API is loaded only when LuckPerms is installed.
 */
final class LuckPermsMeta {

    private final PlayerAdapter<Player> players = LuckPermsProvider.get().getPlayerAdapter(Player.class);

    /** Returns the prefix that applies to an online player now, or null when there is none. */
    @Nullable String prefix(@NotNull Player player) {
        return this.players.getMetaData(player).getPrefix();
    }

    /** Returns the suffix that applies to an online player now, or null when there is none. */
    @Nullable String suffix(@NotNull Player player) {
        return this.players.getMetaData(player).getSuffix();
    }
}
