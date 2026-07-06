package com.ethan.voxyworldgenv2.network;

import java.util.concurrent.atomic.AtomicLong;

public class NetworkState {
    private static boolean serverConnected = false;
    private static final AtomicLong chunksReceived = new AtomicLong(0);
    private static final AtomicLong bytesReceived = new AtomicLong(0);
    
    private static double receiveRate = 0; // chunks/s
    private static double bandwidthRate = 0; // bytes/s
    
    private static long lastUpdateTime = 0;
    private static long lastChunkCount = 0;
    private static long lastByteCount = 0;

    public static void setServerConnected(boolean connected) {
        serverConnected = connected;
        if (!connected) {
            chunksReceived.set(0);
            bytesReceived.set(0);
            receiveRate = 0;
            bandwidthRate = 0;
            lastUpdateTime = 0;
            lastChunkCount = 0;
            lastByteCount = 0;
        }
    }

    public static boolean isServerConnected() {
        return serverConnected;
    }

    public static void incrementReceived(long bytes) {
        chunksReceived.incrementAndGet();
        bytesReceived.addAndGet(bytes);
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (lastUpdateTime == 0) {
            lastUpdateTime = now;
            lastChunkCount = chunksReceived.get();
            lastByteCount = bytesReceived.get();
            return;
        }

        long delta = now - lastUpdateTime;
        if (delta >= 1000) {
            long currentChunkCount = chunksReceived.get();
            long currentByteCount = bytesReceived.get();
            
            double seconds = delta / 1000.0;
            receiveRate = (currentChunkCount - lastChunkCount) / seconds;
            bandwidthRate = (currentByteCount - lastByteCount) / seconds;
            
            lastChunkCount = currentChunkCount;
            lastByteCount = currentByteCount;
            lastUpdateTime = now;
        }
    }

    public static double getReceiveRate() {
        return receiveRate;
    }

    public static double getBandwidthRate() {
        return bandwidthRate;
    }

    public static long getChunksReceived() {
        return chunksReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }
}
