package com.ethan.voxyworldgenv2.platform;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;


public final class NeoForgeChunkPosCompat implements ChunkPosCompat {

    @Override
    public int x(ChunkPos pos) {
        return pos.x;
    }

    @Override
    public int z(ChunkPos pos) {
        return pos.z;
    }

    @Override
    public long packPos(ChunkPos pos) {
        return pos.toLong();
    }

    @Override
    public long asLong(int x, int z) {
        return ChunkPos.asLong(x, z);
    }

    @Override
    public ChunkPos unpack(long packed) {
        return new ChunkPos(packed);
    }

    @Override
    public int minSectionY(LevelChunk chunk) {
        return chunk.getMinSection();
    }

    @Override
    public int minSectionY(ServerLevel level) {
        return level.getMinSection();
    }

    @Override
    public void addForcedTicket(ServerChunkCache cache, ChunkPos pos) {
        // forced tickets carry the chunkpos as their value here
        cache.addRegionTicket(TicketType.FORCED, pos, 0, pos);
    }

    @Override
    public void removeForcedTicket(ServerChunkCache cache, ChunkPos pos) {
        cache.removeRegionTicket(TicketType.FORCED, pos, 0, pos);
    }
}
