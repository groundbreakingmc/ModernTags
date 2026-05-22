package com.github.groundbreakingmc.moderntags.listener;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.moderntags.core.RenderLoop;
import com.github.groundbreakingmc.moderntags.listener.handler.clientbound.*;
import com.github.groundbreakingmc.moderntags.listener.handler.serverbound.ActionHandler;
import com.github.groundbreakingmc.moderntags.listener.handler.serverbound.InputHandler;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.jetbrains.annotations.NotNull;

/**
 * Routes incoming and outgoing play packets to their dedicated handlers.
 */
public final class PacketListener extends PacketListenerAbstract {

    private final InputHandler inputHandler;
    private final ActionHandler actionHandler;

    private final SpawnEntityHandler spawnEntity;
    private final DestroyEntitiesHandler destroyEntities;
    private final DeathHandler death;
    private final PlayerInfoUpdateHandler playerInfoUpdate;
    private final PlayerInfoRemoveHandler playerInfoRemove;
    private final SetPassengersHandler setPassengers;
    private final SelfInvisibilityHandler selfInvisibility;
    private final EntityMetadataHandler entityMetadata;
    private final UpdateTeamHandler updateTeam;
    private final PlayerPositionHandler playerPosition;

    public PacketListener(@NotNull ModernTags plugin, @NotNull RenderLoop renderLoop) {
        this.inputHandler = new InputHandler(renderLoop);
        this.actionHandler = new ActionHandler(renderLoop);
        this.spawnEntity = new SpawnEntityHandler(plugin, renderLoop);
        this.destroyEntities = new DestroyEntitiesHandler(renderLoop);
        this.death = new DeathHandler(renderLoop);
        this.playerInfoUpdate = new PlayerInfoUpdateHandler(renderLoop);
        this.playerInfoRemove = new PlayerInfoRemoveHandler(renderLoop);
        this.setPassengers = new SetPassengersHandler(renderLoop);
        this.selfInvisibility = new SelfInvisibilityHandler(renderLoop);
        this.entityMetadata = new EntityMetadataHandler(renderLoop);
        this.updateTeam = new UpdateTeamHandler(renderLoop);
        this.playerPosition = new PlayerPositionHandler(renderLoop);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPlayer() == null) return;
        if (!(event.getPacketType() instanceof PacketType.Play.Client type)) return;

        switch (type) {
            case PLAYER_INPUT -> this.inputHandler.handle(event);
            case ENTITY_ACTION -> this.actionHandler.handle(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPlayer() == null) return;
        if (!(event.getPacketType() instanceof PacketType.Play.Server type)) return;

        switch (type) {
            case SPAWN_ENTITY -> this.spawnEntity.handle(event);
            case DESTROY_ENTITIES -> this.destroyEntities.handle(event);
            case ENTITY_STATUS -> this.death.handle(event);
            case PLAYER_INFO_UPDATE -> this.playerInfoUpdate.handle(event);
            case PLAYER_INFO_REMOVE -> this.playerInfoRemove.handle(event);
            case SET_PASSENGERS -> this.setPassengers.handle(event);
            case ENTITY_EFFECT, REMOVE_ENTITY_EFFECT -> this.selfInvisibility.handle(event);
            case ENTITY_METADATA -> this.entityMetadata.handle(event);
            case TEAMS -> this.updateTeam.handle(event);
            case PLAYER_POSITION_AND_LOOK -> this.playerPosition.handle(event);
        }
    }
}
