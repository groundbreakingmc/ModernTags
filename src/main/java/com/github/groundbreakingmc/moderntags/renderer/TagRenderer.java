package com.github.groundbreakingmc.moderntags.renderer;

import com.github.groundbreakingmc.moderntags.core.ViewerState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Sealed renderer interface implemented by {@link ModernRenderer} and {@link LegacyRenderer}.
 *
 * <p>All methods are called exclusively from {@link com.github.groundbreakingmc.moderntags.core.RenderLoop}
 * during drain — no synchronisation is required inside implementations.
 *
 * <p>Methods receive {@link ViewerState} instead of two {@code Player} parameters, giving
 * renderers direct access to cached state without additional map lookups.
 */
public sealed interface TagRenderer permits ModernRenderer, LegacyRenderer {

    /**
     * Spawn or show the tag for {@code state.viewer}. Called on transition from not-rendered to rendered.
     */
    void render(@NotNull ViewerState state);

    /**
     * Destroy or remove the tag for {@code state.viewer}. Called on transition from rendered to not-rendered.
     */
    void stopRendering(@NotNull ViewerState state);

    /**
     * Show the tag after a temporary hide. Defaults to {@link #render}.
     */
    default void show(@NotNull ViewerState state) {
        this.render(state);
    }

    /**
     * Hide the tag temporarily without full teardown. Defaults to {@link #stopRendering}.
     */
    default void hide(@NotNull ViewerState state) {
        this.stopRendering(state);
    }

    /**
     * Advance animation frame and push updated packets to all current viewers.
     * {@code state} is a representative state for {@code state.target}; the renderer broadcasts internally.
     */
    void updateFrame(@NotNull ViewerState state);

    /**
     * Re-resolve placeholders / name color and push updated packets to all viewers.
     */
    void updatePlaceholders(@NotNull ViewerState state);

    /**
     * Release all per-player state for {@code player} (as target or viewer). Called on disconnect or reload.
     */
    void cleanup(@NotNull Player player);

    int frameUpdateRate();

    int placeholdersUpdateRate();
}
