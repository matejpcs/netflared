package com.netflared.tunnel;

import java.util.Locale;

/**
 * OS/arch detection and cloudflared asset-name resolution.
 *
 * <p>Asset names match Cloudflare's GitHub release naming convention
 * (latest release at
 * {@code https://github.com/cloudflare/cloudflared/releases/latest/download/}).</p>
 */
public enum Platform {

    WINDOWS_AMD64("cloudflared-windows-amd64.exe", "cloudflared.exe", false),
    LINUX_AMD64("cloudflared-linux-amd64", "cloudflared", false),
    LINUX_ARM64("cloudflared-linux-arm64", "cloudflared", false),
    MAC_AMD64("cloudflared-darwin-amd64.tgz", "cloudflared", true),
    MAC_ARM64("cloudflared-darwin-arm64.tgz", "cloudflared", true);

    private final String assetName;
    private final String binaryFileName;
    private final boolean needsExtraction;

    Platform(String assetName, String binaryFileName, boolean needsExtraction) {
        this.assetName = assetName;
        this.binaryFileName = binaryFileName;
        this.needsExtraction = needsExtraction;
    }

    public String assetName() { return assetName; }
    public String binaryFileName() { return binaryFileName; }
    public boolean needsExtraction() { return needsExtraction; }

    /**
     * Detects the current platform. Falls back to Linux AMD64 for unknown
     * operating systems, which is the safest default for headless servers.
     */
    public static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        if (os.contains("win")) {
            return WINDOWS_AMD64;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return isArm ? MAC_ARM64 : MAC_AMD64;
        } else {
            return isArm ? LINUX_ARM64 : LINUX_AMD64;
        }
    }
}
