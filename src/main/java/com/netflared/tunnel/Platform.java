package com.netflared.tunnel;

import java.util.Locale;

/** OS/architecture detection for cloudflared release assets. */
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

    public static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean amd64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x86-64");

        if (!amd64 && !arm64) {
            throw new UnsupportedOperationException("Unsupported CPU architecture: " + arch);
        }
        if (os.contains("win")) {
            if (!amd64) {
                throw new UnsupportedOperationException("Windows ARM64 cloudflared is not supported by this release");
            }
            return WINDOWS_AMD64;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm64 ? MAC_ARM64 : MAC_AMD64;
        }
        if (os.contains("linux")) {
            return arm64 ? LINUX_ARM64 : LINUX_AMD64;
        }
        throw new UnsupportedOperationException("Unsupported operating system: " + os);
    }
}
