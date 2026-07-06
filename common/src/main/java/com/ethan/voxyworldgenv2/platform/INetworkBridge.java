package com.ethan.voxyworldgenv2.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import it.unimi.dsi.fastutil.ints.IntSet;

// networking calls the shared code makes, each loader implements it
public interface INetworkBridge {

    void sendHandshake(ServerPlayer player);

    void sendServerConfig(ServerPlayer player);

    void sendLODData(ServerPlayer player, LevelChunk chunk);

    void broadcastLODData(LevelChunk chunk);

    void broadcastLODData(LevelChunk chunk, IntSet onlySectionYs);

    double syncRadiusSq();

    void shutdown();
}
