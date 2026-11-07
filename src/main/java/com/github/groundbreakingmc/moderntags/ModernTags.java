package com.github.groundbreakingmc.moderntags;

import com.github.groundbreakingmc.moderntags.command.ModernTagsCommand;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.groundbreakingmc.moderntags.listener.PacketListener;
import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.groundbreakingmc.mylib.updateschecker.UpdatesChecker;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import net.milkbowl.vault.chat.Chat;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ModernTags extends JavaPlugin {

    private TagTextResolver tagTextResolver;
    private volatile ConfigValues configValues;
    private PacketListenerCommon packetListener;
    private Metrics metrics;
    private TagManager tags;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        this.tagTextResolver = this.createTagTextResolver();
        final var chatRegistration = getServer().getPluginManager().isPluginEnabled("Vault")
                ? getServer().getServicesManager().getRegistration(Chat.class) : null;
        if (chatRegistration != null) this.tagTextResolver.chat(chatRegistration.getProvider());

        this.reload();

        this.tags = new TagManager(this);
        this.packetListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener(this, this.tags));
        this.tags.start();

        final var updatesChecker = new UpdatesChecker(this,
                "https://raw.githubusercontent.com/groundbreakingmc/ModernTags/main/update.txt",
                !this.configValues.autoDownloadUpdates() ? "/moderntags update" : "");
        this.getCommand("moderntags").setExecutor(new ModernTagsCommand(this, updatesChecker));

        this.metrics = new Metrics(this, 34701);
        if (this.configValues.checkForUpdates()) {
            updatesChecker.check(this.configValues.autoDownloadUpdates());
        }
    }

    @Override
    public void onDisable() {
        if (this.metrics != null) this.metrics.shutdown();
        if (this.packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(this.packetListener);
        }
        if (this.tags != null) this.tags.close();
    }

    @Override
    public void saveDefaultConfig() {
        final var configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            saveResource("tags/default.yml", false);
            saveResource("tags/default-legacy.yml", false);
            saveResource("tags/vip.yml", false);
        }
    }

    /** Reloads synchronously on the server thread; a load error preserves the previous configuration. */
    public void reload() {
        this.configValues = ConfigValues.of(this.getDataFolder(), this.tagTextResolver);
    }

    public TagTextResolver tagTextResolver() {
        return this.tagTextResolver;
    }

    public ConfigValues configValues() {
        return this.configValues;
    }

    private TagTextResolver createTagTextResolver() {
        final var pm = getServer().getPluginManager();
        return new TagTextResolver(
                getLogger(),
                pm.getPlugin("Vault") != null,
                pm.getPlugin("PlaceholderAPI") != null
        );
    }
}
