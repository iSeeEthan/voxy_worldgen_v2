package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.integration.tellus.TellusIntegration;
import com.ethan.voxyworldgenv2.mixin.ServerChunkCacheMixin;
import com.ethan.voxyworldgenv2.platform.Services;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
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
        // chunks that failed to finish, retry count so we give up instead of looping
        final Map<Long, Integer> failCounts = new ConcurrentHashMap<>();
        boolean tellusActive = false;
        boolean loaded = false;

        DimensionState(ServerLevel level) {
            this.level = level;
        }
    }

    // give up on a chunk after this many failed tries
    private static final int MAX_CHUNK_RETRIES = 3;

    private final Map<ResourceKey<Level>, DimensionState> dimensionStates = new ConcurrentHashMap<>();

    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    // bumped every init/shutdown so callbacks from a previous world can tell they're
    // stale and not touch this session's throttle or counters
    private final AtomicInteger sessionId = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);

    private final TpsMonitor tpsMonitor = new TpsMonitor();
    // smoothed load so the in-flight count eases up and down instead of snapping
    // between the cap and a low value every time tps wobbles
    private double smoothedLoad = 1.0;
    private static final double LOAD_SMOOTHING = 0.2;
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private final java.util.Map<java.util.UUID, ChunkPos> lastPlayerPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.BooleanSupplier pauseCheck = () -> false;

    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    // rotated so one player doesn't hog the worker
    private int fairnessCursor = 0;
    private int syncPruneCounter = 0;
    private static final int SYNC_PRUNE_INTERVAL_TICKS = 600;

    // only run catchup every so often, it doesn't need to fire every loop
    private static final long CATCHUP_INTERVAL_MS = 400;
    // chunks resent per catchup pass per player, kept small to avoid tick spikes
    private static final int CATCHUP_BATCH = 8;
    private long lastCatchupMs = 0;

    // queued and applied on the main thread so we don't fight c2me
    private record TicketOp(ServerLevel level, ChunkPos pos, boolean add) {}
    // track these so we can clear them on shutdown, a leftover one hangs the save screen
    private final Map<ServerLevel, LongSet> appliedTickets = new ConcurrentHashMap<>();
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
        sessionId.incrementAndGet();
        this.server = server;
        this.running.set(true);
        this.pauseCheck = () -> false;
        Config.load();
        this.throttle = new Semaphore(Config.DATA.maxActiveTasks);
        startWorker();
        VoxyWorldGenV2.LOGGER.info("voxy world gen initialized");
    }

    public void shutdown() {
        running.set(false);
        sessionId.incrementAndGet();
        stopWorker();
        TellusIntegration.shutdown();

        // clear our tickets or the world hangs on the save screen when leaving
        releaseAllTickets();

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
        smoothedLoad = 1.0;
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

                if (pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }

                // only players with the client mod can render lod data, generating
                // around a vanilla or non voxy client is wasted work so skip them
                List<ServerPlayer> players = moddedPlayers();
                if (players.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }

                fairnessCursor = (fairnessCursor + 1) % players.size();
                players = rotated(players, fairnessCursor);

                // catchup is a resend pass, it doesn't need to run every loop
                long now = System.currentTimeMillis();
                if (now - lastCatchupMs >= CATCHUP_INTERVAL_MS) {
                    lastCatchupMs = now;
                    runCatchup(players);
                }

                // ease off under lag instead of stopping dead
                double load = tpsMonitor.loadFactor();
                // asymmetric smoothing: drop the budget instantly when tps dips so we
                // back off fast, but ramp back up slowly so the active count doesn't
                // spike straight to the cap and start the sawtooth again
                if (load < smoothedLoad) {
                    smoothedLoad = load;
                } else {
                    smoothedLoad += (load - smoothedLoad) * LOAD_SMOOTHING;
                }
                if (load <= 0.0) {
                    Thread.sleep(200);
                    continue;
                }

                // target in-flight count scales with smoothed load, and we only
                // dispatch enough to top up to that target. this holds the active
                // count steady near the target instead of sawtoothing
                int target = Math.max(1, (int) Math.ceil(Config.DATA.maxActiveTasks * smoothedLoad));
                int inFlight = activeTaskCount.get();
                int budget = Math.max(0, target - inFlight);
                boolean dispatched = budget > 0 && dispatchGeneration(players, budget);

                // the throttle semaphore already paces us, blocking in dispatchBatch
                // when tasks are in flight. a bigger floor here keeps us from spamming
                // the main thread queue when there's nothing to acquire
                Thread.sleep(dispatched ? 20 : 100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private int radiusFor(DimensionState ds) {
        return ds.tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
    }

    // resend completed in-range chunks each player is missing, nearest first, joiners first
    private void runCatchup(List<ServerPlayer> players) {
        List<ServerPlayer> order = new ArrayList<>(players.size());
        for (ServerPlayer p : players) if (PlayerTracker.getInstance().needsBackfill(p.getUUID())) order.add(p);
        for (ServerPlayer p : players) if (!PlayerTracker.getInstance().needsBackfill(p.getUUID())) order.add(p);

        for (ServerPlayer player : order) {
            UUID uuid = player.getUUID();
            if (!PlayerTracker.getInstance().isModded(uuid)) continue;
            var synced = PlayerTracker.getInstance().getSyncedChunks(uuid);
            if (synced == null) continue;

            DimensionState ds = getOrSetupState((ServerLevel) player.level());
            List<ChunkPos> syncBatch = new ArrayList<>();
            // small slice per pass, each chunk serializes on the main thread so a big
            // batch is a tick spike. backfill continues over the next passes anyway
            ds.distanceGraph.collectCompletedInRange(player.chunkPosition(), radiusFor(ds), synced, syncBatch, CATCHUP_BATCH);
            if (syncBatch.isEmpty()) {
                PlayerTracker.getInstance().clearBackfill(uuid);
                continue;
            }

            // mark synced now so the next pass skips these, the send below unmarks any
            // it couldn't send so an unloaded chunk retries instead of leaving a hole
            for (ChunkPos pos : syncBatch) synced.add(Services.CHUNK_POS.packPos(pos));

            final List<ChunkPos> finalBatch = syncBatch;
            final ServerLevel level = ds.level;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null) {
                    // player gone, drop the marks so a rejoin re-syncs
                    var s = PlayerTracker.getInstance().getSyncedChunks(uuid);
                    if (s != null) for (ChunkPos pos : finalBatch) s.remove(Services.CHUNK_POS.packPos(pos));
                    return;
                }
                var s = PlayerTracker.getInstance().getSyncedChunks(uuid);
                for (ChunkPos pos : finalBatch) {
                    LevelChunk c = level.getChunkSource().getChunk(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos), false);
                    if (c != null) {
                        // sendLODData rechecks range and sets the synced flag
                        Services.NETWORK.sendLODData(p, c);
                    } else if (s != null) {
                        // not loaded, clear the mark so we try again later
                        s.remove(Services.CHUNK_POS.packPos(pos));
                    }
                }
            });
        }
    }

    // generate missing chunks nearest first, one batch per player per pass so one
    // player with a big frontier can't eat the whole budget and starve the rest
    private boolean dispatchGeneration(List<ServerPlayer> players, int budget) {
        int dispatched = 0;
        int n = players.size();
        if (n == 0) return false;

        // each player's share, rounded up so a small budget still makes progress
        int perPlayerCap = Math.max(1, (budget + n - 1) / n);

        // per player: slice left, and whether they're out of work
        int[] remainingSlice = new int[n];
        boolean[] exhausted = new boolean[n];
        java.util.Arrays.fill(remainingSlice, perPlayerCap);
        int activePlayers = n;

        // cycle until budget spent or nobody has work
        while (dispatched < budget && activePlayers > 0) {
            for (int i = 0; i < n && dispatched < budget; i++) {
                if (exhausted[i] || remainingSlice[i] <= 0) continue;

                ServerPlayer player = players.get(i);
                DimensionState ds = getOrSetupState((ServerLevel) player.level());
                int radius = radiusFor(ds);

                List<ChunkPos> batch = ds.distanceGraph.findWork(player.chunkPosition(), radius, ds.trackedBatches);
                if (batch == null) {
                    exhausted[i] = true;
                    activePlayers--;
                    continue;
                }

                int limit = Math.min(remainingSlice[i], budget - dispatched);
                int sent = dispatchBatch(ds, batch, limit);
                dispatched += sent;
                remainingSlice[i] -= sent;
                if (remainingSlice[i] <= 0) {
                    // player used up their share this pass
                    exhausted[i] = true;
                    activePlayers--;
                }
            }
        }
        return dispatched > 0;
    }

    // dispatch up to limit chunks from one batch, returns how many were sent
    private int dispatchBatch(DimensionState state, List<ChunkPos> batch, int limit) {
        ChunkPos batchHead = batch.get(0);
        long batchKey = DistanceGraph.getBatchKey(Services.CHUNK_POS.x(batchHead), Services.CHUNK_POS.z(batchHead));
        state.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

        List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
        for (ChunkPos pos : batch) {
            long key = Services.CHUNK_POS.packPos(pos);
            if (state.completedChunks.contains(key) || state.trackedChunks.contains(key)) {
                // already done or in flight, this isn't work. just drop it from the
                // batch counter, don't re-run onSuccess (which re-marks the graph and
                // inflates the skipped stat every time a boundary batch comes back)
                decrementBatch(state, pos);
            } else {
                preFiltered.add(pos);
            }
        }
        if (preFiltered.isEmpty()) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
            return 0;
        }

        List<ChunkPos> readyToGenerate = new ArrayList<>();
        int processedCount = 0;
        for (ChunkPos pos : preFiltered) {
            if (!workerRunning.get() || processedCount >= limit) break;

            boolean acquired;
            try {
                acquired = throttle.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!acquired) break;

            processedCount++;
            if (state.trackedChunks.add(Services.CHUNK_POS.packPos(pos))) {
                activeTaskCount.incrementAndGet();
                stats.incrementQueued();
                if (state.tellusActive) {
                    TellusIntegration.enqueueGenerate(state.level, pos, () -> {
                        onSuccess(state, pos);
                        completeTask(state, pos);
                    });
                } else {
                    readyToGenerate.add(pos);
                }
            } else {
                throttle.release();
                onFailure(state, pos);
            }
        }

        // release the batch tracking so findWork can return the rest
        if (processedCount < preFiltered.size()) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }

        if (!readyToGenerate.isEmpty()) {
            final DimensionState finalState = state;
            // pin the work to this session, a world reload while chunks are in flight
            // must not let old callbacks generate into or release permits on the new one
            final int dispatchSession = sessionId.get();
            final MinecraftServer dispatchServer = server;
            dispatchServer.execute(() -> {
                if (sessionId.get() != dispatchSession) return;
                ServerChunkCache cache = finalState.level.getChunkSource();
                List<ChunkPos> actuallyGenerate = new ArrayList<>();
                for (ChunkPos pos : readyToGenerate) {
                    if (finalState.level.hasChunk(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos))) {
                        LevelChunk existingChunk = finalState.level.getChunk(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));
                        if (existingChunk != null && !existingChunk.isEmpty()) {
                            VoxyIntegration.ingestChunk(existingChunk);
                            Services.NETWORK.broadcastLODData(existingChunk);
                        }
                        onSuccess(finalState, pos);
                        completeTask(finalState, pos);
                    } else {
                        queueTicketAdd(finalState.level, pos);
                        actuallyGenerate.add(pos);
                    }
                }
                if (!actuallyGenerate.isEmpty()) {
                    processPendingTickets();
                    for (ChunkPos pos : actuallyGenerate) {
                        ((ServerChunkCacheMixin) cache).invokeGetChunkFutureMainThread(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos), ChunkStatus.FULL, true)
                            .whenCompleteAsync((result, throwable) -> {
                                if (sessionId.get() != dispatchSession) return;
                                if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                    onSuccess(finalState, pos);
                                    if (!chunk.isEmpty()) {
                                        VoxyIntegration.ingestChunk(chunk);
                                        Services.NETWORK.broadcastLODData(chunk);
                                    }
                                } else {
                                    onFailure(finalState, pos);
                                }
                                cleanupTask(finalState.level, pos);
                            }, dispatchServer);
                    }
                }
            });
        }
        return processedCount;
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

        // drop far-away synced entries now and then so the set can't grow forever
        if (++syncPruneCounter >= SYNC_PRUNE_INTERVAL_TICKS) {
            syncPruneCounter = 0;
            pruneSyncedChunks();
        }

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

            // on a dim change, treat it like a big move so we rescan and backfill
            if (PlayerTracker.getInstance().handleDimensionChange(player)) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
                continue;
            }

            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
            }
        }
        
        // only switch level when another has strictly more players
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

    // drop synced entries well outside any player range, 2x margin avoids boundary churn
    private void pruneSyncedChunks() {
        var players = new ArrayList<>(PlayerTracker.getInstance().getPlayers());
        if (players.isEmpty()) return;

        long marginChunks = (long) Config.DATA.generationRadius * 2L;
        long marginSq = marginChunks * marginChunks;

        for (ServerPlayer player : players) {
            var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
            if (synced == null) continue;
            ChunkPos center = player.chunkPosition();

            synchronized (synced) {
                var it = synced.iterator();
                while (it.hasNext()) {
                    long key = it.nextLong();
                    int cx = ChunkPos.getX(key);
                    int cz = ChunkPos.getZ(key);
                    long dx = cx - Services.CHUNK_POS.x(center);
                    long dz = cz - Services.CHUNK_POS.z(center);
                    if (dx * dx + dz * dz > marginSq) {
                        it.remove();
                    }
                }
            }
        }
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = Services.CHUNK_POS.x(a) - Services.CHUNK_POS.x(b);
        int dz = Services.CHUNK_POS.z(a) - Services.CHUNK_POS.z(b);
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
        var players = moddedPlayers();
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
                // ticket api differs per mc version so go through ChunkPosCompat
                Services.CHUNK_POS.addForcedTicket(cache, op.pos());
                appliedTickets.computeIfAbsent(op.level(), k -> LongSets.synchronize(new LongOpenHashSet())).add(Services.CHUNK_POS.packPos(op.pos()));
            } else {
                Services.CHUNK_POS.removeForcedTicket(cache, op.pos());
                LongSet set = appliedTickets.get(op.level());
                if (set != null) set.remove(Services.CHUNK_POS.packPos(op.pos()));
            }
            modifiedLevels.add(op.level());
        }
        for (ServerLevel level : modifiedLevels) {
            ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates();
        }
    }
    
    // removes every forced ticket we applied, so no chunk stays pinned after shutdown
    private void releaseAllTickets() {
        // drop queued ops first so we don't re-add a ticket we're about to remove
        pendingTicketOps.clear();

        for (var entry : appliedTickets.entrySet()) {
            ServerLevel level = entry.getKey();
            LongSet positions = entry.getValue();
            ServerChunkCache cache = level.getChunkSource();
            synchronized (positions) {
                var it = positions.iterator();
                while (it.hasNext()) {
                    long key = it.nextLong();
                    ChunkPos pos = Services.CHUNK_POS.unpack(key);
                    Services.CHUNK_POS.removeForcedTicket(cache, pos);
                }
            }
            ((ServerChunkCacheMixin) cache).invokeRunDistanceManagerUpdates();
        }
        appliedTickets.clear();
    }

    private void queueTicketAdd(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, true));
    }
    
    private void queueTicketRemove(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, false));
    }
    
    private void cleanupTask(ServerLevel level, ChunkPos pos) {
        queueTicketRemove(level, pos);
        // old emptyTicks reset is gone, that field isn't here and the pause check covers it
        DimensionState state = dimensionStates.get(level.dimension());
        if (state != null) completeTask(state, pos);
    }

    private void onSuccess(DimensionState state, ChunkPos pos) {
        long key = Services.CHUNK_POS.packPos(pos);
        state.failCounts.remove(key);
        if (state.completedChunks.add(key)) {
            stats.incrementCompleted();
            state.distanceGraph.markChunkCompleted(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));
            state.remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
            state.distanceGraph.markChunkCompleted(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));
        }
        decrementBatch(state, pos);
    }

    private void onFailure(DimensionState state, ChunkPos pos) {
        stats.incrementFailed();
        long key = Services.CHUNK_POS.packPos(pos);
        int fails = state.failCounts.merge(key, 1, Integer::sum);
        // after a few fails mark it done so findWork stops looping on the same batch
        if (fails >= MAX_CHUNK_RETRIES) {
            state.failCounts.remove(key);
            state.distanceGraph.markChunkCompleted(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));
            state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
        }
        decrementBatch(state, pos);
    }

    private void decrementBatch(DimensionState state, ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos));
        AtomicInteger counter = state.batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }
    }
    
    private void completeTask(DimensionState state, ChunkPos pos) {
        if (state.trackedChunks.remove(Services.CHUNK_POS.packPos(pos))) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }

    // a player just acked the handshake, rescan so generation starts around them
    // without waiting for them to move
    public void onPlayerModded() {
        if (!running.get() || server == null) return;
        server.execute(this::restartScan);
    }
    
    public boolean isChunkCompleted(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        DimensionState state = dimensionStates.get(level.dimension());
        return state != null && state.completedChunks.contains(Services.CHUNK_POS.packPos(pos));
    }

    // online players that have the client mod, the only ones we generate around
    private List<ServerPlayer> moddedPlayers() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : PlayerTracker.getInstance().getPlayers()) {
            if (PlayerTracker.getInstance().isModded(p.getUUID())) out.add(p);
        }
        return out;
    }

    // returns a new list rotated so element at offset comes first (fair iteration)
    private static <T> List<T> rotated(List<T> list, int offset) {
        int n = list.size();
        if (n <= 1) return list;
        offset = ((offset % n) + n) % n;
        if (offset == 0) return list;
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(list.get((i + offset) % n));
        }
        return out;
    }

    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() {
        if (currentDimensionKey == null) return 0;
        DimensionState state = dimensionStates.get(currentDimensionKey);
        return state != null ? state.remainingInRadius.get() : 0; 
    }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    // chunk gen tasks currently in flight (acquired the throttle, not yet completed)
    public int getQueueSize() { return activeTaskCount.get(); }
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
