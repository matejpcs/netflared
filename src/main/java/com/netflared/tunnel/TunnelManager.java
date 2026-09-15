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

/**
 * Manages the cloudflared binary download and all active tunnel processes.
 *
 * <p>Binary location: {@code .minecraft/config/netflared/bin/}.
 * PID tracking location: {@code .minecraft/config/netflared/tunnels/}.
 * Each tunnel runs
 * {@code cloudflared access tcp --hostname <domain> --url localhost:<port>}.</p>
 *
 * <p>Tunnels are never started at mod init — only when the player
 * explicitly clicks "Connect" in the GUI.</p>
 */
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

    // ------------------------------------------------------------------
    // Binary management
    // ------------------------------------------------------------------

    /** Returns the path where the cloudflared binary lives for this platform. */
    public Path binaryPath() {
        return binDir.resolve(Platform.detect().binaryFileName());
    }

    public boolean isBinaryReady() {
        Path b = binaryPath();
        return Files.exists(b) && b.toFile().length() > 0;
    }

    /**
     * Downloads cloudflared if not already present. Blocking — call from a
     * background thread.
     */
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

    /** macOS releases ship as .tgz; shells out to system tar. */
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
    // Orphan cleanup — call once at mod init
    // ------------------------------------------------------------------

    /**
     * Scans the PID directory for leftovers from previous runs and kills
     * any cloudflared process that's still alive.
     *
     * <p>Only processes whose executable path or command line contains
     * "cloudflared" are killed. Anything else (e.g. a recycled PID now
     * belonging to an unrelated program) is left alone and the stale PID
     * file is simply removed.</p>
     */
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
        try {
            String content = Files.readString(pidFile).trim();
            long pid = Long.parseLong(content);

            ProcessHandle.of(pid).ifPresent(ph -> {
                if (!ph.isAlive()) {
                    return; // already gone
                }
                if (!isCloudflared(ph)) {
                    NetflaredMod.LOGGER.warn(
                            "[Netflared] PID {} from {} is not cloudflared (it's '{}'); refusing to kill it.",
                            pid, pidFile.getFileName(),
                            ph.info().command().orElse("<unknown>"));
                    return;
                }
                NetflaredMod.LOGGER.info(
                        "[Netflared] Killing orphaned cloudflared process PID {} (from {})",
                        pid, pidFile.getFileName());
                killTree(ph);
            });
        } catch (Exception e) {
            NetflaredMod.LOGGER.debug("[Netflared] Could not read PID file {}", pidFile, e);
        } finally {
            try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
        }
    }

    /**
     * Returns true if the process appears to be cloudflared. Checks both
     * the executable path and the full command line (since on some
     * platforms cloudflared is launched via a shim or shell wrapper).
     *
     * <p>Case-insensitive; matches both "cloudflared" and
     * "cloudflared.exe".</p>
     */
    private boolean isCloudflared(ProcessHandle handle) {
        try {
            var info = handle.info();

            var cmdOpt = info.command();
            if (cmdOpt.isPresent()) {
                String cmd = cmdOpt.get().toLowerCase(Locale.ROOT);
                if (cmd.contains("cloudflared")) return true;
            }

            var argsOpt = info.commandLine();
            if (argsOpt.isPresent()) {
                String line = argsOpt.get().toLowerCase(Locale.ROOT);
                if (line.contains("cloudflared")) return true;
            }
        } catch (Throwable ignored) {
            // Can't inspect — be conservative and don't kill.
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Tunnel lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts a tunnel for the given profile. The profile's {@code running}
     * flag is set to true on success.
     */
    public synchronized Process startTunnel(NetflaredConfig.Profile profile)
            throws IOException, InterruptedException {

        if (profile.domain == null || profile.domain.isBlank()) {
            throw new IllegalArgumentException("Profile domain is empty");
        }

        Process existing = activeTunnels.get(profile.domain);
        if (existing != null && existing.isAlive()) {
            return existing;
        }

        // Defensive: clean up anything still holding the PID file for this
        // domain (e.g. a leftover cloudflared from a crashed session).
        killOrphanByPidFile(pidFileFor(profile.domain));

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

        writePidFile(profile.domain, process.pid());
        logProcessOutput(process, "cloudflared-" + profile.domain);
        return process;
    }

    /**
     * Graceful stop for the "Disconnect" button. Kills the process tree
     * and removes the PID file.
     */
    public synchronized void stopTunnel(String domain) {
        Process process = activeTunnels.remove(domain);
        if (process != null && process.isAlive()) {
            NetflaredMod.LOGGER.info("[Netflared] Stopping tunnel for {}", domain);
            killTree(process.toHandle());
        }
        try { Files.deleteIfExists(pidFileFor(domain)); } catch (IOException ignored) {}
    }

    /**
     * Fast, non-blocking shutdown used during JVM exit. Kills every tunnel
     * process tree and clears PID files. Does not wait for anything.
     */
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

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Kills a process and all of its descendants. On Windows this is
     * required because {@code destroyForcibly()} only kills the direct
     * child, leaving grandchildren orphaned and holding ports.
     *
     * <p>Descendants are killed first, then the root — otherwise children
     * can be reparented to init and survive.</p>
     */
    private void killTree(ProcessHandle handle) {
        try {
            handle.descendants().forEach(child -> {
                try { child.destroyForcibly(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
        try { handle.destroyForcibly(); } catch (Throwable ignored) {}
    }

    private Path pidFileFor(String domain) {
        String safe = domain == null ? "default"
                : domain.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty()) safe = "default";
        return pidDir.resolve(safe + ".pid");
    }

    private void writePidFile(String domain, long pid) {
        try {
            Files.createDirectories(pidDir);
            Files.writeString(pidFileFor(domain), Long.toString(pid));
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
                // stream closed because the process ended
            }
        }, tag + "-output-reader");
        reader.setDaemon(true);
        reader.start();
    }
}