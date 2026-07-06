package com.ethan.voxyworldgenv2.integration.tellus;

import com.ethan.voxyworldgenv2.platform.Services;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;

public final class TellusWorldFeatures {

    public static void placeVegetation(ChunkPos pos, TellusSampler.TellusChunkData data, Object mapper, Map<BlockPos, Long> blocks, int[] biomeIds, boolean[] vegAllowed) {
        Random random = new Random(Services.CHUNK_POS.packPos(pos) ^ 0x67726173);
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int[] heights = data.heights();
        
        int grassId = VoxyIngester.getBlockId(mapper, Blocks.SHORT_GRASS);
        int tallGrassLowerId = VoxyIngester.getBlockId(mapper, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        int tallGrassUpperId = VoxyIngester.getBlockId(mapper, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

        for (int i = 0; i < 256; i++) {
            if (!vegAllowed[i]) continue;
            
            int h = heights[i];
            int bId = biomeIds[i];

            if (random.nextFloat() < 0.20f) {
                int wx = minX + (i & 15);
                int wz = minZ + (i >> 4);
                if (random.nextFloat() < 0.15f) {
                    BlockPos p1 = new BlockPos(wx, h + 1, wz);
                    BlockPos p2 = new BlockPos(wx, h + 2, wz);
                    if (!blocks.containsKey(p1) && !blocks.containsKey(p2)) {
                        blocks.put(p1, VoxyIngester.composeId(tallGrassLowerId, bId, 15));
                        blocks.put(p2, VoxyIngester.composeId(tallGrassUpperId, bId, 15));
                    }
                } else {
                    BlockPos p1 = new BlockPos(wx, h + 1, wz);
                    if (!blocks.containsKey(p1)) {
                        blocks.put(p1, VoxyIngester.composeId(grassId, bId, 15));
                    }
                }
            }
        }
    }

    public static void placeUnderwaterVegetation(ChunkPos pos, TellusSampler.TellusChunkData data, Object mapper, Map<BlockPos, Long> blocks, int[] biomeIds, boolean[] hasWaters) {
        Random random = new Random(Services.CHUNK_POS.packPos(pos) ^ 0x73656167);
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int[] heights = data.heights();
        int[] waterHeads = data.waterSurfaces();
        
        int seagrassId = VoxyIngester.getBlockId(mapper, Blocks.SEAGRASS);
        int tallSeagrassLowerId = VoxyIngester.getBlockId(mapper, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        int tallSeagrassUpperId = VoxyIngester.getBlockId(mapper, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

        for (int i = 0; i < 256; i++) {
            if (!hasWaters[i]) continue;
            
            int h = heights[i];
            int wH = waterHeads[i];
            int bId = biomeIds[i];
            
            // seagrass needs space and doesn't always grow
            if (wH - h >= 2 && random.nextFloat() < 0.15f) {
                int wx = minX + (i & 15);
                int wz = minZ + (i >> 4);
                
                if (wH - h >= 3 && random.nextFloat() < 0.10f) {
                    blocks.put(new BlockPos(wx, h + 1, wz), VoxyIngester.composeId(tallSeagrassLowerId, bId, 15));
                    blocks.put(new BlockPos(wx, h + 2, wz), VoxyIngester.composeId(tallSeagrassUpperId, bId, 15));
                } else {
                    blocks.put(new BlockPos(wx, h + 1, wz), VoxyIngester.composeId(seagrassId, bId, 15));
                }
            }
        }
    }



    public static void placeProceduralTrees(ServerLevel level, ChunkPos pos, TellusSampler.TellusChunkData data, Object mapper, Map<BlockPos, Long> blocks, int[] biomeIds, BiomeSource biomeSource, Climate.Sampler sampler, int[] waterHeads) {
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        long seed_base = level.getSeed();

        int cellMinX = Math.floorDiv(minX - 8, 5); 
        int cellMaxX = Math.floorDiv(minX + 23, 5);
        int cellMinZ = Math.floorDiv(minZ - 8, 5);
        int cellMaxZ = Math.floorDiv(minZ + 23, 5);

        for (int cx = cellMinX; cx <= cellMaxX; cx++) {
            for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                long cellSeed = TellusIntegration.seedFromCoords(cx, 0, cz) ^ seed_base;
                Random random = new Random(cellSeed);
                int wx = cx * 5 + random.nextInt(5);
                int wz = cz * 5 + random.nextInt(5);

                int coverClass = TellusSampler.sampleCoverClass(level, wx, wz);
                if (coverClass == 10 || coverClass == 95) {
                     int surface = TellusSampler.sampleHeightOnly(level, wx, wz);
                     
                     int localWaterY = -64;
                     if (wx >= minX && wx < minX + 16 && wz >= minZ && wz < minZ + 16) {
                         localWaterY = waterHeads[(wz - minZ) << 4 | (wx - minX)];
                     } else {
                         localWaterY = data.seaLevel();
                     }

                     if (surface >= localWaterY) {
                         Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(surface), QuartPos.fromBlock(wz), sampler);
                         int bId = VoxyIngester.getBiomeId(mapper, biome);
                         buildProceduralTree(wx, surface + 1, wz, biome, mapper, blocks, random, bId, minX, minZ);
                     }
                }
            }
        }
    }

    private static void buildProceduralTree(int x, int y, int z, Holder<Biome> biome, Object mapper, Map<BlockPos, Long> blocks, Random random, int biomeId, int minX, int minZ) {
        boolean isSpruceBiome = biome.is(BiomeTags.IS_TAIGA) || 
                                 biome.is(Biomes.GROVE) || 
                                 biome.is(Biomes.SNOWY_SLOPES) || 
                                 biome.is(Biomes.FROZEN_PEAKS) ||
                                 biome.is(Biomes.WINDSWEPT_HILLS) ||
                                 biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS);

        if (isSpruceBiome) {
            buildRefinedSpruce(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else if (biome.is(BiomeTags.IS_JUNGLE)) {
            buildRefinedJungle(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
            buildRefinedBirch(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else if (biome.is(Biomes.DARK_FOREST)) {
            buildRefinedDarkOak(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else {
            buildRefinedOak(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        }
    }

    private static long getLeafId(Object mapper, BlockState state, int bId) {
        return VoxyIngester.composeId(VoxyIngester.getBlockId(mapper, state), bId, 15);
    }

    private static long getLogId(Object mapper, BlockState state, int bId) {
        return VoxyIngester.composeId(VoxyIngester.getBlockId(mapper, state), bId, 15);
    }

    private static void buildRefinedOak(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 5 + random.nextInt(2);
        long logId = getLogId(mapper, Blocks.OAK_LOG.defaultBlockState(), bId);
        long leafId = getLeafId(mapper, Blocks.OAK_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);
        
        int tipY = y + height - 1;
        fillEllipsoid(x, tipY, z, 2.8, 2.5, 2.8, leafId, blocks, minX, minZ, random, 0.1);
        setBlockIfInChunk(x, tipY, z, logId, blocks, minX, minZ); 
    }

    private static void buildRefinedBirch(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 6 + random.nextInt(4);
        long logId = getLogId(mapper, Blocks.BIRCH_LOG.defaultBlockState(), bId);
        long leafId = getLeafId(mapper, Blocks.BIRCH_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);
        
        fillEllipsoid(x, y + height - 2, z, 1.8, 3.5, 1.8, leafId, blocks, minX, minZ, random, 0.05);
    }

    private static void buildRefinedSpruce(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 10 + random.nextInt(6);
        long logId = getLogId(mapper, Blocks.SPRUCE_LOG.defaultBlockState(), bId);
        long leafId = getLeafId(mapper, Blocks.SPRUCE_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);
        
        int currentRadius = 1; 
        int maxRadius = 3;
        for (int oy = height - 1; oy >= 2; oy--) {
            double r = currentRadius + (random.nextDouble() * 0.7);
            for (int ox = (int)-Math.ceil(r); ox <= Math.ceil(r); ox++) {
                for (int oz = (int)-Math.ceil(r); oz <= Math.ceil(r); oz++) {
                    double d2 = ox*ox + oz*oz;
                    if (d2 <= r*r) {
                        setBlockIfInChunk(x + ox, y + oy, z + oz, leafId, blocks, minX, minZ);
                    }
                }
            }
            if (oy % 2 == 0) {
                if (currentRadius < maxRadius) currentRadius++;
                else currentRadius--; 
            }
        }
        setBlockIfInChunk(x, y + height, z, leafId, blocks, minX, minZ);
    }

    private static void buildRefinedDarkOak(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 4 + random.nextInt(2);
        long logId = getLogId(mapper, Blocks.DARK_OAK_LOG.defaultBlockState(), bId);
        long leafId = getLeafId(mapper, Blocks.DARK_OAK_LEAVES.defaultBlockState(), bId);

        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int i = 0; i < height; i++) setBlockIfInChunk(x+dx, y+i, z+dz, logId, blocks, minX, minZ);
            }
        }
        fillEllipsoid(x, y + height, z, 4.2, 2.2, 4.2, leafId, blocks, minX, minZ, random, 0.1);
    }

    private static void buildRefinedJungle(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 12 + random.nextInt(15);
        long logId = getLogId(mapper, Blocks.JUNGLE_LOG.defaultBlockState(), bId);
        long leafId = getLeafId(mapper, Blocks.JUNGLE_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);
        
        for (int b = 0; b < 4; b++) {
            int bh = (int)(height * 0.6) + random.nextInt((int)(height * 0.4));
            int box = random.nextInt(5) - 2;
            int boz = random.nextInt(5) - 2;
            fillEllipsoid(x + box, y + bh, z + boz, 3.5, 2.5, 3.5, leafId, blocks, minX, minZ, random, 0.2);
        }
        fillEllipsoid(x, y + height, z, 4.5, 3.5, 4.5, leafId, blocks, minX, minZ, random, 0.1);
    }

    private static void fillEllipsoid(int x, int y, int z, double rx, double ry, double rz, long id, Map<BlockPos, Long> blocks, int minX, int minZ, Random random, double noise) {
        int startX = (int)-Math.ceil(rx);
        int endX = (int)Math.ceil(rx);
        int startY = (int)-Math.ceil(ry);
        int endY = (int)Math.ceil(ry);
        int startZ = (int)-Math.ceil(rz);
        int endZ = (int)Math.ceil(rz);

        for (int ox = startX; ox <= endX; ox++) {
            for (int oy = startY; oy <= endY; oy++) {
                for (int oz = startZ; oz <= endZ; oz++) {
                    double dx = ox / rx;
                    double dy = oy / ry;
                    double dz = oz / rz;
                    double dist = dx*dx + dy*dy + dz*dz;
                    if (dist <= 1.0 + (random.nextDouble() * noise)) {
                        setBlockIfInChunk(x + ox, y + oy, z + oz, id, blocks, minX, minZ);
                    }
                }
            }
        }
    }

    private static void setBlockIfInChunk(int x, int y, int z, long id, Map<BlockPos, Long> blocks, int minX, int minZ) {
        if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16) {
            blocks.put(new BlockPos(x, y, z), id);
        }
    }


}
