package com.ethan.voxyworldgenv2.platform;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;

import java.util.ServiceLoader;

// loads the per-loader service impls via ServiceLoader at runtime
public final class Services {
    private Services() {}

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkBridge NETWORK = load(INetworkBridge.class);
    public static final ChunkPosCompat CHUNK_POS = load(ChunkPosCompat.class);

    private static <T> T load(Class<T> clazz) {
        final T loaded = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No service implementation found for " + clazz.getName()
                                + " — is the loader module's META-INF/services entry present?"));
        VoxyWorldGenV2.LOGGER.debug("Loaded {} for service {}", loaded.getClass(), clazz.getSimpleName());
        return loaded;
    }
}
