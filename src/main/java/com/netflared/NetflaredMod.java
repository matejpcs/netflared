package com.netflared;

import com.mojang.blaze3d.platform.InputConstants;
import com.netflared.config.NetflaredConfig;
import com.netflared.gui.NetflaredSettingsScreen;
import com.netflared.tunnel.TunnelManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class NetflaredMod implements ClientModInitializer {

    public static final String MOD_ID = "netflared";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static NetflaredMod INSTANCE;
    private static NetflaredConfig config;
    private static TunnelManager tunnelManager;

    private KeyMapping openTunnelKey;

    public static NetflaredMod getInstance() { return INSTANCE; }
    public static NetflaredConfig getConfig() { return config; }
    public static TunnelManager getTunnelManager() { return tunnelManager; }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        config = NetflaredConfig.load(configDir);
        tunnelManager = new TunnelManager(configDir);
        tunnelManager.killOrphanedTunnels();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "tunnel"));
        openTunnelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.netflared.open_tunnel_ui",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_F9,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openTunnelKey.consumeClick()) {
                Screen current = client.gui.screen();
                if (current instanceof TitleScreen || current instanceof JoinMultiplayerScreen) {
                    client.gui.setScreen(new NetflaredSettingsScreen(current));
                }
            }
        });

        // Fast, non-blocking cleanup when the client stops.
        // forceStopAll() returns immediately without waiting for processes
        // to die, so the JVM can actually exit before the 26.2 watchdog
        // fires.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (tunnelManager != null) tunnelManager.forceStopAll();
        });

        // Safety net: if anything else is still holding the JVM open, kill
        // it hard after a short grace period. Runtime.halt() bypasses the
        // shutdown-hook sequence entirely, so it can't deadlock. This is
        // the same technique the ForceExitOnShutdown mod uses to fix the
        // 26.2 "Client shutdown from post-main" crash.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (tunnelManager != null) tunnelManager.forceStopAll();

            // Give the JVM ~500ms to finish whatever else it's doing,
            // then pull the plug.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(0);
        }, "netflared-shutdown"));

        LOGGER.info("[Netflared] Initialized with {} server profile(s).",
                config.getProfiles().size());
    }
}