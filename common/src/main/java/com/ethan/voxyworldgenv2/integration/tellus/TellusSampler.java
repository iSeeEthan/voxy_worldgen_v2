package com.ethan.voxyworldgenv2.integration.tellus;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.platform.Services;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// reflective access to tellus
public final class TellusSampler {
    private static boolean initialized = false;
    private static boolean tellusPresent = false;

    private static Class<?> earthChunkGeneratorClass;
    private static Class<?> tellusElevationSourceClass;
    private static Class<?> tellusLandCoverSourceClass;
    private static Class<?> tellusLandMaskSourceClass;
    private static Class<?> landMaskSampleClass;
    private static Class<?> earthGeneratorSettingsClass;
    private static Class<?> waterSurfaceResolverClass;
    private static Class<?> waterChunkDataClass;

    private static MethodHandle sampleElevationMetersMethod;
    private static MethodHandle sampleCoverClassMethod;
    private static MethodHandle sampleLandMaskMethod;
    private static MethodHandle landMaskSampleKnownMethod;
    private static MethodHandle landMaskSampleLandMethod;
    private static MethodHandle getSettingsMethod;
    private static MethodHandle worldScaleHandle;
    private static MethodHandle terrestrialHeightScaleHandle;
    private static MethodHandle oceanicHeightScaleHandle;
    private static MethodHandle heightOffsetHandle;
    private static MethodHandle resolveSeaLevelHandle;
    private static MethodHandle resolveChunkWaterDataMethod;
    private static MethodHandle waterChunkHasWaterMethod;
    private static MethodHandle waterChunkWaterSurfaceMethod;
    private static MethodHandle waterChunkTerrainSurfaceMethod;

    private static Field elevationSourceField;
    private static Field landCoverSourceField;
    private static Field landMaskSourceField;
    private static Field waterResolverField;

    private static final int ESA_NO_DATA = 0;
    private static final int ESA_WATER = 80;

    private TellusSampler() {}

    private static MethodHandle treeFeaturesForBiomeMethod;

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            earthChunkGeneratorClass = Class.forName("com.yucareux.tellus.worldgen.EarthChunkGenerator");
            tellusElevationSourceClass = Class.forName("com.yucareux.tellus.world.data.elevation.TellusElevationSource");
            tellusLandCoverSourceClass = Class.forName("com.yucareux.tellus.world.data.cover.TellusLandCoverSource");
            tellusLandMaskSourceClass = Class.forName("com.yucareux.tellus.world.data.mask.TellusLandMaskSource");
            landMaskSampleClass = Class.forName("com.yucareux.tellus.world.data.mask.TellusLandMaskSource$LandMaskSample");
            earthGeneratorSettingsClass = Class.forName("com.yucareux.tellus.worldgen.EarthGeneratorSettings");
            waterSurfaceResolverClass = Class.forName("com.yucareux.tellus.worldgen.WaterSurfaceResolver");
            waterChunkDataClass = Class.forName("com.yucareux.tellus.worldgen.WaterSurfaceResolver$WaterChunkData");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            elevationSourceField = earthChunkGeneratorClass.getDeclaredField("ELEVATION_SOURCE");
            elevationSourceField.setAccessible(true);
            landCoverSourceField = earthChunkGeneratorClass.getDeclaredField("LAND_COVER_SOURCE");
            landCoverSourceField.setAccessible(true);
            landMaskSourceField = earthChunkGeneratorClass.getDeclaredField("LAND_MASK_SOURCE");
            landMaskSourceField.setAccessible(true);
            waterResolverField = earthChunkGeneratorClass.getDeclaredField("waterResolver");
            waterResolverField.setAccessible(true);

            sampleElevationMetersMethod = lookup.unreflect(tellusElevationSourceClass.getMethod("sampleElevationMeters", double.class, double.class, double.class, boolean.class));
            sampleCoverClassMethod = lookup.unreflect(tellusLandCoverSourceClass.getMethod("sampleCoverClass", double.class, double.class, double.class));
            sampleLandMaskMethod = lookup.unreflect(tellusLandMaskSourceClass.getMethod("sampleLandMask", double.class, double.class, double.class));
            
