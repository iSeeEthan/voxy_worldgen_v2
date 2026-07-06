package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class ChunkPersistence {
    
    public static void save(ServerLevel level, ResourceKey<Level> dimKey, Set<Long> completedChunks) {
        if (level == null || dimKey == null) return;
        
        try {
            String dimId = getDimensionId(dimKey);
            Path savePath = level.getServer().getWorldPath(LevelResource.ROOT).resolve("voxy_gen_" + dimId + ".bin");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(savePath)))) {
                synchronized(completedChunks) {
                    out.writeInt(completedChunks.size());
                    for (Long chunkPos : completedChunks) {
                        out.writeLong(chunkPos);
                    }
                }
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to save chunk generation cache", e);
        }
    }
    
    public static void load(ServerLevel level, ResourceKey<Level> dimKey, Set<Long> completedChunks) {
        completedChunks.clear();
        if (level == null || dimKey == null) return;
        
        try {
            String dimId = getDimensionId(dimKey);
            Path savePath = level.getServer().getWorldPath(LevelResource.ROOT).resolve("voxy_gen_" + dimId + ".bin");
            if (Files.exists(savePath)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(savePath)))) {
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) {
                        completedChunks.add(in.readLong());
                    }
                }
                VoxyWorldGenV2.LOGGER.info("loaded {} chunks from voxy generation cache for {}", completedChunks.size(), dimKey);
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to load chunk generation cache", e);
        }
    }

    private static String getDimensionId(ResourceKey<Level> dimKey) {
        return dimKey.toString()
                .replace("ResourceKey[", "")
                .replace("]", "")
                .replace("/", "_")
                .replace(":", "_")
                .trim();
    }
}
