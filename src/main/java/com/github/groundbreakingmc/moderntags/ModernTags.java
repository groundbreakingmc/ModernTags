package com.github.groundbreakingmc.moderntags;

import com.github.groundbreakingmc.moderntags.command.ReloadCommand;
import com.github.groundbreakingmc.moderntags.config.ConfigValues;
import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.listener.BukkitListener;
import com.github.groundbreakingmc.moderntags.listener.PacketListener;
import com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer;
import com.github.groundbreakingmc.moderntags.renderer.ModernRenderer;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.groundbreakingmc.moderntags.update.UpdateManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public final class ModernTags extends JavaPlugin {

    private TagTextResolver tagTextResolver;
    private ConfigValues configValues;
    private RenderLoop renderLoop;
    private UpdateManager updateManager;
    private PacketListenerCommon packetListener;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        this.tagTextResolver = this.createTagTextResolver();
        this.renderLoop = new RenderLoop();

        this.configValues = new ConfigValues(this.getDataFolder(), this.tagTextResolver);
        this.configValues.registerModernFactory(cfg -> ModernRenderer.of(this, cfg));
        this.configValues.registerLegacyFactory(cfg -> LegacyRenderer.of(
                this.tagTextResolver,
                this.configValues.belowNameValueParser(),
                this.configValues.belowNameChar(),
                cfg
        ));

        this.loadConfiguration();

        this.updateManager = new UpdateManager(this, this.renderLoop);
        this.updateManager.start(this.configValues.tickRate());

        this.packetListener = PacketEvents.getAPI().getEventManager().registerListener(
                new PacketListener(this, this.renderLoop)
        );

        super.getServer().getPluginManager().registerEvents(new BukkitListener(this.renderLoop), this);

        this.getCommand("moderntags").setExecutor(new ReloadCommand(this));

        // Initialize all (target, viewer) pairs — RenderLoop drain handles threading.
        Bukkit.getAsyncScheduler().runNow(this, task ->
                this.renderLoop.post(new RenderTask.InitializeAll()));

        // Resolve Vault async — services may not be registered yet at this point.
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            final RegisteredServiceProvider<Chat> reg =
                    getServer().getServicesManager().getRegistration(Chat.class);
            if (reg != null) this.tagTextResolver.chat(reg.getProvider());
        });
    }

    @Override
    public void onDisable() {
        if (this.updateManager != null) this.updateManager.stop();

        if (this.renderLoop != null) {
            this.renderLoop.post(new RenderTask.InvalidateAll());
        }

        if (this.packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(this.packetListener);
        }
    }

    @Override
    public void saveDefaultConfig() {
        final File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            saveResource("tags/default.yml", false);
            saveResource("tags/default-legacy.yml", false);
            saveResource("tags/vip.yml", false);
        }
    }

    /**
     * Tears down all rendering state, reloads configuration, and re-initializes.
     */
    public CompletableFuture<Void> reload() {
        return CompletableFuture.runAsync(() -> {
            this.renderLoop.post(new RenderTask.InvalidateAll());
            this.updateManager.stop();

            this.loadConfiguration();

            Bukkit.getAsyncScheduler().runNow(this, $$ -> {
                this.renderLoop.post(new RenderTask.InitializeAll());
                this.updateManager.start(this.configValues.tickRate());
            });
        });
    }

    public TagTextResolver tagTextResolver() {
        return this.tagTextResolver;
    }

    public ConfigValues configValues() {
        return this.configValues;
    }

    public RenderLoop renderLoop() {
        return this.renderLoop;
    }

    private void loadConfiguration() {
        this.configValues.setup();
        this.tagTextResolver.miniMessageVault(this.configValues.useMinimessageColorizer());
        this.renderLoop.groups(this.configValues.tags());
        this.renderLoop.hideTagWhenHasPassenger(this.configValues.hideTagWhenHasPassenger());
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
