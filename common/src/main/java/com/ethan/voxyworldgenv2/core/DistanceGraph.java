package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.platform.Services;
import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

// tracks chunk generation state in a 4-level tree, batch up to 2048x2048
public class DistanceGraph {
    private static final int BATCH_SIZE_SHIFT = 2;
    private static final int NODE_SIZE_BITS = 3;
    private static final int ROOT_SIZE_SHIFT = 9;
    
    private final Map<Long, Node> roots = new ConcurrentHashMap<>();

    private static class Node {
        final int level;
        final int x, z; // level-space coords
        volatile long fullMask = 0;
        final Map<Integer, Object> children = new ConcurrentHashMap<>();

        Node(int level, int x, int z) {
            this.level = level;
            this.x = x;
            this.z = z;
        }

        boolean isFull() { return fullMask == -1L; }
    }

    public void markChunkCompleted(int cx, int cz) {
        int bx = cx >> BATCH_SIZE_SHIFT;
        int bz = cz >> BATCH_SIZE_SHIFT;
        int bit = (cx & 3) + ((cz & 3) << 2);

        int rx = bx >> ROOT_SIZE_SHIFT;
        int rz = bz >> ROOT_SIZE_SHIFT;
        long rootKey = Services.CHUNK_POS.asLong(rx, rz);

        Node root = roots.computeIfAbsent(rootKey, k -> new Node(3, rx, rz));
        recursiveMark(root, bx, bz, bit);
    }

    private void recursiveMark(Node node, int bx, int bz, int bit) {
        int idx = getLocalIndex(node.level, bx, bz);
        if ((node.fullMask & (1L << idx)) != 0) return;

        if (node.level == 1) {
            Integer mask = (Integer) node.children.getOrDefault(idx, 0);
            mask |= (1 << bit);
            if (mask == 0xFFFF) {
                synchronized(node) {
                    node.fullMask |= (1L << idx);
                    node.children.remove(idx);
                }
            } else {
                node.children.put(idx, mask);
            }
        } else {
            Node child = (Node) node.children.computeIfAbsent(idx, k -> {
                int cx = (node.x << NODE_SIZE_BITS) + (k & 0x7);
                int cz = (node.z << NODE_SIZE_BITS) + (k >> 3);
                return new Node(node.level - 1, cx, cz);
            });
            recursiveMark(child, bx, bz, bit);
            if (child.isFull()) {
                synchronized(node) {
                    node.fullMask |= (1L << idx);
                    node.children.remove(idx);
                }
            }
        }
    }

