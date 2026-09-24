package com.netflared.tunnel;

import com.netflared.NetflaredMod;
import com.netflared.config.NetflaredConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the cloudflared binary and active tunnel processes. */
public class TunnelManager {
    private static final String CLOUDFLARED_RELEASE_BASE =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/";

    private final Path binDir;
    private final Path pidDir;
    private final Map<String, Process> activeTunnels = new ConcurrentHashMap<>();

    public TunnelManager(Path configDir) {
        this.binDir = configDir.resolve("bin");
        this.pidDir = configDir.resolve("tunnels");
    }

    public Path binaryPath() {
        return binDir.resolve(Platform.detect().binaryFileName());
    }

    public boolean isBinaryReady() {
        try {
            Path b = binaryPath();
            return Files.isRegularFile(b) && Files.size(b) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Downloads cloudflared atomically. A partially downloaded executable is
     * never exposed as the live binary.
     */
    public synchronized Path ensureBinary() throws IOException, InterruptedException {
        Platform platform = Platform.detect();
        Files.createDirectories(binDir);

        Path binary = binDir.resolve(platform.binaryFileName());
        if (Files.isRegularFile(binary) && Files.size(binary) > 0) {
            makeExecutable(binary);
            return binary;
        }

        Path temporary = binDir.resolve(platform.assetName() + ".part");
        Files.deleteIfExists(temporary);

        NetflaredMod.LOGGER.info("[Netflared] Downloading cloudflared ({}) ...", platform.assetName());
        try {
            downloadFile(CLOUDFLARED_RELEASE_BASE + platform.assetName(), temporary);

            if (platform.needsExtraction()) {
                extractTarGz(temporary, binDir);
                Files.deleteIfExists(temporary);
            } else {
                try {
                    Files.move(temporary, binary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, binary, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (!Files.isRegularFile(binary) || Files.size(binary) == 0) {
                throw new IOException("cloudflared download did not produce a valid binary");
            }

            makeExecutable(binary);
            NetflaredMod.LOGGER.info("[Netflared] cloudflared ready at {}", binary);
            return binary;
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private void downloadFile(String url, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "Netflared/" + NetflaredMod.MOD_ID);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Cloudflare download failed with HTTP " + status);
            }

            long expected = connection.getContentLengthLong();
            try (ReadableByteChannel in = Channels.newChannel(connection.getInputStream());
                 var out = Files.newByteChannel(target,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 16);
                long downloaded = 0;
                while (in.read(buffer) != -1) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        downloaded += out.write(buffer);
                    }
                    buffer.clear();
                }
                if (expected >= 0 && downloaded != expected) {
                    throw new IOException("Incomplete cloudflared download (" + downloaded + "/" + expected + " bytes)");
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void extractTarGz(Path archive, Path destDir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destDir.toString())
                .redirectErrorStream(true).start();
        logProcessOutput(p, "cloudflared-extract");
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Failed to extract cloudflared archive (exit code " + code + ")");
        }
    }

    private void makeExecutable(Path path) {
        try {
            var perms = new java.util.HashSet<java.nio.file.attribute.PosixFilePermission>();
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
        } catch (IOException e) {
            NetflaredMod.LOGGER.warn("[Netflared] Could not set executable bit on {}", path, e);
        }
    }

    public void killOrphanedTunnels() {
        try {
            if (!Files.exists(pidDir)) return;
            try (var stream = Files.list(pidDir)) {
                stream.filter(p -> p.toString().endsWith(".pid"))
                        .forEach(this::killOrphanByPidFile);
            }
        } catch (IOException e) {
            NetflaredMod.LOGGER.warn("[Netflared] Could not scan PID directory for orphans", e);
        }
    }

    private void killOrphanByPidFile(Path pidFile) {
        boolean safeToDelete = false;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) {
                safeToDelete = true;
                return;
            }

            ProcessHandle handle = process.get();
            if (!isCloudflared(handle)) {
                NetflaredMod.LOGGER.warn(
                        "[Netflared] Refusing to kill PID {} from {} because it is not cloudflared.",
                        pid, pidFile.getFileName());
                return;
            }

            killTree(handle);
            safeToDelete = true;
        } catch (Exception e) {
            NetflaredMod.LOGGER.debug("[Netflared] Could not inspect PID file {}", pidFile, e);
        } finally {
            if (safeToDelete) {
                try {
                    Files.deleteIfExists(pidFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean isCloudflared(ProcessHandle handle) {
        try {
            var info = handle.info();
            String command = info.command().orElse("").toLowerCase(Locale.ROOT);
            String commandLine = info.commandLine().orElse("").toLowerCase(Locale.ROOT);
            return command.contains("cloudflared") || commandLine.contains("cloudflared");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized Process startTunnel(NetflaredConfig.Profile profile)
            throws IOException, InterruptedException {
        validateProfile(profile);

        Process existing = activeTunnels.get(profile.domain);
        if (existing != null) {
            if (existing.isAlive()) return existing;
            activeTunnels.remove(profile.domain, existing);
            profile.running = false;
        }

        killOrphanByPidFile(pidFileFor(profile.domain));

        Path binary = ensureBinary();
        ProcessBuilder pb = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                "access", "tcp",
                "--hostname", profile.domain,
                "--url", "localhost:" + profile.port);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        activeTunnels.put(profile.domain, process);
        profile.running = true;
        writePidFile(profile.domain, process.pid());
        logProcessOutput(process, "cloudflared-" + profile.domain);

        Thread watcher = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                activeTunnels.remove(profile.domain, process);
                profile.running = false;
                NetflaredMod.LOGGER.info(
                        "[Netflared] cloudflared for {} exited with code {}",
                        profile.domain, exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    Files.deleteIfExists(pidFileFor(profile.domain));
                } catch (IOException ignored) {
                }
            }
        }, "netflared-tunnel-watcher");
        watcher.setDaemon(true);
        watcher.start();

        return process;
    }

    private void validateProfile(NetflaredConfig.Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Profile is missing");
        String domain = profile.domain == null ? "" : profile.domain.trim();
        if (domain.isEmpty()) throw new IllegalArgumentException("Tunnel domain is empty");
        if (domain.length() > 253) throw new IllegalArgumentException("Tunnel domain is too long");
        if (!domain.matches("(?i)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) {
            throw new IllegalArgumentException("Invalid tunnel domain: " + domain);
        }
        if (profile.port < 1 || profile.port > 65535) {
            throw new IllegalArgumentException("Local port must be between 1 and 65535");
        }
        profile.domain = domain;
    }

    public synchronized void stopTunnel(String domain) {
        Process process = activeTunnels.remove(domain);
        if (process != null && process.isAlive()) {
            killTree(process.toHandle());
        }
        try { Files.deleteIfExists(pidFileFor(domain)); } catch (IOException ignored) {}
    }

    public synchronized void forceStopAll() {
        for (Map.Entry<String, Process> entry : activeTunnels.entrySet()) {
            Process p = entry.getValue();
            if (p != null && p.isAlive()) {
                try { killTree(p.toHandle()); } catch (Throwable ignored) {}
            }
            try { Files.deleteIfExists(pidFileFor(entry.getKey())); } catch (IOException ignored) {}

        }
        activeTunnels.clear();
    }

    public boolean isTunnelRunning(String domain) {
        Process p = activeTunnels.get(domain);
        return p != null && p.isAlive();
    }

    private void killTree(ProcessHandle handle) {
        try {
            handle.descendants().forEach(child -> {
                try { child.destroyForcibly(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
        try { handle.destroyForcibly(); } catch (Throwable ignored) {}
    }

    private Path pidFileFor(String domain) {
        String safe = domain == null ? "default" : domain.replaceAll("[^a-zA-Z0-9._-]", "_");
        return pidDir.resolve(safe.isEmpty() ? "default.pid" : safe + ".pid");
    }

    private void writePidFile(String domain, long pid) {
        try {
            Files.createDirectories(pidDir);
            Files.writeString(pidFileFor(domain), Long.toString(pid),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            NetflaredMod.LOGGER.warn("[Netflared] Could not write PID file for {}", domain, e);
        }
    }

    private void logProcessOutput(Process process, String tag) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    NetflaredMod.LOGGER.info("[{}] {}", tag, line);
                }
            } catch (IOException ignored) {
            }
        }, tag + "-output-reader");
        reader.setDaemon(true);
        reader.start();
    }
}
