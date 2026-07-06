package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.event.ServerEventHandler;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@Mod(VoxyWorldGenV2.MOD_ID)
public class VoxyWorldGenV2NeoForge {

    public VoxyWorldGenV2NeoForge(IEventBus modEventBus) {
        VoxyWorldGenV2.LOGGER.info("voxy world gen v2 initializing (neoforge)");
        com.ethan.voxyworldgenv2.core.Config.load();

        modEventBus.addListener(NetworkHandler::registerPayloads);
        NeoForge.EVENT_BUS.register(GameEvents.class);
    }

    public static final class GameEvents {
        private GameEvents() {}

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            ServerEventHandler.onServerStarted(event.getServer());
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            ServerEventHandler.onServerStopping(event.getServer());
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            ServerEventHandler.onServerTick(event.getServer());
        }

        @SubscribeEvent
        public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                ServerEventHandler.onPlayerJoin(player);
            }
        }

        @SubscribeEvent
        public static void onPlayerDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                ServerEventHandler.onPlayerDisconnect(player);
            }
        }

        @SubscribeEvent
        public static void onChunkLoad(ChunkEvent.Load event) {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
                ServerEventHandler.onChunkLoad(level, chunk);
            }
        }

        @SubscribeEvent
        public static void onPermissionsChanged(net.neoforged.neoforge.event.entity.player.PermissionsChangedEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                player.server.execute(() -> ServerEventHandler.onPermissionsChanged(player));
            }
        }
    }
}
