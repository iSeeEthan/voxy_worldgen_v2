package com.ethan.voxyworldgenv2.core;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();
    private final Set<ServerPlayer> players;
    private final java.util.Map<UUID, LongSet> syncedChunks;
    // players that acked the handshake, we only send lod data to these
    private final Set<UUID> moddedPlayers;
    // recently joined players that still need a backfill
    private final Set<UUID> needsBackfill;
    // last dimension per player, to spot a dim change
    private final Map<UUID, ResourceKey<Level>> lastDimension;

    private PlayerTracker() {
        this.players = ConcurrentHashMap.newKeySet();
        this.syncedChunks = new ConcurrentHashMap<>();
        this.moddedPlayers = ConcurrentHashMap.newKeySet();
        this.needsBackfill = ConcurrentHashMap.newKeySet();
        this.lastDimension = new ConcurrentHashMap<>();
    }

    public static PlayerTracker getInstance() {
        return INSTANCE;
    }

    public void addPlayer(ServerPlayer player) {
        players.add(player);
        syncedChunks.put(player.getUUID(), LongSets.synchronize(new LongOpenHashSet()));
        // nothing synced yet so flag for backfill
        needsBackfill.add(player.getUUID());
        lastDimension.put(player.getUUID(), player.level().dimension());
    }

    public void removePlayer(ServerPlayer player) {
        players.remove(player);
        syncedChunks.remove(player.getUUID());
        moddedPlayers.remove(player.getUUID());
        needsBackfill.remove(player.getUUID());
        lastDimension.remove(player.getUUID());
    }

    public void clear() {
        players.clear();
        syncedChunks.clear();
        moddedPlayers.clear();
        needsBackfill.clear();
        lastDimension.clear();
    }

    // synced set has no dimension in the key, so wipe it on a dim change or a stale
    // entry could block a send at the same coords in the new world, true if changed
    public boolean handleDimensionChange(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ResourceKey<Level> current = player.level().dimension();
        ResourceKey<Level> previous = lastDimension.put(uuid, current);
        if (previous != null && !previous.equals(current)) {
            clearSynced(uuid);
            needsBackfill.add(uuid);
            return true;
        }
        return false;
    }

    public Collection<ServerPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players);
    }

    public LongSet getSyncedChunks(UUID uuid) {
        return syncedChunks.get(uuid);
    }

    public int getPlayerCount() {
        return players.size();
    }

    public void setModded(UUID uuid, boolean modded) {
        if (modded) {
            moddedPlayers.add(uuid);
        } else {
            moddedPlayers.remove(uuid);
        }
    }

    public boolean isModded(UUID uuid) {
        return moddedPlayers.contains(uuid);
    }

    // any online player acked the handshake
    public boolean anyModded() {
        return !moddedPlayers.isEmpty();
    }

    // cheap dedup before we serialize sections
    public boolean isSynced(UUID uuid, long packedChunkPos) {
        LongSet synced = syncedChunks.get(uuid);
        return synced != null && synced.contains(packedChunkPos);
    }

    // drop every synced entry for a player
    public void clearSynced(UUID uuid) {
        LongSet synced = syncedChunks.get(uuid);
        if (synced != null) synced.clear();
    }

    public boolean needsBackfill(UUID uuid) {
        return needsBackfill.contains(uuid);
    }

    public void clearBackfill(UUID uuid) {
        needsBackfill.remove(uuid);
    }
}
