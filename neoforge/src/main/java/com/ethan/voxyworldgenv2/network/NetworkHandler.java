package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

public class NetworkHandler {
    public static final ResourceLocation HANDSHAKE_ID = ResourceLocation.fromNamespaceAndPath(VoxyWorldGenV2.MOD_ID, "handshake");
    public static final ResourceLocation HANDSHAKE_ACK_ID = ResourceLocation.fromNamespaceAndPath(VoxyWorldGenV2.MOD_ID, "handshake_ack");
    public static final ResourceLocation LOD_DATA_ID = ResourceLocation.fromNamespaceAndPath(VoxyWorldGenV2.MOD_ID, "lod_data");
    public static final ResourceLocation SERVER_CONFIG_ID = ResourceLocation.fromNamespaceAndPath(VoxyWorldGenV2.MOD_ID, "server_config");
    public static final ResourceLocation SERVER_CONFIG_PUSH_ID = ResourceLocation.fromNamespaceAndPath(VoxyWorldGenV2.MOD_ID, "server_config_push");

    // keep packets well under netty 2mb limit so servers don't reset the connection
    private static final int MAX_PACKET_BYTES = 32_768;
    private static final int SECTION_OVERHEAD_BYTES = 32;
    private static final int PACKET_OVERHEAD_BYTES = 256;

