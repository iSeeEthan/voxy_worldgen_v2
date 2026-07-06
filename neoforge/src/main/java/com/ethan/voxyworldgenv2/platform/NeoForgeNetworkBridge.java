package com.ethan.voxyworldgenv2.platform;

import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import it.unimi.dsi.fastutil.ints.IntSet;

public final class NeoForgeNetworkBridge implements INetworkBridge {

    @Override
    public void sendHandshake(ServerPlayer player) {
        NetworkHandler.sendHandshake(player);
    }

    @Override
    public void sendServerConfig(ServerPlayer player) {
        NetworkHandler.sendServerConfig(player);
    }

    @Override
    public void sendLODData(ServerPlayer player, LevelChunk chunk) {
        NetworkHandler.sendLODData(player, chunk);
    }

    @Override
    public void broadcastLODData(LevelChunk chunk) {
        NetworkHandler.broadcastLODData(chunk);
    }

    @Override
    public void broadcastLODData(LevelChunk chunk, IntSet onlySectionYs) {
        NetworkHandler.broadcastLODData(chunk, onlySectionYs);
    }

    @Override
    public double syncRadiusSq() {
        return NetworkHandler.syncRadiusSq();
    }

    @Override
    public void shutdown() {
        NetworkHandler.shutdown();
    }
}
