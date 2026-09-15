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

    /**
     * Daemon thread that waits for the client to stop, then kills all
     * tunnels and hard-halts the JVM. This runs independently of the
     * shutdown-hook sequence, so it can fire before the 26.2 client
     * watchdog decides the JVM is stuck.
     */
    private Thread shutdownGuard;

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

        // Guard thread: sleeps forever, gets interrupted on client stop.
        // When interrupted, it kills tunnels and halts the JVM immediately,
        // bypassing the shutdown-hook queue and the watchdog timer.
        shutdownGuard = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // Client is stopping — clean up and exit now.
                try {
                    if (tunnelManager != null) tunnelManager.forceStopAll();
                } catch (Throwable ignored) {}
                Runtime.getRuntime().halt(0);
            }
        }, "netflared-shutdown-guard");
        shutdownGuard.setDaemon(true);
        shutdownGuard.start();

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

        // Fire the guard thread the moment the client begins stopping.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (shutdownGuard != null) shutdownGuard.interrupt();
        });

        // Secondary shutdown hook — in case CLIENT_STOPPING never fires
        // (e.g. the process is killed from outside). No sleep, halt
        // immediately.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (tunnelManager != null) tunnelManager.forceStopAll();
            } catch (Throwable ignored) {}
            Runtime.getRuntime().halt(0);
        }, "netflared-shutdown"));

        LOGGER.info("[Netflared] Initialized with {} server profile(s).",
                config.getProfiles().size());
    }
}