            landMaskSampleKnownMethod = lookup.unreflect(landMaskSampleClass.getMethod("known"));
            landMaskSampleLandMethod = lookup.unreflect(landMaskSampleClass.getMethod("land"));
            
            getSettingsMethod = lookup.unreflect(earthChunkGeneratorClass.getMethod("settings"));

            worldScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("worldScale"));
            terrestrialHeightScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("terrestrialHeightScale"));
            oceanicHeightScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("oceanicHeightScale"));
            heightOffsetHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("heightOffset"));
            resolveSeaLevelHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("resolveSeaLevel"));

            resolveChunkWaterDataMethod = lookup.unreflect(waterSurfaceResolverClass.getMethod("resolveChunkWaterData", int.class, int.class));
            waterChunkHasWaterMethod = lookup.unreflect(waterChunkDataClass.getMethod("hasWater", int.class, int.class));
            waterChunkWaterSurfaceMethod = lookup.unreflect(waterChunkDataClass.getMethod("waterSurface", int.class, int.class));
            waterChunkTerrainSurfaceMethod = lookup.unreflect(waterChunkDataClass.getMethod("terrainSurface", int.class, int.class));

            try {
                Method m = earthChunkGeneratorClass.getDeclaredMethod("treeFeaturesForBiome", Holder.class);
                m.setAccessible(true);
                treeFeaturesForBiomeMethod = lookup.unreflect(m);
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.warn("could not find treeFeaturesForBiome");
            }

            tellusPresent = true;
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("tellus not found for sampling integration: " + e.getMessage());
            tellusPresent = false;
        }
    }

    public static boolean isTellusPresent() {
        if (!initialized) initialize();
        return tellusPresent;
    }

    public static java.util.List<?> getTreeFeatures(Holder<Biome> biome) {
        if (treeFeaturesForBiomeMethod == null) return java.util.Collections.emptyList();
        try {
            return (java.util.List<?>) treeFeaturesForBiomeMethod.invoke(null, biome);
        } catch (Throwable e) {
            return java.util.Collections.emptyList();
        }
    }

    public static void prefetch(ServerLevel level, ChunkPos pos) {
        if (!isTellusPresent()) return;
        try {
            Object generator = level.getChunkSource().getGenerator();
            earthChunkGeneratorClass.getMethod("prefetchForChunk", int.class, int.class).invoke(generator, Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));
        } catch (Throwable ignored) {}
    }

    public record TellusChunkData(int[] heights, byte[] coverClasses, byte[] slopes, boolean[] hasWater, int[] waterSurfaces, int seaLevel) {}

    public static TellusChunkData sample(ServerLevel level, ChunkPos pos) {
        if (!isTellusPresent()) return null;

        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object elevationSource = elevationSourceField.get(null);
            Object landCoverSource = landCoverSourceField.get(null);
            Object landMaskSource = landMaskSourceField.get(null);
            Object waterResolver = waterResolverField.get(generator);

            double worldScale = (double) worldScaleHandle.invoke(settings);
            double terrestrialHeightScale = (double) terrestrialHeightScaleHandle.invoke(settings);
            double oceanicHeightScale = (double) oceanicHeightScaleHandle.invoke(settings);
            int heightOffset = (int) heightOffsetHandle.invoke(settings);
            int seaLevel = (int) resolveSeaLevelHandle.invoke(settings);

            int[] heights = new int[256];
            byte[] coverClasses = new byte[256];
            byte[] slopes = new byte[256];
            boolean[] hasWaters = new boolean[256];
            int[] waterSurfaces = new int[256];

            int minBlockX = pos.getMinBlockX();
            int minBlockZ = pos.getMinBlockZ();

            Object waterData = resolveChunkWaterDataMethod.invoke(waterResolver, Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));

            for (int i = 0; i < 256; i++) {
                int z = i >> 4;
                int x = i & 15;
                int worldZ = minBlockZ + z;
                int worldX = minBlockX + x;
                try {
                    boolean hWater = (boolean) waterChunkHasWaterMethod.invoke(waterData, x, z);
                    int terrainH = (int) waterChunkTerrainSurfaceMethod.invoke(waterData, x, z);
                    int waterH = (int) waterChunkWaterSurfaceMethod.invoke(waterData, x, z);
                    
                    heights[i] = terrainH;
                    hasWaters[i] = hWater;
                    waterSurfaces[i] = waterH;
                    coverClasses[i] = (byte) (int) sampleCoverClassMethod.invoke(landCoverSource, (double) worldX, (double) worldZ, worldScale);

                    // sampling slopes with step 4
                    int step = 4;
                    int hE = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX + step, worldZ);
                    int hW = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX - step, worldZ);
                    int hN = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX, worldZ - step);
                    int hS = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX, worldZ + step);
                    
                    int maxDiff = Math.max(
                            Math.max(Math.abs(hE - terrainH), Math.abs(hW - terrainH)),
                            Math.max(Math.abs(hN - terrainH), Math.abs(hS - terrainH))
                    );
                    slopes[i] = (byte) Math.min(255, maxDiff);
                } catch (Throwable e) {
                    heights[i] = heightOffset;
                    coverClasses[i] = 0;
                    slopes[i] = 0;
                    hasWaters[i] = false;
                    waterSurfaces[i] = seaLevel;
                }
            }
            return new TellusChunkData(heights, coverClasses, slopes, hasWaters, waterSurfaces, seaLevel);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int samplePixelHeight(Object elevationSource, Object landCoverSource, Object landMaskSource, double worldScale, double terrestrialHeightScale, double oceanicHeightScale, int heightOffset, int worldX, int worldZ) throws Throwable {
        boolean oceanZoom = true;
        Object landSample = sampleLandMaskMethod.invoke(landMaskSource, (double) worldX, (double) worldZ, worldScale);
        if ((boolean) landMaskSampleKnownMethod.invoke(landSample)) {
            if ((boolean) landMaskSampleLandMethod.invoke(landSample)) {
                oceanZoom = false;
            } else {
                int coverClass = (int) sampleCoverClassMethod.invoke(landCoverSource, (double) worldX, (double) worldZ, worldScale);
                oceanZoom = (coverClass == ESA_NO_DATA || coverClass == ESA_WATER);
            }
        }
        double elevation = (double) sampleElevationMetersMethod.invoke(elevationSource, (double) worldX, (double) worldZ, worldScale, oceanZoom);
        double heightScale = (elevation >= 0.0) ? terrestrialHeightScale : oceanicHeightScale;
        double scaled = (elevation * heightScale) / worldScale;
        return ((elevation >= 0.0) ? Mth.ceil(scaled) : Mth.floor(scaled)) + heightOffset;
    }

    public static int sampleHeightOnly(ServerLevel level, int worldX, int worldZ) {
        if (!isTellusPresent()) return 64;
        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object elevationSource = elevationSourceField.get(null);
            Object landCoverSource = landCoverSourceField.get(null);
            Object landMaskSource = landMaskSourceField.get(null);
            double worldScale = (double) worldScaleHandle.invoke(settings);
            double terrestrialHeightScale = (double) terrestrialHeightScaleHandle.invoke(settings);
            double oceanicHeightScale = (double) oceanicHeightScaleHandle.invoke(settings);
            int heightOffset = (int) heightOffsetHandle.invoke(settings);
            return samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX, worldZ);
        } catch (Throwable e) {
            return 64;
        }
    }

    public static int sampleCoverClass(ServerLevel level, int worldX, int worldZ) {
        if (!isTellusPresent()) return 0;
        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object landCoverSource = landCoverSourceField.get(null);
            double worldScale = (double) worldScaleHandle.invoke(settings);
            return (int) sampleCoverClassMethod.invoke(landCoverSource, (double) worldX, (double) worldZ, worldScale);
        } catch (Throwable e) {
            return 0;
        }
    }
}
