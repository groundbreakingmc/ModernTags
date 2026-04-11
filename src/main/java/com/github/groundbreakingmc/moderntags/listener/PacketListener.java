package com.github.groundbreakingmc.moderntags.listener;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.listener.handler.*;
import com.github.groundbreakingmc.moderntags.manager.PlayerTagManager;
import com.github.groundbreakingmc.moderntags.text.PlaceholderParser;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * Main packet listener that delegates packet handling to specific handlers.
 * <p>
 * This class uses the Strategy pattern to separate concerns and make the code
 * more maintainable and testable. Each packet type has its own dedicated handler.
 */
public final class PacketListener extends PacketListenerAbstract {

    private final Map<PacketType.Play.Server, PacketHandler> handlers;

    public PacketListener(@NotNull ModernTags plugin,
                          @NotNull PlayerTagManager tagManager,
                          @NotNull PlaceholderParser placeholderParser) {
        this.handlers = this.initializeHandlers(plugin, tagManager, placeholderParser);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPlayer() == null) {
            return;
        }

        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) {
            return;
        }

        final PacketHandler handler = this.handlers.get(type);
        if (handler != null) {
            handler.handle(event);
        }
    }

    private Map<PacketType.Play.Server, PacketHandler> initializeHandlers(
            @NotNull ModernTags plugin,
            @NotNull PlayerTagManager tagManager,
            @NotNull PlaceholderParser placeholderParser) {

        final Map<PacketType.Play.Server, PacketHandler> handlers = new EnumMap<>(PacketType.Play.Server.class);

        handlers.put(PacketType.Play.Server.SPAWN_ENTITY,
                new SpawnEntityHandler(plugin, tagManager));

        handlers.put(PacketType.Play.Server.PLAYER_INFO_UPDATE,
                new PlayerInfoUpdateHandler(tagManager));

        handlers.put(PacketType.Play.Server.PLAYER_INFO_REMOVE,
                new PlayerInfoRemoveHandler(tagManager));

        handlers.put(PacketType.Play.Server.DESTROY_ENTITIES,
                new DestroyEntitiesHandler(tagManager));

        handlers.put(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK,
                new PlayerPositionHandler(tagManager));

        handlers.put(PacketType.Play.Server.SET_PASSENGERS,
                new SetPassengersHandler(tagManager));

        handlers.put(PacketType.Play.Server.ENTITY_STATUS,
                new DeathHandler(tagManager));

        handlers.put(PacketType.Play.Server.DISCONNECT,
                new DisconnectHandler(tagManager, placeholderParser));

        return handlers;
    }
}
