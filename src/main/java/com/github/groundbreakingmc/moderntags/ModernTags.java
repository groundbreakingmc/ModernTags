package com.github.groundbreakingmc.moderntags;

import com.github.groundbreakingmc.moderntags.command.ReloadCommand;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.groundbreakingmc.moderntags.listener.PacketListener;
import com.github.groundbreakingmc.moderntags.manager.PlayerTagManager;
import com.github.groundbreakingmc.moderntags.manager.UpdateScheduler;
import com.github.groundbreakingmc.moderntags.text.PlaceholderParser;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.nio.file.Path;

public final class ModernTags extends JavaPlugin {

    private PlayerTagManager tagManager;
    private UpdateScheduler updateScheduler;
    private PlaceholderParser placeholderParser;
    private PacketListenerCommon listener;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        this.placeholderParser = this.initializePlaceholderParser();
        this.tagManager = new PlayerTagManager(this, this.placeholderParser);

        this.loadConfiguration();

        this.updateScheduler = new UpdateScheduler(this, this.tagManager);
        this.updateScheduler.start();

        this.listener = PacketEvents.getAPI().getEventManager().registerListener(
                new PacketListener(this, this.tagManager, this.placeholderParser)
        );

        this.getCommand("moderntags").setExecutor(new ReloadCommand(this));

        final AsyncScheduler scheduler = super.getServer().getAsyncScheduler();

        scheduler.runNow(this, task -> this.tagManager.initializeAll());

        scheduler.runNow(this, (task) -> {
            final RegisteredServiceProvider<Chat> registration = super.getServer().getServicesManager().getRegistration(Chat.class);
            if (registration != null) {
                this.placeholderParser.chat(registration.getProvider());
            }
        });
    }

    @Override
    public void onDisable() {
        if (this.updateScheduler != null) {
            this.updateScheduler.stop();
        }

        if (this.tagManager != null) {
            super.getServer().getOnlinePlayers().forEach(this.tagManager::cleanup);
        }

        if (this.listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(this.listener);
        }
    }

    public void reload() {
        super.getServer().getOnlinePlayers().forEach(this.tagManager::cleanup);

        if (this.updateScheduler != null) {
            this.updateScheduler.stop();
        }

        this.loadConfiguration();

        this.updateScheduler = new UpdateScheduler(this, this.tagManager);
        this.updateScheduler.start();

        super.getServer().getOnlinePlayers().forEach(this.tagManager::updateTag);
    }

    private void loadConfiguration() {
        try {
            final Path configPath = this.getDataFolder().toPath().resolve("config.yml");
            final ConfigValues values = new ConfigValues(super.getLogger(), configPath);
            values.setup();
            this.placeholderParser.useMinimessageColorizer(values.useMinimessageColorizer());
            this.tagManager.setTags(values.tags());
            this.tagManager.hideTagWhenHasPassenger(values.hideTagWhenHasPassenger());
        } catch (Exception ex) {
            throw new RuntimeException("Error loading configuration", ex);
        }
    }

    private PlaceholderParser initializePlaceholderParser() {
        final PluginManager pluginManager = super.getServer().getPluginManager();
        final boolean supportsPapi = pluginManager.getPlugin("PlaceholderAPI") != null;
        final boolean supportsVault = pluginManager.getPlugin("Vault") != null;
        return new PlaceholderParser(supportsVault, supportsPapi);
    }
}
