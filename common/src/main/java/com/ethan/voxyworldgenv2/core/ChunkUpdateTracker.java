package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.platform.Services;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// tracks which chunk sections changed so we only resend the ones that did
public class ChunkUpdateTracker {
    private static final ChunkUpdateTracker INSTANCE = new ChunkUpdateTracker();

    // dimension -> (chunk key -> set of dirty section y-levels)
    private final Map<ResourceKey<Level>, Map<Long, IntSet>> dirty = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, Long> lastProcessTimes = new ConcurrentHashMap<>();

    // cap chunks handled per cycle so a burst of activity (tnt, fluids, fire) can't spike the tick
    private static final int MAX_CHUNKS_PER_CYCLE = 64;
    // floor so update_interval=1 can't hammer the tick
    private static final long MIN_PROCESS_INTERVAL_MS = 500;

    // how often to flush dirty sections, driven by update_interval (ticks)
    private static long processIntervalMs() {
        return Math.max(MIN_PROCESS_INTERVAL_MS, Config.DATA.update_interval * 50L);
    }

    private ChunkUpdateTracker() {}

    public static ChunkUpdateTracker getInstance() {
        return INSTANCE;
    }

    // mark the section containing blockY dirty for this chunk
    public void markDirty(LevelChunk chunk, int blockY) {
        // this fires for every block change (fluids, fire, redstone), so bail before
        // any map work when nobody can receive a resend
        if (!PlayerTracker.getInstance().anyModded()) return;

        long key = Services.CHUNK_POS.packPos(chunk.getPos());
        Map<Long, IntSet> levelDirty = dirty.computeIfAbsent(chunk.getLevel().dimension(), k -> new ConcurrentHashMap<>());

        // cap the backlog so a redstone/fluid storm can't grow it forever, a dropped
        // chunk gets re-marked next sweep over it
        int cap = Config.DATA.maxQueueSize;
        if (cap > 0 && !levelDirty.containsKey(key) && levelDirty.size() >= cap) return;

        int sectionY = SectionPos.blockToSectionCoord(blockY);
        IntSet set = levelDirty.computeIfAbsent(key, k -> new IntOpenHashSet());
        synchronized (set) {
            set.add(sectionY);
        }
    }

    public void processDirty(ServerLevel level) {
        if (level == null) return;

        Map<Long, IntSet> levelDirty = dirty.get(level.dimension());
        if (levelDirty == null || levelDirty.isEmpty()) return;

        long now = System.currentTimeMillis();
        long lastTime = lastProcessTimes.getOrDefault(level.dimension(), 0L);
        if (now - lastTime < processIntervalMs()) return;
        lastProcessTimes.put(level.dimension(), now);

        double maxDistSq = Services.NETWORK.syncRadiusSq();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : PlayerTracker.getInstance().getPlayers()) {
            if (p.level() == level) players.add(p);
        }

        int processed = 0;
        Iterator<Map.Entry<Long, IntSet>> it = levelDirty.entrySet().iterator();
        while (it.hasNext() && processed < MAX_CHUNKS_PER_CYCLE) {
            Map.Entry<Long, IntSet> entry = it.next();
            it.remove();

            long posLong = entry.getKey();
            IntSet src = entry.getValue();
            ChunkPos pos = Services.CHUNK_POS.unpack(posLong);

            // skip chunks no player is near, broadcast still gates per player
            if (!anyPlayerNear(players, pos, maxDistSq)) continue;

            // snapshot the dirty set so a concurrent markDirty can't mutate it mid-read
            IntSet sectionYs;
            synchronized (src) {
                sectionYs = new IntOpenHashSet(src);
            }

            LevelChunk chunk = level.getChunkSource().getChunk(Services.CHUNK_POS.x(pos), Services.CHUNK_POS.z(pos), false);
            if (chunk != null) {
                Services.NETWORK.broadcastLODData(chunk, sectionYs);
                processed++;
            }
        }
    }

    private static boolean anyPlayerNear(List<ServerPlayer> players, ChunkPos pos, double maxDistSq) {
        for (ServerPlayer p : players) {
            double dx = p.getX() - pos.getMiddleBlockX();
            double dz = p.getZ() - pos.getMiddleBlockZ();
            if (dx * dx + dz * dz <= maxDistSq) return true;
        }
        return false;
    }
}
