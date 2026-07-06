package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class DebugRenderer {
    
    private DebugRenderer() {}
    
    public static void render(GuiGraphics graphics, DeltaTracker tickDelta) {
        if (!com.ethan.voxyworldgenv2.core.Config.DATA.showF3MenuStats) return;
        
        Minecraft mc = Minecraft.getInstance();
        
        if (!mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        
        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int lineHeight = font.lineHeight + 2;
        
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
        
        String status;
        if (manager.isThrottled()) {
            status = "§cthrottled (low tps)";
        } else if (remaining == 0) {
            status = "§adone";
        } else {
            status = "§arunning";
        }
        
        boolean isLocal = mc.getSingleplayerServer() != null;
        boolean isVoxyServer = com.ethan.voxyworldgenv2.network.NetworkState.isServerConnected();
        
        java.util.List<String> lineList = new java.util.ArrayList<>();
        
        if (isLocal) {
            // singleplayer
            lineList.add("§6[voxy worldgen v2] " + status);
            lineList.add("§7completed: §a" + formatNumber(stats.getCompleted()));
            lineList.add("§7skipped: §f" + formatNumber(stats.getSkipped()));
            lineList.add("§7remaining: §e" + formatNumber(remaining) + " §8(" + eta + ")");
            lineList.add("§7active: §b" + manager.getActiveTaskCount());
            lineList.add("§7rate: §f" + String.format("%.1f", rate) + " c/s");
            lineList.add("§7voxy: " + (VoxyIntegration.isVoxyAvailable() ? "§aenabled" : "§cdisabled"));
        } else if (isVoxyServer) {
            // multiplayer
            double netRate = com.ethan.voxyworldgenv2.network.NetworkState.getReceiveRate();
            double bwRate = com.ethan.voxyworldgenv2.network.NetworkState.getBandwidthRate();
            lineList.add("§6[voxy worldgen v2] §aconnected");
            lineList.add("§7rate: §f" + String.format("%.1f", netRate) + " c/s");
            lineList.add("§7bandwidth: §f" + formatBytes((long) bwRate) + "/s");
            lineList.add("§7received: §b" + formatNumber(com.ethan.voxyworldgenv2.network.NetworkState.getChunksReceived()) + " §8(" + formatBytes(com.ethan.voxyworldgenv2.network.NetworkState.getBytesReceived()) + ")");
            lineList.add("§7voxy: " + (VoxyIntegration.isVoxyAvailable() ? "§aenabled" : "§cdisabled"));
        } else {
            // multiplayer, no voxy server
            lineList.add("§6[voxy worldgen v2] §7voxy-server: §coffline");
            lineList.add("§7voxy: " + (VoxyIntegration.isVoxyAvailable() ? "§aenabled" : "§cdisabled"));
        }
        
        String[] lines = lineList.toArray(new String[0]);
        
        int y = screenHeight - (lines.length * lineHeight) - 4;
        
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }
        
        for (String line : lines) {
            int x = screenWidth - font.width(line) - 4;
            int bgX = screenWidth - maxWidth - 6;
            
            graphics.fill(bgX, y - 1, screenWidth - 2, y + font.lineHeight, 0x90505050);
            graphics.drawString(font, line, x, y, 0xFFFFFFFF, false);
            
            y += lineHeight;
        }
    }
    
    private static String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }
}
