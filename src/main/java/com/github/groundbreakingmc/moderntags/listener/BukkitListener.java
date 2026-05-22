package com.github.groundbreakingmc.moderntags.listener;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Posts a {@link RenderTask.Cleanup} when the player has disconnected a server.
 */
public final class BukkitListener implements Listener {

    private final RenderLoop renderLoop;

    public BukkitListener(RenderLoop renderLoop) {
        this.renderLoop = renderLoop;
    }

    @EventHandler
    public void onDisconnect(PlayerQuitEvent event) {
        this.renderLoop.post(new RenderTask.Cleanup(event.getPlayer()));
    }
}
