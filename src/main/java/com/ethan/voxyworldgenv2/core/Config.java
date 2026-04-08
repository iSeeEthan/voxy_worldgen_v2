package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxyworldgenv2.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static volatile ConfigData DATA = new ConfigData();
    
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            // auto-configure for first run
            int cores = Runtime.getRuntime().availableProcessors();
            long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024); // mb
            
            // scale based on hardware
            DATA.maxActiveTasks = 20;
            DATA.generationRadius = 128; // standard default
            
            save();
            return;
        }
        
        try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded == null) {
                restoreDefaultsAndSave("config file was empty, restoring defaults", null);
                return;
            }
            loaded.normalize();
            DATA = loaded;
        } catch (JsonParseException | IllegalStateException e) {
            restoreDefaultsAndSave("failed to parse config, restoring defaults", e);
        } catch (IOException e) {
            // Keep the current in-memory config on pure I/O failures.
            VoxyWorldGenV2.LOGGER.error("failed to load config (io error), keeping current config", e);
        } catch (Exception e) {
            restoreDefaultsAndSave("failed to load config, restoring defaults", e);
        }
    }

    private static void restoreDefaultsAndSave(String message, Throwable error) {
        if (error != null) {
            VoxyWorldGenV2.LOGGER.error(message, error);
        } else {
            VoxyWorldGenV2.LOGGER.warn(message);
        }
        ConfigData defaults = new ConfigData();
        defaults.normalize();
        DATA = defaults;
        save();
    }
    
    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(DATA, writer);
            }
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to save config", e);
        }
    }
    
    public static class ConfigData {
        public boolean enabled = true;
        public boolean showF3MenuStats = true;
        public int generationRadius = 128;
        public int update_interval = 20; // legacy field for Compat
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 20;

        public void normalize() {
            maxQueueSize = Math.max(1, maxQueueSize);
            maxActiveTasks = Math.max(1, maxActiveTasks);
            generationRadius = Math.max(1, generationRadius);
            update_interval = Math.max(1, update_interval);
        }
    }
}
