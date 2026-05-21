package com.github.groundbreakingmc.moderntags.update;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Periodic tick source. Posts {@link RenderTask.Tick} for each online player
 * at a fixed interval. RenderLoop handles frame/placeholder update scheduling
 * internally based on per-renderer rates.
 *
 * <p>The tick counter is local — no dependency on the Bukkit server tick counter,
 * which avoids cross-thread access to server internals.
 */
public final class UpdateManager {

    private final ModernTags plugin;
    private final RenderLoop renderLoop;
    private @Nullable ScheduledTask task;
    private int currentTick = 0;

    public UpdateManager(@NotNull ModernTags plugin, @NotNull RenderLoop renderLoop) {
        this.plugin = plugin;
        this.renderLoop = renderLoop;
    }

    public void start(int tickRate) {
        if (this.task != null) return;
        this.task = Bukkit.getAsyncScheduler().runAtFixedRate(
                this.plugin,
                scheduledTask -> this.tick(),
                tickRate,
                tickRate,
                TimeUnit.MILLISECONDS
        );
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        final int tick = this.currentTick++;
        // Post a single Tick task — RenderLoop iterates all states internally.
        this.renderLoop.post(new RenderTask.Tick(tick));
    }
}
