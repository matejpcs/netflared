package com.netflared.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.netflared.NetflaredMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persistent Netflared configuration. */
public class NetflaredConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private List<Profile> profiles = new ArrayList<>();
    private boolean setupComplete;

    public static class Profile {
        public String name = "Netflared Server";
        public String domain = "";
        public int port = 25565;
        public transient boolean running;

        public Profile() {}
        public Profile(String name, String domain, int port) {
            this.name = name;
            this.domain = domain;
            this.port = port;
        }

        public String getJoinAddress() {
            return "localhost:" + port;
        }
    }

    public static NetflaredConfig load(Path configDir) {
        Path file = configDir.resolve("netflared.json");
        NetflaredConfig cfg = new NetflaredConfig();

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                cfg = GSON.fromJson(reader, NetflaredConfig.class);
                if (cfg == null) cfg = new NetflaredConfig();
                if (cfg.profiles == null) cfg.profiles = new ArrayList<>();
                cfg.normalize();
            } catch (Exception e) {
                NetflaredMod.LOGGER.error("[Netflared] Failed to load config", e);
                backupBrokenConfig(file);
                cfg = new NetflaredConfig();
            }
        }

        if (cfg.profiles.isEmpty()) {
            cfg.profiles.add(new Profile("Netflared Server 1", "", 25565));
        }
        return cfg;
    }

    public void save(Path configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("Config directory cannot be null");
        }

        try {
            Files.createDirectories(configDir);
            Path file = configDir.resolve("netflared.json");
            Path temp = configDir.resolve("netflared.json.tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            NetflaredMod.LOGGER.error("[Netflared] Failed to save config", e);
        }
    }

    private void normalize() {
        profiles.removeIf(profile -> profile == null);
        for (Profile profile : profiles) {
            profile.name = profile.name == null || profile.name.isBlank()
                    ? "Netflared Server" : profile.name.trim();
            profile.domain = profile.domain == null ? "" : profile.domain.trim();
            if (profile.port < 1 || profile.port > 65535) profile.port = 25565;
            profile.running = false;
        }
    }

    private static void backupBrokenConfig(Path file) {
        try {
            Path backup = file.resolveSibling("netflared.json.broken");
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException backupError) {
            NetflaredMod.LOGGER.warn("[Netflared] Could not back up broken config", backupError);
        }
    }

    public List<Profile> getProfiles() { return profiles; }
    public void setProfiles(List<Profile> profiles) {
        this.profiles = profiles == null ? new ArrayList<>() : profiles;
        normalize();
    }
    public boolean isSetupComplete() { return setupComplete; }
    public void setSetupComplete(boolean value) { setupComplete = value; }

    public void addProfile() {
        int n = 1;
        String name;
        while (true) {
            name = "Netflared Server " + n++;
            boolean used = false;
            for (Profile profile : profiles) {
                if (profile != null && name.equals(profile.name)) {
                    used = true;
                    break;
                }
            }
            if (!used) break;
        }
        profiles.add(new Profile(name, "", 25565));
    }

    public void removeProfile(int index) {
        if (index >= 0 && index < profiles.size() && profiles.size() > 1) {
            profiles.remove(index);
        }
    }
}
