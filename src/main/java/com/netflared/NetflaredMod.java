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
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    /**
     * Downloads editable Netflared translations from the dedicated lang branch and
     * keeps a local copy under config/netflared/lang. The GUI reads this cache so
     * language changes can be shipped independently of the mod jar.
     */
    public static final class Translations {
        private static final String LANG_BRANCH =
                "https://raw.githubusercontent.com/matejpcs/netflared/lang/src/main/resources/assets/netflared/lang/";
        private static final Map<String, String> values = new HashMap<>();
        private static volatile String loadedLocale = "en_us";
        private static volatile boolean refreshStarted;
        String[][] defaults = {
                {"netflared.settings.title", "Netflared Settings"},
                {"netflared.settings.name", "Name"},
                {"netflared.settings.domain", "Tunnel Domain"},
                {"netflared.settings.port", "Local Port"},
                {"netflared.settings.add", "+ Add Server"},
                {"netflared.settings.save", "Save & Sync"},
                {"netflared.settings.saved", "Settings saved"},
                {"netflared.settings.save_failed", "Could not save settings"},
                {"netflared.settings.cancel", "Cancel"},
                {"netflared.settings.back", "Back"},
                {"netflared.status.title", "Netflared — %s"},
                {"netflared.status.working", "Connecting..."},
                {"netflared.status.downloading", "Downloading cloudflared..."},
                {"netflared.status.connecting", "Establishing tunnel..."},
                {"netflared.status.ready", "Tunnel ready"},
                {"netflared.status.connected", "Connection established"},
                {"netflared.status.exited", "cloudflared stopped unexpectedly"},
                {"netflared.status.failed", "Connection failed"},
                {"netflared.status.error_detail", "Details: %s"},
                {"netflared.status.local", "Local endpoint: %s"},
                {"netflared.status.domain", "Cloudflare host: %s"},
                {"netflared.status.join", "Join"},
                {"netflared.status.back", "Back"},
                {"netflared.status.cancel", "Cancel"},
                {"netflared.status.idle", "Idle"},
                {"netflared.status.connect", "Connect"},
                {"netflared.status.disconnect", "Disconnect"},
                {"netflared.debug.button", "Debug"},
                {"netflared.debug.title", "Netflared Debug Tools"},
                {"netflared.debug.subtitle", "Internal UI testing — no tunnel is started"},
                {"netflared.debug.working", "Test Connecting"},
                {"netflared.debug.success", "Test Connected"},
                {"netflared.debug.error", "Test Error"},
                {"netflared.debug.settings", "Test Settings"},
                {"netflared.debug.working_message", "Waiting for tunnel..."},
                {"netflared.debug.success_message", "Debug state: tunnel ready"},
                {"netflared.debug.error_message", "Debug state: simulated connection failure"}
        };
        for (String[] entry : defaults) values.put(entry[0], entry[1]);

        public static synchronized void refresh() {
            if (refreshStarted) return;
            refreshStarted = true;

            String locale = "en_us";
            try {
                if (MinecraftHolder.client() != null) {
                    locale = MinecraftHolder.client().getLanguageManager().getSelected();
                }
            } catch (Throwable ignored) {}

            loadedLocale = locale;
            final String selected = locale;
            Thread thread = new Thread(() -> {
                try {
                    Path langDir = FabricLoader.getInstance().getConfigDir()
                            .resolve(MOD_ID).resolve("lang");
                    Files.createDirectories(langDir);

                    Path selectedFile = langDir.resolve(selected + ".json");
                    Path englishFile = langDir.resolve("en_us.json");

                    downloadIfChanged(selectedFile, selected);
                    if (!"en_us".equals(selected)) downloadIfChanged(englishFile, "en_us");

                    loadFile(englishFile);
                    if (!"en_us".equals(selected)) loadFile(selectedFile);

                    LOGGER.info("[Netflared] Loaded dynamic language {}", selected);
                } catch (Exception e) {
                    LOGGER.warn("[Netflared] Dynamic language sync failed; using bundled/default text", e);
                }
            }, "netflared-language-sync");
            thread.setDaemon(true);
            thread.start();
        }

        public static String text(String key, Object... args) {
            String value = values.getOrDefault(key, key);
            if (args.length > 0) {
                try {
                    return String.format(value, args);
                } catch (Exception ignored) {}
            }
            return value;
        }

        private static void downloadIfChanged(Path target, String locale) throws Exception {
            HttpURLConnection connection = (HttpURLConnection)
                    URI.create(LANG_BRANCH + locale + ".json").toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Netflared/" + MOD_ID);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                connection.disconnect();
                return;
            }

            byte[] data = connection.getInputStream().readAllBytes();
            connection.disconnect();
            if (data.length == 0) return;

            Path temp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(temp, data);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static synchronized void loadFile(Path file) {
            if (!Files.isRegularFile(file)) return;
            try {
                JsonObject object = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        values.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Netflared] Invalid language file {}", file, e);
            }
        }
    }

    private static final class MinecraftHolder {
        static net.minecraft.client.Minecraft client() {
            return net.minecraft.client.Minecraft.getInstance();
        }
    }

    public static void refreshTranslations() {
        Translations.refresh();
    }

    public static net.minecraft.network.chat.Component tr(String key, Object... args) {
        return net.minecraft.network.chat.Component.literal(Translations.text(key, args));
    }

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