    public record HandshakePayload(boolean serverHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
            buf.writeVarInt(this.protocolVersion);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // client -> server ack so the server knows this client can receive lod data
    public record HandshakeAckPayload(boolean clientHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakeAckPayload> TYPE = new Type<>(HANDSHAKE_ACK_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakeAckPayload> CODEC = CustomPacketPayload.codec(HandshakeAckPayload::write, HandshakeAckPayload::new);

        public HandshakeAckPayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.clientHasMod);
            buf.writeVarInt(this.protocolVersion);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // server -> client the live server values plus whether this client may edit them
    public record ServerConfigPayload(Config.ServerConfig config, boolean canEdit) implements CustomPacketPayload {
        public static final Type<ServerConfigPayload> TYPE = new Type<>(SERVER_CONFIG_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPayload> CODEC = CustomPacketPayload.codec(ServerConfigPayload::write, ServerConfigPayload::new);

        public ServerConfigPayload(FriendlyByteBuf buf) {
            this(readConfig(buf), buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            writeConfig(buf, config);
            buf.writeBoolean(canEdit);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // client op pushes new values to apply
    public record ServerConfigPushPayload(Config.ServerConfig config) implements CustomPacketPayload {
        public static final Type<ServerConfigPushPayload> TYPE = new Type<>(SERVER_CONFIG_PUSH_ID);
        public static final StreamCodec<FriendlyByteBuf, ServerConfigPushPayload> CODEC = CustomPacketPayload.codec(ServerConfigPushPayload::write, ServerConfigPushPayload::new);

        public ServerConfigPushPayload(FriendlyByteBuf buf) {
            this(readConfig(buf));
        }

        public void write(FriendlyByteBuf buf) {
            writeConfig(buf, config);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static Config.ServerConfig readConfig(FriendlyByteBuf buf) {
        return new Config.ServerConfig(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeConfig(FriendlyByteBuf buf, Config.ServerConfig c) {
        buf.writeBoolean(c.enabled());
        buf.writeVarInt(c.generationRadius());
        buf.writeVarInt(c.updateInterval());
        buf.writeVarInt(c.maxQueueSize());
        buf.writeVarInt(c.maxActiveTasks());
    }

    public record LODDataPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) implements CustomPacketPayload {
        public static final Type<LODDataPayload> TYPE = new Type<>(LOD_DATA_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LODDataPayload> CODEC = CustomPacketPayload.codec(LODDataPayload::write, LODDataPayload::new);

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(RegistryFriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }

            public static SectionData read(RegistryFriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(),
                    buf.readByteArray(),
                    buf.readByteArray(),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())
                );
            }

            // approx serialized size incl framing, used for batch splitting
            int sizeBytes() {
                return states.length + biomes.length
                    + (blockLight != null ? blockLight.length : 0)
                    + (skyLight != null ? skyLight.length : 0)
                    + SECTION_OVERHEAD_BYTES;
            }
        }

        public LODDataPayload(RegistryFriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, b -> SectionData.read((RegistryFriendlyByteBuf) b))
            );
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimension.location().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            // cast to avoid ambiguous writeCollection / BiConsumer type issues
            buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // one sync radius shared by the broadcast, catch-up and chunk-load paths
    public static double syncRadiusSq() {
        long radiusBlocks = (long) Config.DATA.generationRadius * 16L;
        double r = radiusBlocks;
        return r * r;
    }

    public static boolean inSyncRange(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (player.level() != chunk.getLevel()) return false;
        double dx = player.getX() - pos.getMiddleBlockX();
        double dz = player.getZ() - pos.getMiddleBlockZ();
        return dx * dx + dz * dz <= syncRadiusSq();
    }

    // coarse range/dim check for the send pool, pos is read racily but worst case
    // is one extra chunk
    private static boolean stillRelevant(ServerPlayer player, ResourceKey<Level> dim, ChunkPos pos) {
        if (!player.level().dimension().equals(dim)) return false;
        double dx = player.getX() - pos.getMiddleBlockX();
        double dz = player.getZ() - pos.getMiddleBlockZ();
        return dx * dx + dz * dz <= syncRadiusSq();
    }

    // registers payload types client receive logic is in NetworkClientHandler, the ack is here
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VoxyWorldGenV2.MOD_ID)
                .optional(); // tolerate clients/servers without the mod

        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            ClientPayloadBinding.register(registrar);
        } else {
            registrar.playToClient(HandshakePayload.TYPE, HandshakePayload.CODEC, NetworkHandler::ignoreClientbound);
            registrar.playToClient(LODDataPayload.TYPE, LODDataPayload.CODEC, NetworkHandler::ignoreClientbound);
            registrar.playToClient(ServerConfigPayload.TYPE, ServerConfigPayload.CODEC, NetworkHandler::ignoreClientbound);
        }

        registrar.playToServer(
                HandshakeAckPayload.TYPE,
                HandshakeAckPayload.CODEC,
                NetworkHandler::handleHandshakeAck
        );
        registrar.playToServer(
                ServerConfigPushPayload.TYPE,
                ServerConfigPushPayload.CODEC,
                NetworkHandler::handleServerConfigPush
        );

        VoxyWorldGenV2.LOGGER.info("voxy networking initialized");
    }

    // server-side no-op the server never receives clientbound payloads
    private static <T extends CustomPacketPayload> void ignoreClientbound(T payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
    }

    private static void handleHandshakeAck(HandshakeAckPayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                // only modded if it acks and matches our protocol, else packets would
                // mis-parse so leave it unmodded and send nothing
                boolean compatible = payload.clientHasMod()
                        && payload.protocolVersion() == VoxyWorldGenV2.PROTOCOL_VERSION;
                if (payload.clientHasMod() && !compatible) {
                    VoxyWorldGenV2.LOGGER.warn("client {} has an incompatible voxy protocol (theirs={}, ours={}), not syncing LOD data",
                            player.getGameProfile().getName(), payload.protocolVersion(), VoxyWorldGenV2.PROTOCOL_VERSION);
                }
                PlayerTracker.getInstance().setModded(player.getUUID(), compatible);
                sendServerConfig(player);
                if (compatible) com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().onPlayerModded();
            });
        }
    }

    // gamemaster level 2, matches the fabric permission check
    private static final int CONFIG_OP_LEVEL = 2;

    // op level required to edit server config from the client
    private static boolean canEditConfig(ServerPlayer player) {
        return player.hasPermissions(CONFIG_OP_LEVEL);
    }

    private static void handleServerConfigPush(ServerConfigPushPayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> {
            if (!canEditConfig(player)) {
                VoxyWorldGenV2.LOGGER.warn("ignoring server config push from non-op {}", player.getGameProfile().getName());
                sendServerConfig(player); // snap their screen back to the real values
                return;
            }
            Config.applyServerConfig(payload.config());
            Config.save();
            com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            VoxyWorldGenV2.LOGGER.info("server config updated by op {}", player.getGameProfile().getName());
            broadcastServerConfig();
        });
    }

    // send the live server config to one player (canEdit reflects their op status)
    public static void sendServerConfig(ServerPlayer player) {
        if (!PlayerTracker.getInstance().isModded(player.getUUID())) return;
        boolean canEdit = canEditConfig(player);
        sendTo(player, new ServerConfigPayload(Config.ServerConfig.snapshot(), canEdit));
    }

