package com.ethan.voxyworldgenv2.platform;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class FabricChunkPosCompat implements ChunkPosCompat {

    @Override
    public int x(ChunkPos pos) {
        return pos.x();
    }

    @Override
    public int z(ChunkPos pos) {
        return pos.z();
    }

    @Override
    public long packPos(ChunkPos pos) {
        return pos.pack();
    }

    @Override
    public long asLong(int x, int z) {
        return ChunkPos.pack(x, z);
    }

    @Override
    public ChunkPos unpack(long packed) {
        return ChunkPos.unpack(packed);
    }

    @Override
    public int minSectionY(LevelChunk chunk) {
        return chunk.getMinSectionY();
    }

    @Override
    public int minSectionY(ServerLevel level) {
        return level.getMinSectionY();
    }

    @Override
    public void addForcedTicket(ServerChunkCache cache, ChunkPos pos) {
        cache.addTicketWithRadius(TicketType.FORCED, pos, 0);
    }

    @Override
    public void removeForcedTicket(ServerChunkCache cache, ChunkPos pos) {
        cache.removeTicketWithRadius(TicketType.FORCED, pos, 0);
    }
}
