package com.netflared.tunnel;

import com.netflared.NetflaredMod;
import com.netflared.config.NetflaredConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TunnelManager {

    private static final String CLOUDFLARED_RELEASE_BASE =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/";

    private final Path binDir;
    private final Map<String, Process> activeTunnels = new ConcurrentHashMap<>();

    public TunnelManager(Path configDir) {
        this.binDir = configDir.resolve("bin");
    }

    // ------------------------------------------------------------------
    // Binary management (unchanged)
    // ------------------------------------------------------------------

    public Path binaryPath() {
        return binDir.resolve(Platform.detect().binaryFileName());
    }

    public boolean isBinaryReady() {
        Path b = binaryPath();
        return Files.exists(b) && b.toFile().length() > 0;
    }

    public Path ensureBinary() throws IOException, InterruptedException {
        Files.createDirectories(binDir);
        Platform platform = Platform.detect();
        Path binary = binDir.resolve(platform.binaryFileName());

        if (Files.exists(binary) && Files.size(binary) > 0) {
            makeExecutable(binary);
            return binary;
        }

        NetflaredMod.LOGGER.info("[Netflared] Downloading cloudflared ({}) ...", platform.assetName());
        Path downloadTarget = binDir.resolve(platform.assetName());
        downloadFile(CLOUDFLARED_RELEASE_BASE + platform.assetName(), downloadTarget);

        if (platform.needsExtraction()) {
            extractTarGz(downloadTarget, binDir);
            Files.deleteIfExists(downloadTarget);
        } else if (!downloadTarget.equals(binary)) {
            Files.move(downloadTarget, binary, StandardCopyOption.REPLACE_EXISTING);
        }

        makeExecutable(binary);
        NetflaredMod.LOGGER.info("[Netflared] cloudflared ready at {}", binary);
        return binary;
    }

    private void downloadFile(String url, Path target) throws IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "netflared-mod");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);

        try (ReadableByteChannel in = Channels.newChannel(connection.getInputStream());
             var out = Files.newByteChannel(target,
                     Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                             StandardOpenOption.TRUNCATE_EXISTING))) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 16);
            while (in.read(buffer) != -1) {
                buffer.flip();
                out.write(buffer);
                buffer.clear();
            }
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
        } catch (UnsupportedOperationException e) {
            // Windows — no POSIX permissions.
        } catch (IOException e) {
            NetflaredMod.LOGGER.warn("[Netflared] Could not set executable bit on {}", path, e);
        }
    }

    // ------------------------------------------------------------------
    // Tunnel lifecycle
    // ------------------------------------------------------------------

    public synchronized Process startTunnel(NetflaredConfig.Profile profile)
            throws IOException, InterruptedException {

        if (profile.domain == null || profile.domain.isBlank()) {
            throw new IllegalArgumentException("Profile domain is empty");
        }

        Process existing = activeTunnels.get(profile.domain);
        if (existing != null && existing.isAlive()) {
            return existing;
        }

        Path binary = ensureBinary();
        String localUrl = "localhost:" + profile.port;

        NetflaredMod.LOGGER.info("[Netflared] Starting tunnel: {} access tcp --hostname {} --url {}",
                binary, profile.domain, localUrl);

        ProcessBuilder pb = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                "access", "tcp",
                "--hostname", profile.domain,
                "--url", localUrl
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        activeTunnels.put(profile.domain, process);
        profile.running = true;

        logProcessOutput(process, "cloudflared-" + profile.domain);
        return process;
    }

    /**
     * Graceful stop for the "Disconnect" button in the GUI. Waits up to 3
     * seconds for the process to exit before force-killing it.
     *
     * <p>Do NOT call this from a JVM shutdown hook — use
     * {@link #forceStopAll()} instead, or the client watchdog will kill
     * Minecraft and generate a crash report.</p>
     */
    public synchronized void stopTunnel(String domain) {
        Process process = activeTunnels.remove(domain);
        if (process != null && process.isAlive()) {
            NetflaredMod.LOGGER.info("[Netflared] Stopping tunnel for {}", domain);
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Fast, non-blocking shutdown used during JVM exit. Kills every tunnel
     * immediately with {@code destroyForcibly()} and returns without waiting.
     *
     * <p>This is the method the shutdown hook should call. Waiting for
     * processes to exit cleanly during JVM shutdown is what triggers the
     * 26.2 client watchdog crash.</p>
     */
    public synchronized void forceStopAll() {
        for (Map.Entry<String, Process> entry : activeTunnels.entrySet()) {
            Process p = entry.getValue();
            if (p != null) {
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                    // best-effort; we are exiting anyway
                }
            }
        }
        activeTunnels.clear();
    }

    public boolean isTunnelRunning(String domain) {
        Process p = activeTunnels.get(domain);
        return p != null && p.isAlive();
    }

    // ------------------------------------------------------------------

    private void logProcessOutput(Process process, String tag) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    NetflaredMod.LOGGER.info("[{}] {}", tag, line);
                }
            } catch (IOException ignored) {
                // stream closed because the process ended
            }
        }, tag + "-output-reader");
        reader.setDaemon(true);
        reader.start();
    }
}
