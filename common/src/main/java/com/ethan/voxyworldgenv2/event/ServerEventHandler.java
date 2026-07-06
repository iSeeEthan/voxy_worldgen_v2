package com.ethan.voxyworldgenv2.event;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ServerEventHandler {
    private ServerEventHandler() {}

    public static void onServerStarted(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server started, initializing manager");
        ChunkGenerationManager.getInstance().initialize(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server stopping, shutting down manager");
        ChunkGenerationManager.getInstance().shutdown();
        Services.NETWORK.shutdown();
        PlayerTracker.getInstance().clear();
    }

    public static void onPlayerJoin(ServerPlayer player) {
        PlayerTracker.getInstance().addPlayer(player);
        Services.NETWORK.sendHandshake(player);
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        PlayerTracker.getInstance().removePlayer(player);
    }

    // op changed so re-send config to update the client edit lock
    public static void onPermissionsChanged(ServerPlayer player) {
        Services.NETWORK.sendServerConfig(player);
    }

    public static void onServerTick(MinecraftServer server) {
        ChunkGenerationManager.getInstance().tick();
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        // fires for every vanilla chunk load, so skip the loop when nobody can receive
        if (!PlayerTracker.getInstance().anyModded()) return;

        // offer it to each player, skip non-modded and ones who already have it
        long packed = Services.CHUNK_POS.packPos(chunk.getPos());
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (!PlayerTracker.getInstance().isModded(player.getUUID())) continue;
            if (PlayerTracker.getInstance().isSynced(player.getUUID(), packed)) continue;
            Services.NETWORK.sendLODData(player, chunk);
        }
    }
}
