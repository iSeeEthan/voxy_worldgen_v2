package com.ethan.voxyworldgenv2.integration.tellus;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
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
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TellusIntegration {
    private static boolean initialized = false;
    private static ExecutorService workerPool;
    private static final AtomicInteger threadCounter = new AtomicInteger();
    private static final Set<ChunkPos> buildingChunks = ConcurrentHashMap.newKeySet();

    private TellusIntegration() {}

    public static void shutdown() {
        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                workerPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            workerPool = null;
        }
        buildingChunks.clear();
        initialized = false;
        VoxyWorldGenV2.LOGGER.info("tellus integration shutdown");
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        TellusSampler.initialize();
        VoxyIngester.initialize();

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        workerPool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10000),
                r -> {
                    Thread t = new Thread(r, "tellus-voxy-worker-" + threadCounter.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        VoxyWorldGenV2.LOGGER.info("tellus integration hub initialized ({} workers)", threadCount);
    }

    public static boolean isTellusWorld(ServerLevel level) {
        if (!initialized) initialize();
        if (!TellusSampler.isTellusPresent()) return false;
        Object generator = level.getChunkSource().getGenerator();
        return generator != null && generator.getClass().getName().contains("EarthChunkGenerator");
    }

    public static void enqueueGenerate(ServerLevel level, ChunkPos pos, Runnable onComplete) {
        if (workerPool == null || !buildingChunks.add(pos)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        workerPool.execute(() -> {
            try {
                TellusSampler.prefetch(level, pos);
                TellusSampler.TellusChunkData data = TellusSampler.sample(level, pos);
                if (data != null) {
                    buildAndIngest(level, pos, data);
                }
            } finally {
                buildingChunks.remove(pos);
                if (onComplete != null) onComplete.run();
            }
        });
    }

    private record Palette(BlockState top, BlockState filler) {}

    private static Palette getPalette(Holder<Biome> biome, Random random) {
        if (biome != null) {
            if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
                int roll = random.nextInt(100);
                if (roll < 10) return new Palette(Blocks.GRAVEL.defaultBlockState(), Blocks.GRAVEL.defaultBlockState());
                if (roll < 15) return new Palette(Blocks.CLAY.defaultBlockState(), Blocks.CLAY.defaultBlockState());
                return new Palette(Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState());
            }
            if (biome.is(Biomes.DESERT)) return new Palette(Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState());
            if (biome.is(BiomeTags.IS_BEACH)) return new Palette(Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState());
            if (biome.is(BiomeTags.IS_BADLANDS)) return new Palette(Blocks.RED_SAND.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState());
            if (biome.is(Biomes.MANGROVE_SWAMP)) return new Palette(Blocks.MUD.defaultBlockState(), Blocks.MUD.defaultBlockState());
            if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA)) return new Palette(Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        }
        return new Palette(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
    }

    public static long seedFromCoords(int x, int y, int z) {
        long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    private static void buildAndIngest(ServerLevel level, ChunkPos pos, TellusSampler.TellusChunkData data) {
        if (!VoxyIngester.isAvailable()) return;
        try {
            Object voxy = VoxyIngester.getVoxyInstance();
            if (voxy == null) return;
            Object engine = VoxyIngester.getWorldEngine(voxy, level);
            if (engine == null) return;
            Object mapper = VoxyIngester.getMapper(engine);
            if (mapper == null) return;

            BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
            Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
            
            int minX = pos.getMinBlockX();
            int minZ = pos.getMinBlockZ();
            
            Map<Integer, Holder<Biome>> biomeIdToHolder = new HashMap<>();
            Map<Holder<Biome>, Integer> biomeHolderToId = new IdentityHashMap<>();
            Random random = new Random(Services.CHUNK_POS.packPos(pos));

            int grassId_Voxy = VoxyIngester.getBlockId(mapper, Blocks.GRASS_BLOCK);
            int sandId_Voxy = VoxyIngester.getBlockId(mapper, Blocks.SAND);

            int seaLevel = data.seaLevel();
            int[] heights = data.heights();
            byte[] cover = data.coverClasses();
            byte[] slopes = data.slopes();
            boolean[] hasWaters = data.hasWater();
            int[] waterHeads = data.waterSurfaces();

            int[] biomeIds = new int[256];
            boolean[] vegAllowed = new boolean[256];
            long[] colTopIds = new long[256];
            long[] colFillerIds = new long[256];
            long[] colStoneIds = new long[256];
            long[] colDeepIds = new long[256];
            long[] colWaterIds = new long[256];

            int stoneId = VoxyIngester.getBlockId(mapper, Blocks.STONE.defaultBlockState());
            int deepId = VoxyIngester.getBlockId(mapper, Blocks.DEEPSLATE.defaultBlockState());
            int waterId = VoxyIngester.getBlockId(mapper, Blocks.WATER.defaultBlockState());
            if (waterId == 0) waterId = VoxyIngester.getBlockId(mapper, Blocks.ICE.defaultBlockState());
            if (waterId == 0) waterId = stoneId;

            // wider water grid so the shoreline check sees past the 16x16 area
            boolean[] expandedWater = new boolean[24 * 24];
            for (int dz = -4; dz < 20; dz++) {
                for (int dx = -4; dx < 20; dx++) {
                    int idx = (dz + 4) * 24 + (dx + 4);
                    if (dx >= 0 && dx < 16 && dz >= 0 && dz < 16) {
                        expandedWater[idx] = hasWaters[dz << 4 | dx];
                    } else {
                        // sample neighbor cover to get water status outside the local 16x16
                        int c = TellusSampler.sampleCoverClass(level, minX + dx, minZ + dz);
                        expandedWater[idx] = (c == 80 || c == 0); // 80 is water, 0 is no-data/ocean
                    }
                }
            }

            // pass 1, resolve biomes and terrain/water heights
            for (int i = 0; i < 256; i++) {
                int wx = minX + (i & 15);
                int wz = minZ + (i >> 4);
                int h = heights[i];
                int c = cover[i] & 0xFF;
                
                boolean isOcean = (c == 0 && h <= seaLevel);
                boolean hasWaterValue = hasWaters[i] || (c == 80 || c == 95 || isOcean);
                
                if (hasWaterValue) {
                    int waterH = hasWaters[i] ? waterHeads[i] : seaLevel;
                    if (waterH <= h) waterH = h + 1;
                    waterHeads[i] = waterH;
                    
                    if (!isOcean) {
                        // distance to shore so water can slope shallow near land
                        int distToShore = 5;
                        int ix = i & 15;
                        int iz = i >> 4;
                        for (int d = 1; d < 5; d++) {
                            boolean foundLand = false;
                            for (int dz = -d; dz <= d; dz++) {
                                for (int dx = -d; dx <= d; dx++) {
                                    if (Math.abs(dx) < d && Math.abs(dz) < d) continue;
                                    if (!expandedWater[(iz + dz + 4) * 24 + (ix + dx + 4)]) {
                                        foundLand = true; break;
                                    }
                                }
                                if (foundLand) break;
                            }
                            if (foundLand) { distToShore = d; break; }
                        }

                        // make water shallow near land
                        int targetH = waterH - distToShore;
                        if (h > targetH) {
                            h = targetH;
                            heights[i] = h;
                        }
                    } else if (h > seaLevel - 8) {
                         h = seaLevel - 8;
                         heights[i] = h;
                    }
                    hasWaters[i] = true;
                }

                Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(h), QuartPos.fromBlock(wz), sampler);
                int bId = biomeHolderToId.computeIfAbsent(biome, b -> {
                    int id = VoxyIngester.getBiomeId(mapper, b);
                    biomeIdToHolder.put(id, b);
                    return id;
                });
                biomeIds[i] = bId;
            }

            // pass 2, apply surface rules
            for (int i = 0; i < 256; i++) {
                int h = heights[i];
                int c = cover[i] & 0xFF;
                int slope = slopes[i] & 0xFF;
                int bId = biomeIds[i];
                Holder<Biome> biome = biomeIdToHolder.get(bId);
                
                Palette p = getPalette(biome, random);

                colStoneIds[i] = VoxyIngester.composeId(stoneId, bId, 15);
                colDeepIds[i] = VoxyIngester.composeId(deepId, bId, 15);
                colWaterIds[i] = VoxyIngester.composeId(waterId, bId, 15);

                boolean isStony = slope >= 3 && h >= 0;
                boolean isSnowIce = (c == 70); // ESA_SNOW_ICE

                if (isStony) {
                    colTopIds[i] = colStoneIds[i];
                    colFillerIds[i] = colStoneIds[i];
                    vegAllowed[i] = false;
                } else if (isSnowIce) {
                    colTopIds[i] = VoxyIngester.composeId(VoxyIngester.getBlockId(mapper, Blocks.SNOW_BLOCK), bId, 15);
                    colFillerIds[i] = colDeepIds[i];
                    vegAllowed[i] = false;
                } else {
                    int topBlockId = VoxyIngester.getBlockId(mapper, p.top);
                    colTopIds[i] = VoxyIngester.composeId(topBlockId, bId, 15);
                    colFillerIds[i] = VoxyIngester.composeId(VoxyIngester.getBlockId(mapper, p.filler), bId, 15);
                    vegAllowed[i] = (topBlockId == grassId_Voxy) && 
                                    (biome == null || (!biome.is(BiomeTags.IS_BADLANDS) && !biome.is(Biomes.DESERT))) && 
                                    !hasWaters[i];
                }
            }

            Map<BlockPos, Long> propBlocks = new HashMap<>();
            TellusWorldFeatures.placeProceduralTrees(level, pos, data, mapper, propBlocks, biomeIds, biomeSource, sampler, waterHeads);
            TellusWorldFeatures.placeVegetation(pos, data, mapper, propBlocks, biomeIds, vegAllowed);
            TellusWorldFeatures.placeUnderwaterVegetation(pos, data, mapper, propBlocks, biomeIds, hasWaters);

            long brightAir = VoxyIngester.composeId(0, 0, 15);
            int minSY = Services.CHUNK_POS.minSectionY(level);
            int sCount = level.getSectionsCount();

            int maxHV = -64;
            for (int h : heights) if (h > maxHV) maxHV = h;
            for (int i = 0; i < 256; i++) if (hasWaters[i] && waterHeads[i] > maxHV) maxHV = waterHeads[i];
            for (BlockPos p : propBlocks.keySet()) if (p.getY() > maxHV) maxHV = p.getY();

            for (int sy = 0; sy < sCount; sy++) {
                int sY = minSY + sy;
                int bY = sY << 4;
                if (bY > maxHV + 16) continue;

                Object vs = VoxyIngester.createSection(Services.CHUNK_POS.x(pos), sY, Services.CHUNK_POS.z(pos));
                long[] dataArray = VoxyIngester.getSectionData(vs);
                if (dataArray == null) continue;
                Arrays.fill(dataArray, brightAir);

                int nAir = 0;
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int i = 0; i < 4096; i++) {
                    int ly = (i >> 8) & 15;
                    int lz = (i >> 4) & 15;
                    int lx = i & 15;
                    int col = lz << 4 | lx;
                    
                    int wX = minX + lx;
                    int wY = bY + ly;
                    int wZ = minZ + lz;
                    
                    int h = heights[col];
                    boolean isTerrain = wY <= h;
                    boolean isWater = !isTerrain && hasWaters[col] && wY <= waterHeads[col];

                    if (isTerrain) {
                        dataArray[i] = (wY == h) ? colTopIds[col] : (wY > h - 4) ? colFillerIds[col] : (wY < 0) ? colDeepIds[col] : colStoneIds[col];
                        nAir++;
                    } else if (isWater) {
                        dataArray[i] = colWaterIds[col];
                        nAir++;
                    } else {
                        cursor.set(wX, wY, wZ);
                        Long propId = propBlocks.get(cursor);
                        if (propId != null) {
                            dataArray[i] = propId;
                            if (propId != brightAir) nAir++;
                        }
                    }
                }

                if (nAir > 0) {
                    VoxyIngester.setNonAirCount(vs, nAir);
                    VoxyIngester.mipAndInsert(engine, mapper, vs);
                }
            }
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("tellus-voxy build failed for {}", pos, e);
        }
    }
}
