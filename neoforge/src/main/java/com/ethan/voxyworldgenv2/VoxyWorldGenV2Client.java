package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.client.DebugRenderer;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = VoxyWorldGenV2.MOD_ID, dist = Dist.CLIENT)
public class VoxyWorldGenV2Client {

    public VoxyWorldGenV2Client(IEventBus modEventBus, ModContainer modContainer) {
        VoxyWorldGenV2.LOGGER.info("initializing voxy world gen v2 client");

        modEventBus.addListener(VoxyWorldGenV2Client::registerGuiLayers);

        com.ethan.voxyworldgenv2.network.NetworkClientHandler.init();

        if (ModList.get().isLoaded("cloth_config")) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (container, parent) ->
                            com.ethan.voxyworldgenv2.integration.ModMenuIntegration.createConfigScreen(parent));
        }

        ChunkGenerationManager.getInstance().setPauseCheck(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isPaused();
        });

        NeoForge.EVENT_BUS.register(ClientGameEvents.class);
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(VoxyWorldGenV2.MOD_ID, "debug_overlay"),
                DebugRenderer::render
        );
    }

    public static final class ClientGameEvents {
        private ClientGameEvents() {}

        @SubscribeEvent
        public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
            com.ethan.voxyworldgenv2.network.NetworkState.setServerConnected(false);
            com.ethan.voxyworldgenv2.network.ServerConfigState.clear();
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            com.ethan.voxyworldgenv2.network.NetworkState.tick();
        }
    }
}
