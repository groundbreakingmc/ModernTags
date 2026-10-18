package com.github.groundbreakingmc.moderntags.listener;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import com.github.groundbreakingmc.moderntags.util.PlayerLookup;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Maintains player ID lookups and posts render cleanup on disconnect.
 */
public final class BukkitListener implements Listener {

    private final RenderLoop renderLoop;

    public BukkitListener(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    /**
     * Publishes the player before native entity tracking begins.
     *
     * @param event the player joining the server
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        PlayerLookup.register(event.getPlayer());
    }

    @EventHandler
    public void onDisconnect(PlayerQuitEvent event) {
        PlayerLookup.unregister(event.getPlayer());
        this.renderLoop.post(new RenderTask.Cleanup(event.getPlayer()));
    }
}