    public static void broadcastServerConfig() {
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            sendServerConfig(player);
        }
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.toLong());
            } else {
                synced.remove(pos.toLong());
            }
        }
    }

    // background pool for the safe part of sync serialization stays on the
    // server thread because the palette write is not safe to read off-thread
    private static final java.util.concurrent.ExecutorService SEND_POOL = createSendPool();

    // cap the send queue, a storm pushes back on the server thread instead of
    // growing memory forever
    private static final int SEND_QUEUE_CAPACITY = 4096;

    private static java.util.concurrent.ExecutorService createSendPool() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        java.util.concurrent.ThreadPoolExecutor ex = new java.util.concurrent.ThreadPoolExecutor(
            threads, threads, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "Voxy-LOD-Send");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    public static void shutdown() {
        SEND_POOL.shutdownNow();
    }

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    // if onlySectionYs is non-null, only those section y-levels are sent (used for block edits)
    public static void broadcastLODData(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        List<ServerPlayer> recipients = new ArrayList<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (!PlayerTracker.getInstance().isModded(player.getUUID())) continue;
            if (!inSyncRange(player, chunk)) {
                if (onlySectionYs == null) setSyncedState(player, pos, false);
                continue;
            }
            recipients.add(player);
        }

        if (recipients.isEmpty()) return;

        List<LODDataPayload.SectionData> sections = buildSections(chunk, onlySectionYs);
        if (sections.isEmpty()) {
            if (onlySectionYs == null) {
                for (ServerPlayer player : recipients) setSyncedState(player, pos, false);
            }
            return;
        }
        if (onlySectionYs == null) {
            for (ServerPlayer player : recipients) setSyncedState(player, pos, true);
        }
        sendAsync(dim, pos, minY, sections, recipients);
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        if (!PlayerTracker.getInstance().isModded(player.getUUID())) return;
        if (!inSyncRange(player, chunk)) {
            setSyncedState(player, pos, false);
            return;
        }

        List<LODDataPayload.SectionData> sections = buildSections(chunk, null);
        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }
        setSyncedState(player, pos, true);
        sendAsync(dim, pos, minY, sections, List.of(player));
    }

    private static void sendAsync(ResourceKey<Level> dim, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections, List<ServerPlayer> recipients) {
        // can race that shutdown drop the send instead of crashing the server tick loop
        if (SEND_POOL.isShutdown()) return;
        try {
            SEND_POOL.execute(() -> {
                try {
                    for (ServerPlayer player : recipients) {
                        // by now the player may have left or moved off, skip stale sends
                        if (player.hasDisconnected()) continue;
                        if (!stillRelevant(player, dim, pos)) continue;
                        sendSectionsInBatches(player, dim, pos, minY, sections);
                    }
                } catch (Throwable t) {
                    VoxyWorldGenV2.LOGGER.error("failed to send LOD data for chunk " + pos, t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // pool was shut down between the check above and submit, the chunk should just re-syncs on next join
        }
    }

    private static List<LODDataPayload.SectionData> buildSections(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LODDataPayload.SectionData> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        LevelChunkSection[] sectionArray = chunk.getSections();
        for (int i = 0; i < sectionArray.length; i++) {
            int sectionY = minY + i;
            if (onlySectionYs != null && !onlySectionYs.contains(sectionY)) continue;

            LevelChunkSection section = sectionArray[i];
            if (section == null || section.hasOnlyAir()) continue;

            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.buffer();
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            try {
                byte[] states, biomes;
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), chunk.getLevel().registryAccess());
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), chunk.getLevel().registryAccess());
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);

                SectionPos sectionPos = SectionPos.of(pos, sectionY);
                DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                // empty block light is meaningless, voxy ingests null block light anyway
                // so send null instead of a dead 2048-byte array
                byte[] blData = (bl != null && !isAllZero(bl.getData())) ? bl.getData().clone() : null;

                sections.add(new LODDataPayload.SectionData(
                    sectionY,
                    states,
                    biomes,
                    blData,
                    sl != null ? sl.getData().clone() : null
                ));
            } catch (Throwable t) {
                VoxyWorldGenV2.LOGGER.debug("skipped section {} of chunk {}: {}", sectionY, pos, t.toString());
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }

        return sections;
    }

    // true if every byte is zero, used to drop empty block-light arrays
    private static boolean isAllZero(byte[] data) {
        if (data == null) return true;
        for (byte b : data) {
            if (b != 0) return false;
        }
        return true;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections) {
        List<LODDataPayload.SectionData> batch = new ArrayList<>();
        int batchBytes = PACKET_OVERHEAD_BYTES;

        for (LODDataPayload.SectionData sd : sections) {
            int sectionBytes = sd.sizeBytes();

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                sendTo(player, new LODDataPayload(dimension, pos, minY, batch));
                batch = new ArrayList<>();
                batchBytes = PACKET_OVERHEAD_BYTES;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            sendTo(player, new LODDataPayload(dimension, pos, minY, batch));
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        sendTo(player, new HandshakePayload(true, VoxyWorldGenV2.PROTOCOL_VERSION));
    }

    // vanilla and non voxy clients never open our channel, sending anyway throws
    // and kicks them with invalid player data, so only send when the channel is up
    private static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        if (!player.connection.hasChannel(payload.type())) return;
        PacketDistributor.sendToPlayer(player, payload);
    }
}
