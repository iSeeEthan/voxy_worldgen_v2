package com.ethan.voxyworldgenv2.generation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public record QueuedChunk(ServerLevel level, ChunkPos pos, double priority) implements Comparable<QueuedChunk> {
    @Override
    public int compareTo(QueuedChunk other) {
        return Double.compare(other.priority, this.priority);
    }
}
