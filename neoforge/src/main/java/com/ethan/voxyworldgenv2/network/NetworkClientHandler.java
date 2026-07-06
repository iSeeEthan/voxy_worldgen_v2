package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NetworkClientHandler {

    // drained nearest-first each tick, sorted once per tick instead of scanned per poll
    private static final ArrayDeque<NetworkHandler.LODDataPayload> INGEST_QUEUE = new ArrayDeque<>();
    private static final Object QUEUE_LOCK = new Object();
    // max sections to ingest per client tick
    private static final int MAX_SECTIONS_PER_TICK = 96;
    // cap on the queue so a huge backfill can't grow it without bound
    private static final int MAX_QUEUE_SIZE = 8192;

    // kept for the client init call, real registration happens in NetworkHandler
    public static void init() {
    }

    public static void handleHandshake(NetworkHandler.HandshakePayload payload, IPayloadContext context) {
        boolean serverHasMod = payload.serverHasMod();
        context.enqueueWork(() -> {
            // only connected if the server matches our protocol
            boolean compatible = serverHasMod
                    && payload.protocolVersion() == VoxyWorldGenV2.PROTOCOL_VERSION;
            if (serverHasMod && !compatible) {
                VoxyWorldGenV2.LOGGER.warn("server voxy protocol {} != ours {}, LOD sync disabled",
                        payload.protocolVersion(), VoxyWorldGenV2.PROTOCOL_VERSION);
            }
            NetworkState.setServerConnected(compatible);
            // reply so the server knows this client can receive lod data
            PacketDistributor.sendToServer(new NetworkHandler.HandshakeAckPayload(true, VoxyWorldGenV2.PROTOCOL_VERSION));
        });
    }

    public static void handleServerConfig(NetworkHandler.ServerConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ServerConfigState.set(payload.config(), payload.canEdit()));
    }

    public static void handleLODData(NetworkHandler.LODDataPayload payload, IPayloadContext context) {
        synchronized (QUEUE_LOCK) {
            INGEST_QUEUE.addLast(payload);
            // memory backstop, drain trims by distance, this only fires on a burst
            // between ticks where dropping oldest is fine
            if (INGEST_QUEUE.size() > MAX_QUEUE_SIZE) {
                INGEST_QUEUE.pollFirst();
            }
        }
    }

    // player's chunk position, call on the client main thread
    private static ChunkPos playerChunk() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return mc.player.chunkPosition();
    }

    private static long distSq(ChunkPos a, ChunkPos b) {
        long dx = a.x - b.x;
        long dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    // drains a bounded number of sections per tick, nearest first
    // sort once per tick, ingest outside the lock so the netty thread isn't blocked
    public static void drainIngestQueue() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // not in a world, drop anything queued so it doesn't leak into the next session
            synchronized (QUEUE_LOCK) {
                INGEST_QUEUE.clear();
            }
            return;
        }

        ChunkPos center = playerChunk();

        // grab everything under the lock, work on it after
        List<NetworkHandler.LODDataPayload> snapshot;
        synchronized (QUEUE_LOCK) {
            if (INGEST_QUEUE.isEmpty()) return;
            snapshot = new ArrayList<>(INGEST_QUEUE);
            INGEST_QUEUE.clear();
        }

        if (center != null) {
            final ChunkPos c = center;
            snapshot.sort(Comparator.comparingLong(p -> distSq(c, p.pos())));
        }

        int sectionsThisTick = 0;
        int i = 0;
        for (; i < snapshot.size() && sectionsThisTick < MAX_SECTIONS_PER_TICK; i++) {
            NetworkHandler.LODDataPayload payload = snapshot.get(i);
            sectionsThisTick += payload.sections().size();
            processLODData(level, payload);
        }

        // put the rest back, dropping the farthest if we're over the cap
        if (i < snapshot.size()) {
            synchronized (QUEUE_LOCK) {
                int kept = 0;
                for (; i < snapshot.size() && kept < MAX_QUEUE_SIZE; i++, kept++) {
                    INGEST_QUEUE.addLast(snapshot.get(i));
                }
                // concurrent adds sit at the back, drop oldest to hold the cap
                while (INGEST_QUEUE.size() > MAX_QUEUE_SIZE) {
                    INGEST_QUEUE.pollFirst();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void processLODData(ClientLevel level, NetworkHandler.LODDataPayload payload) {
        // drop lod data from another dimension to avoid cross-dimension artifacts
        if (!level.dimension().equals(payload.dimension())) return;

        // approx payload size for the stats hud
        long bytes = 0;
        for (NetworkHandler.LODDataPayload.SectionData sd : payload.sections()) {
            bytes += sd.states().length;
            bytes += sd.biomes().length;
            if (sd.blockLight() != null) bytes += sd.blockLight().length;
            if (sd.skyLight() != null) bytes += sd.skyLight().length;
        }
        NetworkState.incrementReceived(bytes);

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        for (NetworkHandler.LODDataPayload.SectionData sectionData : payload.sections()) {
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.states());
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                // recreate the section using the biome registry
                LevelChunkSection section = new LevelChunkSection(biomeRegistry);

                // read states+biomes back with RegistryFriendlyByteBuf for palette consistency
                net.minecraft.network.RegistryFriendlyByteBuf statesBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    new net.minecraft.network.FriendlyByteBuf(statesRaw),
                    level.registryAccess()
                );
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);

                net.minecraft.network.RegistryFriendlyByteBuf biomesBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    new net.minecraft.network.FriendlyByteBuf(biomesRaw),
                    level.registryAccess()
                );
                
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);

                // hand it to voxy
                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;

                VoxyIntegration.rawIngest(level, section, payload.pos().x, sectionData.y(), payload.pos().z, bl, sl);

            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("failed to handle LOD data for chunk " + payload.pos(), e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
    }
}
