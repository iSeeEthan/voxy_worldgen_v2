package com.ethan.voxyworldgenv2.network;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys.Client;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;


import org.apache.logging.log4j.Level;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.io.File;

import java.io.BufferedOutputStream;

public class NetworkState {
    private static boolean serverConnected = false;
    private static final AtomicLong chunksReceived = new AtomicLong(0);
    private static final AtomicLong bytesReceived = new AtomicLong(0);
    
    private static double receiveRate = 0; // chunks/s
    private static double bandwidthRate = 0; // bytes/s
    
    private static long lastUpdateTime = 0;
    private static long lastChunkCount = 0;
    private static long lastByteCount = 0;

    public static void save() {
        Path savePath = savePath();
        VoxyWorldGenV2.LOGGER.info("trying to save network state to path: " + savePath);

        File clientDirectory = new File(FabricLoader.getInstance().getGameDir().resolve(".voxyworldgen").toUri());
        VoxyWorldGenV2.LOGGER.info("does client directory exist: " + clientDirectory.exists());
        VoxyWorldGenV2.LOGGER.info("client directory: " + clientDirectory.toString());
        if (!clientDirectory.exists()) {
            VoxyWorldGenV2.LOGGER.info("Voxy world gen client directory does not exist, attempting to create it: " + clientDirectory);
            try {
                if (!clientDirectory.mkdir()) {
                    VoxyWorldGenV2.LOGGER.error("could not create client directory");
                }
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("could not create client directory bad", e);
            }
        }

        File stateFile = new File(savePath.toUri());
        if (!stateFile.exists()) {
            try {
                if (!stateFile.createNewFile()) {
                    VoxyWorldGenV2.LOGGER.error("could not create network state file");
                }
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("could not create network state file", e);
            }
        }

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(savePath, StandardOpenOption.CREATE)))) {
            out.writeLong(chunksReceived.get());
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to save network state", e);
        }
    }

    public static void load() {
        Path savePath = savePath();
        VoxyWorldGenV2.LOGGER.info("trying to load network state from path: " + savePath);

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(savePath)))) {
            Long toSetChunks = in.readLong();
            chunksReceived.set(toSetChunks);
            Long hello = chunksReceived.get();
            VoxyWorldGenV2.LOGGER.info("tried to set Chunks recieved to: " + toSetChunks.toString() + " it is now set to: " + hello.toString());
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to load network state", e);
        }
    }

    private static Path savePath() {
        String worldName = null;
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            worldName = Minecraft.getInstance().getSingleplayerServer().name();
        } else {
            worldName = Minecraft.getInstance().getCurrentServer().ip;
        }

        return FabricLoader.getInstance().getGameDir().resolve(".voxyworldgen/" + worldName + ".bin");
    }


    public static void setServerConnected(boolean connected) {
        VoxyWorldGenV2.LOGGER.info("setting server connected state to: " + connected);

        serverConnected = connected;
        if (!connected) {
            save();
            chunksReceived.set(0);
            bytesReceived.set(0);
            receiveRate = 0;
            bandwidthRate = 0;
            lastUpdateTime = 0;
            lastChunkCount = 0;
            lastByteCount = 0;
        } else if (connected) {
            load();
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
