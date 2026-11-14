package com.github.groundbreakingmc.moderntags.listener;

import com.github.groundbreakingmc.moderntags.render.TagManager;
import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.listener.handler.serverbound.InputHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.serverbound.ActionHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.PlayerPositionHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.DeathHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.EntityMetadataHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.DestroyEntitiesHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.SpawnEntityHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.SetPassengersHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.SelfInvisibilityHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.PlayerInfoUpdateHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.UpdateTeamHandler;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import io.netty.channel.Channel;
import org.jetbrains.annotations.NotNull;

public final class PacketListener extends PacketListenerAbstract {

    private final ModernTags plugin;
    private final InputHandler input;
    private final ActionHandler action;

    private final SpawnEntityHandler spawn;
    private final DestroyEntitiesHandler destroy;
    private final PlayerPositionHandler position;
    private final DeathHandler death;
    private final EntityMetadataHandler metadata;
    private final SelfInvisibilityHandler selfInvisibility;
    private final SetPassengersHandler passengers;
    private final PlayerInfoUpdateHandler playerInfoUpdate;
    private final UpdateTeamHandler team;

    /**
     * Creates the packet router after other listeners have processed the packets.
     */
    public PacketListener(@NotNull ModernTags plugin, @NotNull TagManager tags) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
        this.input = new InputHandler(tags);
        this.action = new ActionHandler(tags);
        this.spawn = new SpawnEntityHandler(tags);
        this.destroy = new DestroyEntitiesHandler(tags);
        this.position = new PlayerPositionHandler(tags);
        this.death = new DeathHandler(tags);
        this.metadata = new EntityMetadataHandler(tags);
        this.selfInvisibility = new SelfInvisibilityHandler(tags);
        this.passengers = new SetPassengersHandler(tags);
        this.playerInfoUpdate = new PlayerInfoUpdateHandler(tags);
        this.team = new UpdateTeamHandler(tags);
    }

    /**
     * Dispatches non-cancelled player input directly to its handlers.
     */
    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        if (event.isCancelled() || !(event.getPacketType() instanceof PacketType.Play.Client type)) return;
        switch (type) {
            case PLAYER_INPUT -> this.input.handle(event, this.plugin.configValues());
            case ENTITY_ACTION -> this.action.handle(event, this.plugin.configValues());
            default -> {
                return;
            }
        }
        // No outgoing packet follows to carry what the handlers wrote.
        ((Channel) event.getChannel()).flush();
    }

    /**
     * Dispatches relevant, non-cancelled play packets. Configuration-dependent handlers
     * receive one snapshot for the entire packet, including all profile entries.
     */
    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (event.isCancelled() || !(event.getPacketType() instanceof PacketType.Play.Server type)) return;
        switch (type) {
            case SPAWN_ENTITY, SPAWN_PLAYER -> this.spawn.handle(event, this.plugin.configValues());
            case DESTROY_ENTITIES -> this.destroy.handle(event);
            case PLAYER_POSITION_AND_LOOK -> this.position.handle(event, this.plugin.configValues());
            case ENTITY_STATUS -> this.death.handle(event);
            case ENTITY_METADATA -> this.metadata.handle(event, this.plugin.configValues());
            case ENTITY_EFFECT, REMOVE_ENTITY_EFFECT -> this.selfInvisibility.handle(event, this.plugin.configValues());
            case SET_PASSENGERS -> this.passengers.handle(event, this.plugin.configValues());
            case PLAYER_INFO_UPDATE -> this.playerInfoUpdate.handle(event, this.plugin.configValues());
            case TEAMS -> this.team.handle(event);
        }
    }
}
