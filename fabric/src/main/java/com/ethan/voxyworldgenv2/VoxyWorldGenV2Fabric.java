package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.event.ServerEventHandler;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;


public class VoxyWorldGenV2Fabric implements ModInitializer {

    @Override
    public void onInitialize() {
        VoxyWorldGenV2.LOGGER.info("voxy world gen v2 initializing (fabric)");
        com.ethan.voxyworldgenv2.core.Config.load();
        NetworkHandler.init();

        ServerLifecycleEvents.SERVER_STARTED.register(ServerEventHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerEventHandler::onServerStopping);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerEventHandler.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ServerEventHandler.onPlayerDisconnect(handler.getPlayer()));

        ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick);

        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) ->
                ServerEventHandler.onChunkLoad(level, chunk));

    }
}
