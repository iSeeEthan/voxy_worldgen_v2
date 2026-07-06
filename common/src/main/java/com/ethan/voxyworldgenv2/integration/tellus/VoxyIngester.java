package com.ethan.voxyworldgenv2.integration.tellus;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// reflective access to voxy
public final class VoxyIngester {
    private static boolean initialized = false;
    private static boolean voxyPresent = false;

    private static Class<?> voxyCommonClass;
    private static Class<?> worldIdentifierClass;
    private static Class<?> worldEngineClass;
    private static Class<?> mapperClass;
    private static Class<?> voxelizedSectionClass;
    private static Class<?> worldUpdaterClass;
    private static Class<?> worldConversionFactoryClass;

    private static MethodHandle getInstanceHandle;
    private static MethodHandle getOrCreateWorldHandle;
    private static MethodHandle worldIdOfHandle;
    private static MethodHandle getMapperHandle;
    private static MethodHandle getIdForBlockStateHandle;
    private static MethodHandle createEmptySectionHandle;
    private static MethodHandle setPositionHandle;
    private static MethodHandle withLightHandle;
    private static MethodHandle withBlockBiomeHandle;
    private static MethodHandle mipSectionHandle;
    private static MethodHandle insertUpdateHandle;
    private static MethodHandle getBiomeIdHandle;

    private static java.lang.reflect.Field sectionDataField;
    private static java.lang.reflect.Field sectionNonAirCountField;

    private VoxyIngester() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            voxyCommonClass = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
            mapperClass = Class.forName("me.cortex.voxy.common.world.other.Mapper");
            voxelizedSectionClass = Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection");
            worldUpdaterClass = Class.forName("me.cortex.voxy.common.world.WorldUpdater");
            worldConversionFactoryClass = Class.forName("me.cortex.voxy.common.voxelization.WorldConversionFactory");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            getInstanceHandle = lookup.unreflect(voxyCommonClass.getMethod("getInstance"));
            getOrCreateWorldHandle = lookup.unreflect(Class.forName("me.cortex.voxy.commonImpl.VoxyInstance").getMethod("getOrCreate", worldIdentifierClass));
            worldIdOfHandle = lookup.unreflect(worldIdentifierClass.getMethod("of", net.minecraft.world.level.Level.class));
            
            getMapperHandle = lookup.unreflect(worldEngineClass.getMethod("getMapper"));
            getIdForBlockStateHandle = lookup.unreflect(mapperClass.getMethod("getIdForBlockState", BlockState.class));
            getBiomeIdHandle = lookup.unreflect(mapperClass.getMethod("getIdForBiome", Holder.class));
            
            createEmptySectionHandle = lookup.unreflect(voxelizedSectionClass.getMethod("createEmpty"));
            setPositionHandle = lookup.unreflect(voxelizedSectionClass.getMethod("setPosition", int.class, int.class, int.class));
            
            withLightHandle = lookup.unreflect(mapperClass.getMethod("withLight", long.class, int.class));
            withBlockBiomeHandle = lookup.unreflect(mapperClass.getMethod("withBlockBiome", long.class, int.class, int.class));
            
            mipSectionHandle = lookup.unreflect(worldConversionFactoryClass.getMethod("mipSection", voxelizedSectionClass, mapperClass));
            insertUpdateHandle = lookup.unreflect(worldUpdaterClass.getMethod("insertUpdate", worldEngineClass, voxelizedSectionClass));

            sectionDataField = voxelizedSectionClass.getDeclaredField("section");
            sectionDataField.setAccessible(true);
            sectionNonAirCountField = voxelizedSectionClass.getDeclaredField("lvl0NonAirCount");
            sectionNonAirCountField.setAccessible(true);

            voxyPresent = true;
            VoxyWorldGenV2.LOGGER.info("voxy ingester initialized successfully (reflective)");
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("failed to initialize voxy ingester reflection: {}", e.getMessage());
            voxyPresent = false;
        }
    }

    public static boolean isAvailable() {
        if (!initialized) initialize();
        return voxyPresent;
    }

    public static Object getVoxyInstance() {
        if (!isAvailable()) return null;
        try {
            return getInstanceHandle.invoke();
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object getWorldEngine(Object voxyInstance, ServerLevel level) {
        try {
            Object worldId = worldIdOfHandle.invoke(level);
            return getOrCreateWorldHandle.invoke(voxyInstance, worldId);
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object getMapper(Object worldEngine) {
        try {
            return getMapperHandle.invoke(worldEngine);
        } catch (Throwable e) {
            return null;
        }
    }

    public static int getBlockId(Object mapper, BlockState state) {
        try {
            return (int) getIdForBlockStateHandle.invoke(mapper, state);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static int getBlockId(Object mapper, Block block) {
        return getBlockId(mapper, block.defaultBlockState());
    }

    public static int getBiomeId(Object mapper, Holder<Biome> biome) {
        try {
            return (int) getBiomeIdHandle.invoke(mapper, biome);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static long composeId(int blockId, int biomeId, int light) {
        // voxy light packing, block in bits 4-7 and sky in bits 0-3
        if (blockId == 0) { // air
            return ((long) (light & 0xFF)) << 56;
        }
        return (((long) (light & 0xFF)) << 56) |
               (((long) (biomeId & 0x1FF)) << 47) |
               (((long) (blockId & 0xFFFFF)) << 27);
    }

    public static Object createSection(int cx, int cy, int cz) {
        try {
            Object vs = createEmptySectionHandle.invoke();
            return setPositionHandle.invoke(vs, cx, cy, cz);
        } catch (Throwable e) {
            return null;
        }
    }

    public static long[] getSectionData(Object section) {
        try {
            return (long[]) sectionDataField.get(section);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public static void setNonAirCount(Object section, int count) {
        try {
            sectionNonAirCountField.set(section, count);
        } catch (IllegalAccessException ignored) {}
    }

    public static void mipAndInsert(Object engine, Object mapper, Object section) {
        try {
            mipSectionHandle.invoke(section, mapper);
            insertUpdateHandle.invoke(engine, section);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to mip/insert voxy section", e);
        }
    }
}
