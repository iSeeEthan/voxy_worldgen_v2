package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.integration.tellus.TellusIntegration;
import com.ethan.voxyworldgenv2.mixin.MinecraftServerAccess;

import com.ethan.voxyworldgenv2.mixin.ServerChunkCacheMixin;
import com.ethan.voxyworldgenv2.stats.GenerationStats;

import net.minecraft.client.multiplayer.chat.LoggedChatMessage.Player;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.UUID;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkGenerationManager {
    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();
    
    private static class DimensionState {
        final ServerLevel level;
        final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
        final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
        final DistanceGraph distanceGraph = new DistanceGraph();
        final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
        final AtomicInteger remainingInRadius = new AtomicInteger(0);
        boolean tellusActive = false;
        boolean loaded = false;

        DimensionState(ServerLevel level) {
            this.level = level;
        }
    }

    private final Map<ResourceKey<Level>, DimensionState> dimensionStates = new ConcurrentHashMap<>();
    
    // global state
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);
    
    // components
    private final TpsMonitor tpsMonitor = new TpsMonitor();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private final java.util.Map<java.util.UUID, ChunkPos> lastPlayerPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.BooleanSupplier pauseCheck = () -> false;

    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    
    // c2me compatibility - queue ticket operations to process at safe time
    private record TicketOp(ServerLevel level, ChunkPos pos, boolean add) {}
    private final ConcurrentLinkedQueue<TicketOp> pendingTicketOps = new ConcurrentLinkedQueue<>();

    private ChunkGenerationManager() {}
    
    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
    }

    private DimensionState getOrSetupState(ServerLevel level) {
        return dimensionStates.computeIfAbsent(level.dimension(), k -> {
            DimensionState state = new DimensionState(level);
            state.tellusActive = TellusIntegration.isTellusWorld(level);
            return state;
        });
    }

    public ServerLevel getCurrentLevel() {
        return currentLevel;
    }
    
    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);
        // unpaused by default
        this.pauseCheck = () -> false; 
        Config.load();
        this.throttle = new Semaphore(Config.DATA.maxActiveTasks);
        startWorker();
        VoxyWorldGenV2.LOGGER.info("voxy world gen initialized");
    }
    
    public void shutdown() {
        running.set(false);
        stopWorker();
        TellusIntegration.shutdown();
        
        for (var entry : dimensionStates.entrySet()) {
            DimensionState state = entry.getValue();
            if (state.loaded) {
                ChunkPersistence.save(state.level, entry.getKey(), state.completedChunks);
            }
        }
        
        dimensionStates.clear();
        pendingTicketOps.clear();
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        tpsMonitor.reset();
        currentDimensionKey = null;
        currentLevel = null;
        lastPlayerPositions.clear();
    }

    private void startWorker() {
        if (workerRunning.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "Voxy-WorldGen-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                // wait up to 5 seconds for worker to die
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void workerLoop() {
        while (workerRunning.get() && running.get()) {
            try {
                if (!Config.DATA.enabled || server == null) {
                    Thread.sleep(100);
                    continue;
                }

                if (!VoxyIntegration.isVoxyRenderingEnabled()) {
                    Thread.sleep(500);
                    continue;
                }

                if (tpsMonitor.isThrottled() || pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }
                
                var players = new ArrayList<>(PlayerTracker.getInstance().getPlayers());
                if (players.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }
                
                List<ChunkPos> batch = null;
                DimensionState activeState = null;
                
                // try to find work around any player in their respective dimension
                for (ServerPlayer player : players) {
                    DimensionState ds = getOrSetupState((ServerLevel) player.level());
                    int radius = ds.tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
                    batch = ds.distanceGraph.findWork(player.chunkPosition(), radius, ds.trackedBatches);
                    if (batch != null) {
                        activeState = ds;
                        break;
                    }
                }
                
                if (batch == null) {
                    // if no generation work, try to catch up on syncing for any player
                    boolean workDispatched = false;

                    ArrayList<UUID> unsyncedPlayersUUIDs = new ArrayList<>();

                    for (ServerPlayer player : players) {
                        if (PlayerTracker.getInstance().getSyncedChunks(player.getUUID()) == null) {
                            unsyncedPlayersUUIDs.add(player.getUUID());
                        }
                    }
                    
                    for (ServerPlayer player : players) {
                        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
                        if (synced == null) continue;
                        
                        DimensionState ds = getOrSetupState((ServerLevel) player.level());
                        int radius = ds.tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
                        List<ChunkPos> syncBatch = new ArrayList<>();
                        ds.distanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced, syncBatch, 64);
                        
                        if (!syncBatch.isEmpty()) {
                            workDispatched = true;
                            final List<ChunkPos> finalSyncBatch = new ArrayList<>(syncBatch);
                            final ServerLevel level = ds.level;

                            final UUID playerUUID = player.getUUID();
                            // mark all as synced now so we don't retry unloaded chunks in a tight loop;
                            // the block update mixin will re-sync them when they load naturally
                            for (ChunkPos syncPos : finalSyncBatch) {
                                synced.add(syncPos.toLong());
                            }
                            
                            server.execute(() -> {
                                ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
                                if (p != null) {
                                    for (ChunkPos syncPos : finalSyncBatch) {
                                        LevelChunk chunk = level.getChunkSource().getChunk(syncPos.x, syncPos.z, false);
                                        
                                        // if c == null the chunk is not loaded; the BlockUpdateMixin will
                                        // handle syncing it when it gets loaded into memory later
                                        if (chunk != null) {

                                            // Find other players that needs this chunk
                                            ArrayList<ServerPlayer> playersToSendTo = new ArrayList<>();
                                            for (UUID otherPlayerUUID : unsyncedPlayersUUIDs) {
                                                ServerPlayer otherPlayer = server.getPlayerList().getPlayer(otherPlayerUUID);
                                                if (otherPlayer != null) {
                                                    if (PlayerTracker.getInstance().getSyncedChunks(otherPlayer.getUUID()).contains(syncPos.toLong()) == false) {
                                                        playersToSendTo.add(otherPlayer);

                                                        // add to player's synced chunks
                                                        PlayerTracker.getInstance().getSyncedChunks(otherPlayer.getUUID()).add(syncPos.toLong());
                                                    }
                                                }
                                            }
                                            
                                            for (ServerPlayer playerToSendTo : playersToSendTo) {
                                                com.ethan.voxyworldgenv2.network.NetworkHandler.sendLODData(playerToSendTo, chunk);
                                            }

                                            
                                        }
                                    }
                                }
                            });
                            
                            //break; // processed one player, break to skip sleep
                        }
                    }
                    
                    if (workDispatched) {
                        Thread.sleep(10); // small delay to prevent overwhelming network/server tasks
                        continue; 
                    }
                    
                    Thread.sleep(100);
                    continue;
                }
                
                final DimensionState finalState = activeState;
                long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
                finalState.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

                // skip if already tracked locally
                List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
                for (ChunkPos pos : batch) {
                    long key = pos.toLong();
                    if (finalState.completedChunks.contains(key) || finalState.trackedChunks.contains(key)) {
                        onSuccess(finalState, pos);
                    } else {
                        preFiltered.add(pos);
                    }
                }

                if (preFiltered.isEmpty()) {
                    finalState.trackedBatches.remove(batchKey);
                    finalState.batchCounters.remove(batchKey);
                    continue;
                }

                // dispatch tasks
                List<ChunkPos> readyToGenerate = new ArrayList<>();
                int processedCount = 0;
                for (ChunkPos pos : preFiltered) {
                    if (!workerRunning.get()) break;
                    
                    boolean acquired = false;
                    try {
                        acquired = throttle.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    if (!acquired) break;
                    
                    processedCount++;
                    if (finalState.trackedChunks.add(pos.toLong())) {
                        activeTaskCount.incrementAndGet();
                        stats.incrementQueued();
                        
                        if (finalState.tellusActive) {
                            TellusIntegration.enqueueGenerate(finalState.level, pos, () -> {
                                onSuccess(finalState, pos);
                                completeTask(finalState, pos);
                            });
                            continue;
                        }
                        
                        readyToGenerate.add(pos);
                    } else {
                        throttle.release();
                        onFailure(finalState, pos);
                    }
                }
                
                if (processedCount < preFiltered.size()) {
                    finalState.trackedBatches.remove(batchKey);
                    finalState.batchCounters.remove(batchKey);
                }

                if (!readyToGenerate.isEmpty()) {
                    server.execute(() -> {
                        ServerChunkCache cache = finalState.level.getChunkSource();
                        List<ChunkPos> actuallyGenerate = new ArrayList<>();
                        
                        for (ChunkPos pos : readyToGenerate) {
                            if (finalState.level.hasChunk(pos.x, pos.z)) {
                                LevelChunk existingChunk = finalState.level.getChunk(pos.x, pos.z);
                                if (existingChunk != null && !existingChunk.isEmpty()) {
                                    VoxyIntegration.ingestChunk(existingChunk);
                                    com.ethan.voxyworldgenv2.network.NetworkHandler.broadcastLODData(existingChunk);
                                }
                                onSuccess(finalState, pos);
                                completeTask(finalState, pos);
                            } else {
                                queueTicketAdd(finalState.level, pos);
                                actuallyGenerate.add(pos);
                            }
                        }
                        
                        if (!actuallyGenerate.isEmpty()) {
                            // apply tickets immediately to ensure DistanceManager is aware of them, keeps stuff nice and clean
                            processPendingTickets();

                            for (ChunkPos pos : actuallyGenerate) {
                                ((ServerChunkCacheMixin) cache).invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                                    .whenCompleteAsync((result, throwable) -> {
                                        if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                            onSuccess(finalState, pos);
                                            if (!chunk.isEmpty()) {
                                                VoxyIntegration.ingestChunk(chunk);
                                                com.ethan.voxyworldgenv2.network.NetworkHandler.broadcastLODData(chunk);
                                            }
                                        } else {
                                            onFailure(finalState, pos);
                                        }
                                        cleanupTask(finalState.level, pos);
                                    }, server);
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    public void tick() {
        if (!running.get() || server == null) return;
        
        processPendingTickets();
        
        if (configReloadScheduled.compareAndSet(true, false)) {
            Config.load();
            updateThrottleCapacity();
            restartScan();
        }
        
        tpsMonitor.tick();
        stats.tick();
        checkPlayerMovement();
        
        // broadcast changes for all active dimensions
        Set<ServerLevel> activeLevels = new HashSet<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            activeLevels.add((ServerLevel) player.level());
        }
        for (ServerLevel level : activeLevels) {
            ChunkUpdateTracker.getInstance().processDirty(level);
        }
    }
    
    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) {
            if (!lastPlayerPositions.isEmpty()) {
                lastPlayerPositions.clear();
            }
            return;
        }

        boolean shouldRescan = false;
        Map<ServerLevel, Integer> levelCounts = new HashMap<>();
        
        for (ServerPlayer player : players) {
            levelCounts.merge((ServerLevel) player.level(), 1, Integer::sum);
            ChunkPos currentPos = player.chunkPosition();
            ChunkPos lastPos = lastPlayerPositions.get(player.getUUID());
            
            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
            }
        }
        
        // majority check for currentLevel - only switch when a candidate strictly exceeds the current level's count...
        ServerLevel majorLevel = currentLevel;
        int maxCount = levelCounts.getOrDefault(currentLevel, 0);
        
        for (var entry : levelCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                majorLevel = entry.getKey();
            }
        }
        
        if (majorLevel != currentLevel && majorLevel != null) {
            setupLevel(majorLevel);
            return;
        }
        
        // clean up players who left
        Set<java.util.UUID> currentPlayerIds = new java.util.HashSet<>();
        for (ServerPlayer p : players) currentPlayerIds.add(p.getUUID());
        if (lastPlayerPositions.size() > currentPlayerIds.size()) {
            lastPlayerPositions.keySet().removeIf(uuid -> !currentPlayerIds.contains(uuid));
            shouldRescan = true;
        }

        if (shouldRescan) {
            restartScan();
        }
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return (double) dx * dx + dz * dz;
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            DimensionState oldState = dimensionStates.get(currentDimensionKey);
            if (oldState != null) {
                ChunkPersistence.save(currentLevel, currentDimensionKey, oldState.completedChunks);
            }
        }
        
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        DimensionState state = getOrSetupState(newLevel);
        
        if (!state.loaded) {
            if (state.tellusActive) {
                VoxyWorldGenV2.LOGGER.info("tellus world detected for {}, enabling fast generation", currentDimensionKey);
            }
            ChunkPersistence.load(newLevel, currentDimensionKey, state.completedChunks);
            synchronized(state.completedChunks) {
                for (long pos : state.completedChunks) {
                    state.distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                }
            }
            state.loaded = true;
        }
        
        restartScan();
    }
    
    private void restartScan() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;
        
        java.util.Map<DimensionState, Integer> maxCounts = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            DimensionState state = getOrSetupState((ServerLevel) player.level());
            int radius = state.tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
            int missing = state.distanceGraph.countMissingInRange(player.chunkPosition(), radius);
            maxCounts.merge(state, missing, Math::max);
        }
        
        maxCounts.forEach((state, count) -> state.remainingInRadius.set(count));
    }

    private void updateThrottleCapacity() {
        int target = Config.DATA.maxActiveTasks;
        int available = throttle.availablePermits();
        int maxPossible = available + activeTaskCount.get();
        if (target > maxPossible) {
            throttle.release(target - maxPossible);
        }
    }
    
    private void processPendingTickets() {
        TicketOp op;
        java.util.Set<ServerLevel> modifiedLevels = new java.util.HashSet<>();
        while ((op = pendingTicketOps.poll()) != null) {
            ServerChunkCache cache = op.level().getChunkSource();
            if (op.add()) {
                cache.addTicketWithRadius(TicketType.FORCED, op.pos(), 0);
            } else {
                cache.removeTicketWithRadius(TicketType.FORCED, op.pos(), 0);
            }
            modifiedLevels.add(op.level());
        }
        for (ServerLevel level : modifiedLevels) {
            ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates();
        }
    }
    
    private void queueTicketAdd(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, true));
    }
    
    private void queueTicketRemove(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, false));
    }
    
    private void cleanupTask(ServerLevel level, ChunkPos pos) {
        queueTicketRemove(level, pos);
        ((MinecraftServerAccess) server).setEmptyTicks(0);
        DimensionState state = dimensionStates.get(level.dimension());
        if (state != null) completeTask(state, pos);
    }

    private void onSuccess(DimensionState state, ChunkPos pos) {
        long key = pos.toLong();
        if (state.completedChunks.add(key)) {
            stats.incrementCompleted();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
            state.remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
        }
        decrementBatch(state, pos);
    }
    
    private void onFailure(DimensionState state, ChunkPos pos) {
        stats.incrementFailed();
        state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
        decrementBatch(state, pos);
    }

    private void decrementBatch(DimensionState state, ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(pos.x, pos.z);
        AtomicInteger counter = state.batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }
    }
    
    private void completeTask(DimensionState state, ChunkPos pos) {
        if (state.trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }
    
    public boolean isChunkCompleted(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        DimensionState state = dimensionStates.get(level.dimension());
        return state != null && state.completedChunks.contains(pos.toLong());
    }

    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() {
        if (currentDimensionKey == null) return 0;
        DimensionState state = dimensionStates.get(currentDimensionKey);
        return state != null ? state.remainingInRadius.get() : 0; 
    }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; }
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
