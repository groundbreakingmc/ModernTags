package com.github.groundbreakingmc.moderntags.manager;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.config.model.TagTemplate;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.TimeUnit;

public final class UpdateScheduler {

    private final ModernTags plugin;
    private final PlayerTagManager tagManager;
    private ScheduledTask updateTask;

    public UpdateScheduler(ModernTags plugin, PlayerTagManager tagManager) {
        this.plugin = plugin;
        this.tagManager = tagManager;
    }

    public void start() {
        this.updateTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                this.plugin,
                task -> tick(),
                1L,
                1L,
                TimeUnit.MILLISECONDS
        );
    }

    public void stop() {
        if (this.updateTask != null && !this.updateTask.isCancelled()) {
            this.updateTask.cancel();
            this.updateTask = null;
        }
    }

    private void tick() {
        try {
            this.processTick();
        } catch (Exception ex) {
            this.plugin.getLogger().severe("Critical error in tag update scheduler: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void processTick() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (!player.isOnline()) {
                    // this code shouldn't be called at all,
                    // because we remove the player's tag on the Quit event.
                    this.tagManager.cleanup(player);
                    continue;
                }
                if (!player.isValid()) continue;

                this.tagManager.updateVisibility(player);

                if (this.tagManager.updateTag(player)) continue;

                final PlayerTagManager.PlayerData entry = this.tagManager.getPlayerData(player);

                final TagTemplate template = entry.tagTemplate();
                entry.incrementTicks();

                final int frameUpdateRate = template.frameUpdateRate();
                if (frameUpdateRate > 0 && entry.ticksSinceLastUpdate() % frameUpdateRate == 0) {
                    this.tagManager.updateFrame(player);
                    entry.resetTicks();
                } else {
                    final int placeholderUpdateRate = template.placeholdersUpdateRate();
                    if (placeholderUpdateRate > 0 && entry.ticksSinceLastUpdate() % placeholderUpdateRate == 0) {
                        this.tagManager.updatePlaceholders(player);
                    }
                }
            } catch (Exception e) {
                this.plugin.getLogger().warning("Error updating tag for player " + player.getName() + ": " + e.getMessage());
                // Continue processing other players
            }
        }
    }
}
