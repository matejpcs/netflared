package com.netflared.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.netflared.NetflaredMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent configuration stored at {@code .minecraft/config/netflared.json}.
 *
 * <p>Each profile represents one Cloudflare tunnel endpoint. The mod syncs
 * each profile into the vanilla multiplayer server list as
 * "Netflared Server N" on save.</p>
 */
public class NetflaredConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** List of configured tunnel profiles. */
    private List<Profile> profiles = new ArrayList<>();

    /** Whether the mod has already shown the initial setup prompt. */
    private boolean setupComplete = false;

    public static class Profile {
        /** Human-readable name shown in the multiplayer server list. */
        public String name = "Netflared Server";

        /** Cloudflare Access hostname, e.g. {@code play.justsmp.eu}. */
        public String domain = "";

        /** Local TCP port to forward to, e.g. {@code 25565}. */
        public int port = 25565;

        /** Runtime-only: PID of the active cloudflared process, not persisted. */
        public transient boolean running = false;

        public Profile() {}

        public Profile(String name, String domain, int port) {
            this.name = name;
            this.domain = domain;
            this.port = port;
        }

        /** The local join address players use in the multiplayer menu. */
        public String getJoinAddress() {
            return "localhost:" + port;
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public static NetflaredConfig load(Path configDir) {
        Path file = configDir.resolve("netflared.json");
        NetflaredConfig cfg = new NetflaredConfig();

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                cfg = GSON.fromJson(reader, NetflaredConfig.class);
                if (cfg == null) cfg = new NetflaredConfig();
                if (cfg.profiles == null) cfg.profiles = new ArrayList<>();
            } catch (Exception e) {
                NetflaredMod.LOGGER.error("[Netflared] Failed to load config, using defaults", e);
                cfg = new NetflaredConfig();
            }
        }

        // Seed a default profile on first run so the GUI isn't empty.
        if (cfg.profiles.isEmpty()) {
            cfg.profiles.add(new Profile("Netflared Server 1", "", 25565));
        }

        return cfg;
    }

    public void save(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path file = configDir.resolve("netflared.json");
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            NetflaredMod.LOGGER.error("[Netflared] Failed to save config", e);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public List<Profile> getProfiles() { return profiles; }
    public void setProfiles(List<Profile> profiles) { this.profiles = profiles; }
    public boolean isSetupComplete() { return setupComplete; }
    public void setSetupComplete(boolean v) { this.setupComplete = v; }

    public void addProfile() {
        int n = profiles.size() + 1;
        profiles.add(new Profile("Netflared Server " + n, "", 25565));
    }

    public void removeProfile(int index) {
        if (index >= 0 && index < profiles.size()) {
            profiles.remove(index);
        }
    }
}
