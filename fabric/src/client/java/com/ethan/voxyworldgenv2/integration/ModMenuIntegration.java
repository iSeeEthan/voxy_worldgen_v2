package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import com.ethan.voxyworldgenv2.network.ServerConfigState;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.translatable("config.voxyworldgenv2.title"));

            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            buildClientCategory(builder, entryBuilder);
            ServerEdits edits = buildServerCategory(builder, entryBuilder);

            builder.setSavingRunnable(() -> {
                // client values save locally regardless
                Config.save();

                boolean remote = isOnRemoteServer();
                if (!remote) {
                    Config.applyServerConfig(edits.snapshot());
                    Config.save();
                    com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
                } else if (ServerConfigState.canEdit()) {
                    ClientPlayNetworking.send(new NetworkHandler.ServerConfigPushPayload(edits.snapshot()));
                }
            });

            return builder.build();
        };
    }

    private static void buildClientCategory(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory client = builder.getOrCreateCategory(Component.translatable("config.voxyworldgenv2.category.client"));

        client.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.f3_stats"), Config.DATA.showF3MenuStats)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.f3_stats.tooltip"))
                .setSaveConsumer(v -> Config.DATA.showF3MenuStats = v)
                .build());
    }

    // holds the live entry handles so the saving runnable can read the edited server values
    private record ServerEdits(java.util.function.Supplier<Boolean> enabled,
                               java.util.function.Supplier<Integer> radius,
                               java.util.function.Supplier<Integer> updateInterval,
                               java.util.function.Supplier<Integer> maxQueue,
                               java.util.function.Supplier<Integer> maxActive) {
        Config.ServerConfig snapshot() {
            return new Config.ServerConfig(enabled.get(), radius.get(), updateInterval.get(), maxQueue.get(), maxActive.get());
        }
    }

    private static ServerEdits buildServerCategory(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory server = builder.getOrCreateCategory(Component.translatable("config.voxyworldgenv2.category.server"));

        // current server values, synced from the server when connected, else the local config
        Config.ServerConfig sc = ServerConfigState.get();

        boolean editable = !isOnRemoteServer() || ServerConfigState.canEdit();

        if (isOnRemoteServer() && !editable) {
            server.addEntry(entryBuilder.startTextDescription(
                    Component.translatable("config.voxyworldgenv2.server.readonly")).build());
        }

        var enabled = entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.enabled"), sc.enabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.enabled.tooltip"))
                .build();
        var radius = entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.radius"), sc.generationRadius(), 1, 512)
                .setDefaultValue(128)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.radius.tooltip"))
                .build();
        var updateInterval = entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.update_interval"), sc.updateInterval(), 1, 200)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.update_interval.tooltip"))
                .build();
        var maxQueue = entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.max_queue"), sc.maxQueueSize())
                .setDefaultValue(20000)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_queue.tooltip"))
                .build();
        var maxActive = entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.max_active"), sc.maxActiveTasks(), 1, 128)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_active.tooltip"))
                .build();

        enabled.setEditable(editable);
        radius.setEditable(editable);
        updateInterval.setEditable(editable);
        maxQueue.setEditable(editable);
        maxActive.setEditable(editable);

        addAll(server, enabled, radius, updateInterval, maxQueue, maxActive);

        return new ServerEdits(enabled::getValue, radius::getValue, updateInterval::getValue, maxQueue::getValue, maxActive::getValue);
    }

    private static void addAll(ConfigCategory cat, AbstractConfigListEntry<?>... entries) {
        for (AbstractConfigListEntry<?> e : entries) cat.addEntry(e);
    }

    // true if connected to a server other than our own integrated (singleplayer) one
    private static boolean isOnRemoteServer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && !mc.hasSingleplayerServer();
    }
}
