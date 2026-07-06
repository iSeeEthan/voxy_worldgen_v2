package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

public final class DebugRenderer implements DebugScreenEntry {

    public static final Identifier ID = Identifier.parse("voxyworldgenv2:stats");
    public static final DebugRenderer INSTANCE = new DebugRenderer();

    private static final DebugEntryCategory CATEGORY = new DebugEntryCategory(
        Component.literal("Voxy WorldGen"), 900.0f
    );

    private DebugRenderer() {}

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk chunk, LevelChunk chunk2) {
        if (level == null) return;
        Minecraft mc = Minecraft.getInstance();
        ChunkGenerationManager manager = ChunkGenerationManager.getInstance();
        GenerationStats stats = manager.getStats();

        double rate = stats.getChunksPerSecond();
        int remaining = manager.getRemainingInRadius();

        String eta = "--";
        if (rate > 0.1 && remaining > 0) {
            int seconds = (int) (remaining / rate);
            if (seconds < 60) {
                eta = seconds + "s";
            } else if (seconds < 3600) {
                eta = (seconds / 60) + "m " + (seconds % 60) + "s";
            } else {
                eta = (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            }
        } else if (remaining == 0) {
            eta = "done";
        }

        List<String> lines = new ArrayList<>();

        boolean isLocal = mc.getSingleplayerServer() != null;
        boolean isVoxyServer = com.ethan.voxyworldgenv2.network.NetworkState.isServerConnected();

        if (isLocal) {
            String status = manager.isThrottled() ? "throttled" : remaining == 0 ? "done" : "running";
            lines.add("status: " + status);
            lines.add("completed: " + formatNumber(stats.getCompleted()));
            lines.add("skipped: " + formatNumber(stats.getSkipped()));
            lines.add("remaining: " + formatNumber(remaining) + " (" + eta + ")");
            lines.add("active: " + manager.getActiveTaskCount());
            lines.add("rate: " + String.format("%.1f", rate) + " c/s");
            lines.add("voxy: " + (VoxyIntegration.isVoxyAvailable() ? "enabled" : "disabled"));
        } else if (isVoxyServer) {
            double netRate = com.ethan.voxyworldgenv2.network.NetworkState.getReceiveRate();
            double bwRate = com.ethan.voxyworldgenv2.network.NetworkState.getBandwidthRate();
            lines.add("mode: multiplayer (connected)");
            lines.add("rate: " + String.format("%.1f", netRate) + " c/s");
            lines.add("bandwidth: " + formatBytes((long) bwRate) + "/s");
            lines.add("received: " + formatNumber(com.ethan.voxyworldgenv2.network.NetworkState.getChunksReceived())
                + " (" + formatBytes(com.ethan.voxyworldgenv2.network.NetworkState.getBytesReceived()) + ")");
            lines.add("voxy: " + (VoxyIntegration.isVoxyAvailable() ? "enabled" : "disabled"));
        } else {
            lines.add("mode: multiplayer (no voxy server)");
            lines.add("voxy: " + (VoxyIntegration.isVoxyAvailable() ? "enabled" : "disabled"));
        }

        displayer.addToGroup(ID, lines);
    }

    @Override
    public DebugEntryCategory category() {
        return CATEGORY;
    }

    private static String formatNumber(long number) {
        if (number >= 1_000_000) return String.format("%.1fM", number / 1_000_000.0);
        if (number >= 1_000) return String.format("%.1fK", number / 1_000.0);
        return String.valueOf(number);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }
}
