package com.github.groundbreakingmc.moderntags.listener;

import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.core.RenderTask;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Listens to LuckPerms node mutation events and re-renders (or stops rendering)
 * the self-tag for players affected by changes to {@value #PERMISSION}.
 *
 * <h2>Cases handled</h2>
 * <ol>
 *   <li><b>Direct user node</b> — {@code NodeAddEvent / NodeRemoveEvent / NodeClearEvent}
 *       on a {@link User}: the affected player is re-evaluated immediately.</li>
 *   <li><b>Group permission node</b> — {@code NodeAddEvent / NodeRemoveEvent / NodeClearEvent}
 *       on a {@link Group}: every online player that inherits the group is re-evaluated.</li>
 *   <li><b>User inherits a group</b> — {@code NodeAddEvent} of an {@link net.luckperms.api.node.types.InheritanceNode}
 *       on a {@link User}: the player is re-evaluated because they may now indirectly hold
 *       the permission via the newly inherited group.</li>
 *   <li><b>User loses a group</b> — {@code NodeRemoveEvent / NodeClearEvent} of an
 *       {@link net.luckperms.api.node.types.InheritanceNode} on a {@link User}: symmetric to the above.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * LuckPerms fires mutation events on arbitrary threads. All work here is either
 * a Bukkit async scheduler lookup or a lock-free {@link RenderLoop#post} — both safe.
 */
public final class LuckPermsListener {

    static final String PERMISSION = "moderntags.see.own";

    private final JavaPlugin plugin;
    private final RenderLoop renderLoop;
    private final LuckPerms luckPerms;

    private final List<EventSubscription<?>> subscriptions = new ArrayList<>();

    public LuckPermsListener(JavaPlugin plugin, RenderLoop renderLoop, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.renderLoop = renderLoop;
        this.luckPerms = luckPerms;
    }

    public void register() {
        final var eventBus = this.luckPerms.getEventBus();

        this.subscriptions.add(eventBus.subscribe(this.plugin, NodeAddEvent.class, this::onNodeAdd));
        this.subscriptions.add(eventBus.subscribe(this.plugin, NodeRemoveEvent.class, this::onNodeRemove));
        this.subscriptions.add(eventBus.subscribe(this.plugin, NodeClearEvent.class, this::onNodeClear));
    }

    public void unregister() {
        this.subscriptions.forEach(EventSubscription::close);
        this.subscriptions.clear();
    }

    /**
     * A node was <b>added</b> to a user or group.
     *
     * <ul>
     *   <li>Direct permission on user → re-render with the new effective value.</li>
     *   <li>InheritanceNode on user   → player now inherits a group; re-evaluate.</li>
     *   <li>Permission on group       → find all online members and re-evaluate.</li>
     * </ul>
     */
    private void onNodeAdd(NodeAddEvent event) {
        final var node = event.getNode();

        if (event.getTarget() instanceof User user) {
            if (isRelevantPermission(node) || NodeType.INHERITANCE.matches(node)) {
                this.reevaluateUser(Bukkit.getPlayer(user.getUniqueId()), node.getValue());
            }
            return;
        }

        if (event.getTarget() instanceof Group group) {
            if (isRelevantPermission(node)) {
                this.reevaluateGroupMembers(group.getName());
            }
        }
    }

    /**
     * A node was <b>removed</b> from a user or group.
     * Mirrors {@link #onNodeAdd} — removal may revoke the effective permission.
     */
    private void onNodeRemove(NodeRemoveEvent event) {
        final var node = event.getNode();

        if (event.getTarget() instanceof User user) {
            if (isRelevantPermission(node) || NodeType.INHERITANCE.matches(node)) {
                // Another group may still grant the permission after this node was removed.
                this.reevaluateUser(Bukkit.getPlayer(user.getUniqueId()), null);
            }
            return;
        }

        if (event.getTarget() instanceof Group group) {
            if (isRelevantPermission(node)) {
                this.reevaluateGroupMembers(group.getName());
            }
        }
    }

    /**
     * <b>All nodes were cleared</b> from a user or group.
     * After a clear the effective permission is almost certainly lost.
     */
    private void onNodeClear(NodeClearEvent event) {
        if (event.getTarget() instanceof User user) {
            this.reevaluateUser(Bukkit.getPlayer(user.getUniqueId()), null);
            return;
        }

        if (event.getTarget() instanceof Group group) {
            // The event has no pre-clear snapshot, so refresh every online member.
            this.reevaluateGroupMembers(group.getName());
        }
    }

    /**
     * Re-evaluates a single player's self-tag visibility.
     *
     * @param player  the online player
     * @param granted {@code true}  → the node was directly added with value=true
     *                (skip the LP lookup for speed)<br>
     *                {@code false} → the node was directly added with value=false
     *                (treat as revoke without LP lookup)<br>
     *                {@code null}  → derive the effective value via LP (removal / clear path)
     */
    private void reevaluateUser(Player player, Boolean granted) {
        if (player == null) return;

        // Removal and clear paths need the remaining effective permission, not the old node value.
        final boolean hasPermission = (granted != null)
                ? granted
                : player.hasPermission(PERMISSION);

        if (hasPermission) {
            this.renderLoop.post(new RenderTask.Render(player, player));
        } else {
            this.renderLoop.post(new RenderTask.StopRendering(player, player));
        }
    }

    /**
     * Re-evaluates every online player that is a direct or inherited member of
     * {@code groupName}.  Uses LuckPerms' user manager to perform a lightweight
     * group membership check without loading offline users.
     */
    private void reevaluateGroupMembers(String groupName) {
        // Online users are already loaded; avoid loading offline users from storage.
        final var loadedUsers = this.luckPerms.getUserManager().getLoadedUsers();

        for (final var user : loadedUsers) {
            final var player = Bukkit.getPlayer(user.getUniqueId());
            if (player == null) continue;
            final boolean inGroup = user.getInheritedGroups(user.getQueryOptions()).stream()
                    .anyMatch(group -> group.getName().equals(groupName));

            if (!inGroup) continue;

            this.reevaluateUser(player, null);
        }
    }

    private static boolean isRelevantPermission(Node node) {
        return node instanceof PermissionNode permission
                && permission.getPermission().equals(PERMISSION);
    }
}