    public List<ChunkPos> findWork(ChunkPos center, int radiusChunks, Set<Long> trackedBatches) {
        int cbx = Services.CHUNK_POS.x(center) >> BATCH_SIZE_SHIFT;
        int cbz = Services.CHUNK_POS.z(center) >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        // chunk-space center + radius so we can drop out-of-range chunks from a
        // boundary batch, otherwise that batch never fills and gets re-pulled forever
        int ccx = Services.CHUNK_POS.x(center);
        int ccz = Services.CHUNK_POS.z(center);
        long radiusSq = (long) radiusChunks * radiusChunks;

        PriorityQueue<WorkItem> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> i.distSq));

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(Services.CHUNK_POS.asLong(rx, rz));
                // check empty space even if node is null
                double dSq = getDistSq(rx, rz, rootSize, cbx, cbz);
                if (dSq <= (double)rb * rb) {
                    queue.add(new WorkItem(root, 3, rx, rz, dSq));
                }
            }
        }

        while (!queue.isEmpty()) {
            WorkItem item = queue.poll();
            if (item.node != null && item.node.isFull()) continue;

            if (item.level == 0) {
                long key = Services.CHUNK_POS.asLong(item.x, item.z);
                if (trackedBatches.add(key)) {
                    List<ChunkPos> batch = new ArrayList<>(16);
                    for (int lz = 0; lz < 4; lz++) {
                        for (int lx = 0; lx < 4; lx++) {
                            int chunkX = (item.x << 2) + lx;
                            int chunkZ = (item.z << 2) + lz;
                            long dx = chunkX - ccx;
                            long dz = chunkZ - ccz;
                            // only include chunks actually inside the radius
                            if (dx * dx + dz * dz <= radiusSq) {
                                batch.add(new ChunkPos(chunkX, chunkZ));
                            }
                        }
                    }
                    // whole batch was out of range, untrack and keep looking
                    if (batch.isEmpty()) {
                        trackedBatches.remove(key);
                        continue;
                    }
                    return batch;
                }
                continue;
            }

            int childLevel = item.level - 1;
            int childSize = 1 << (3 * childLevel);
            
            for (int i = 0; i < 64; i++) {
                if (item.node != null && (item.node.fullMask & (1L << i)) != 0) continue;

                int cx = (item.x << 3) + (i & 7);
                int cz = (item.z << 3) + (i >> 3);
                
                double dSq = getDistSq(cx, cz, childSize, cbx, cbz);
                if (dSq <= (double)rb * rb) {
                    Object child = (item.node == null) ? null : item.node.children.get(i);
                    Node childNode = (child instanceof Node) ? (Node) child : null;
                    queue.add(new WorkItem(childNode, childLevel, cx, cz, dSq));
                }
            }
        }
        return null;
    }

    private double getDistSq(int nx, int nz, int size, int cbx, int cbz) {
        // distance to nearest edge of node
        double dx = Math.max(0, Math.max((double)nx * size - cbx, (double)cbx - (nx + 1) * size + 1));
        double dz = Math.max(0, Math.max((double)nz * size - cbz, (double)cbz - (nz + 1) * size + 1));
        return dx * dx + dz * dz;
    }

    private int getLocalIndex(int level, int bx, int bz) {
        int shift = (level - 1) * 3;
        int lx = (bx >> shift) & 7;
        int lz = (bz >> shift) & 7;
        return lx + (lz << 3);
    }

    public int countMissingInRange(ChunkPos center, int radiusChunks) {
        int cbx = Services.CHUNK_POS.x(center) >> BATCH_SIZE_SHIFT;
        int cbz = Services.CHUNK_POS.z(center) >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int count = 0;
        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(Services.CHUNK_POS.asLong(rx, rz));
                count += recursiveCount(root, 3, rx, rz, cbx, cbz, rb);
            }
        }
        return count;
    }

    public void collectCompletedInRange(ChunkPos center, int radiusChunks, it.unimi.dsi.fastutil.longs.LongSet alreadySynced, List<ChunkPos> out, int maxResults) {
        int cbx = Services.CHUNK_POS.x(center) >> BATCH_SIZE_SHIFT;
        int cbz = Services.CHUNK_POS.z(center) >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        // use a priority queue to process chunks from nearest to farthest
        PriorityQueue<CollectItem> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> i.distSq));

        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        double maxDistSq = (double) rb * rb;

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(Services.CHUNK_POS.asLong(rx, rz));
                if (root == null) continue;
                
                double dSq = getDistSq(rx, rz, rootSize, cbx, cbz);
                if (dSq <= maxDistSq) {
                    queue.add(new CollectItem(root, false, 0, 3, rx, rz, dSq));
                }
            }
        }

        while (!queue.isEmpty() && out.size() < maxResults) {
            CollectItem item = queue.poll();
            
            if (item.level == 0) {
                int mask = item.isVirtualFull ? 0xFFFF : item.mask;
                for (int i = 0; i < 16; i++) {
                    if ((mask & (1 << i)) != 0) {
                        int lx = i & 3;
                        int lz = i >> 2;
                        ChunkPos pos = new ChunkPos((item.x << 2) + lx, (item.z << 2) + lz);
                        if (!alreadySynced.contains(Services.CHUNK_POS.packPos(pos))) {
                            out.add(pos);
                            if (out.size() >= maxResults) return;
                        }
                    }
                }
                continue;
            }
            
            int childLevel = item.level - 1;
            int childSize = 1 << (3 * childLevel);
            
            for (int i = 0; i < 64; i++) {
                int cx = (item.x << 3) + (i & 7);
                int cz = (item.z << 3) + (i >> 3);
                
                double dSq = getDistSq(cx, cz, childSize, cbx, cbz);
                if (dSq > maxDistSq) continue;
                
                if (item.isVirtualFull || (item.node != null && (item.node.fullMask & (1L << i)) != 0)) {
                   queue.add(new CollectItem(null, true, 0xFFFF, childLevel, cx, cz, dSq));
                   continue;
                }
                
                if (item.node == null) continue;
                Object child = item.node.children.get(i);
                if (child == null) continue;
                
                if (childLevel == 0) {
                    if (child instanceof Integer mask) {
                        queue.add(new CollectItem(null, false, mask, 0, cx, cz, dSq));
                    }
                } else if (child instanceof Node childNode) {
                    queue.add(new CollectItem(childNode, false, 0, childLevel, cx, cz, dSq));
                }
            }
        }
    }
    
    private record CollectItem(Node node, boolean isVirtualFull, int mask, int level, int x, int z, double distSq) {}

    private int recursiveCount(Node node, int level, int nx, int nz, int cbx, int cbz, int rb) {
        int size = 1 << (3 * level);
        if (getDistSq(nx, nz, size, cbx, cbz) > (double)rb * rb) return 0;
        if (node != null && node.isFull()) return 0;

        if (level == 0) return 1; // batch

        if (node == null) {
            // estimate chunks in circle inside empty node
            if (level == 1) {
                int c = 0;
                for (int i = 0; i < 64; i++) {
                    int bx = (nx << 3) + (i & 7);
                    int bz = (nz << 3) + (i >> 3);
                    if (getDistSq(bx, bz, 1, cbx, cbz) <= (double)rb * rb) c += 16;
                }
                return c;
            }
            // higher level, recurse null node
            int c = 0;
            for (int i = 0; i < 64; i++) {
                int cx = (nx << 3) + (i & 7);
                int cz = (nz << 3) + (i >> 3);
                c += recursiveCount(null, level - 1, cx, cz, cbx, cbz, rb);
            }
            return c;
        }

        // l1 partial
        if (level == 1) {
            int c = 0;
            for (int i = 0; i < 64; i++) {
                if ((node.fullMask & (1L << i)) != 0) continue;
                int bx = (nx << 3) + (i & 7);
                int bz = (nz << 3) + (i >> 3);
                if (getDistSq(bx, bz, 1, cbx, cbz) <= (double)rb * rb) {
                    Integer mask = (Integer) node.children.getOrDefault(i, 0);
                    c += (16 - Integer.bitCount(mask));
                }
            }
            return c;
        }

        // higher level partial
        int c = 0;
        for (int i = 0; i < 64; i++) {
            if ((node.fullMask & (1L << i)) != 0) continue;
            int cx = (nx << 3) + (i & 7);
            int cz = (nz << 3) + (i >> 3);
            Object child = node.children.get(i);
            Node childNode = (child instanceof Node) ? (Node) child : null;
            c += recursiveCount(childNode, level - 1, cx, cz, cbx, cbz, rb);
        }
        return c;
    }



    private static class WorkItem {
        final Node node;
        final int level;
        final int x, z;
        final double distSq;
        WorkItem(Node node, int level, int x, int z, double distSq) {
            this.node = node; this.level = level; this.x = x; this.z = z; this.distSq = distSq;
        }
    }

    public static long getBatchKey(int cx, int cz) {
        return Services.CHUNK_POS.asLong(cx >> 2, cz >> 2);
    }
}
