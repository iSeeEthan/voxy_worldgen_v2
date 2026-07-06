package com.ethan.voxyworldgenv2.platform;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

// bridges the chunkpos and chunk method shapes that differ between mc versions
public interface ChunkPosCompat {

    int x(ChunkPos pos);

    int z(ChunkPos pos);

    long packPos(ChunkPos pos);

    long asLong(int x, int z);

    ChunkPos unpack(long packed);

    int minSectionY(LevelChunk chunk);

    int minSectionY(ServerLevel level);

    // pin a chunk with a forced ticket
    void addForcedTicket(ServerChunkCache cache, ChunkPos pos);

    void removeForcedTicket(ServerChunkCache cache, ChunkPos pos);
}
