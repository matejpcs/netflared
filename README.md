# Netflared

A **client-side** Fabric mod for Minecraft 26.2 that manages Cloudflare Access tunnels to your Minecraft servers, with multi-profile support, on-demand connection, and one-click join.

---

## What it does

1. Adds a **"Netflared"** button to the top-left of the multiplayer server list screen.
2. The settings screen lets you configure **multiple tunnel profiles**, each with:
   - A display name (used as the server name in the multiplayer list)
   - A Cloudflare tunnel domain (e.g. `play.example.com`)
   - A local port the tunnel binds to (default `25565`)
3. Clicking **Connect** for a profile:
   - Downloads the official `cloudflared` binary from Cloudflare's GitHub releases **if it isn't already present**
   - Runs `cloudflared access tcp --hostname <domain> --url localhost:<port>`
   - Shows a status overlay (`Downloading`, `Establishing tunnel`, `Connected`, `Error`)
   - Offers a **Join** button that connects you directly to `localhost:<port>`
4. All tunnels are killed when Minecraft exits. Nothing runs silently, the binary is never downloaded and no process is ever spawned without an explicit user action.

---

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | ≥ 0.19.0 |
| Fabric API | 0.160.0+26.2 (or later for 26.2) |
| Java | 25 |

---

## Installation

1. Install Fabric Loader 0.19.5 (or newer) for Minecraft 26.2.
2. Drop the built jar into your instance's `mods/` folder:
   - Vanilla launcher: `~/.minecraft/mods/` (or `%appdata%\.minecraft\mods\` on Windows)
   - Prism / MultiMC / ATLauncher: `<instance>/minecraft/mods/`
3. Also install **Fabric API 0.160.0+26.2** in the same folder.
4. Launch the game.

---

## Usage

### Setting up your first tunnel

1. Open the **Multiplayer** screen. Click the **Netflared** button in the top-left.
2. In the settings screen, fill in:
   - **Name** — what you want the server to be called (e.g. `My Server`)
   - **Domain** — your Cloudflare tunnel hostname (e.g. `play.example.com`)
   - **Port** — local port for the tunnel to bind (default `25565`)
3. Click **Save & Sync** to persist the config.

### Connecting

1. Open the Netflared settings screen.
2. Click **Connect** next to the profile you want to use.
3. Wait for the status screen to show **Successfully connected** (this can take a few seconds the first time, since it downloads the cloudflared binary).
4. Click **Join** to enter the server, or click **OK** to go back and join manually via `localhost:<port>`.

### Multiple servers

Click **+ Add Server** in the settings screen to create additional profiles. Each profile gets its own domain, port, and tunnel process. They can all be running at once.

---

## Configuration files

Everything lives under `.minecraft/config/netflared/`:

| Path | Purpose |
|---|---|
| `netflared.json` | Profiles (name, domain, port). Edit by hand if you prefer. |
| `bin/cloudflared` (or `cloudflared.exe` on Windows) | The downloaded cloudflared binary. Delete it to force a fresh download. |

---

## Building from source

### Prerequisites

- **JDK 25** (verify with `java -version`)
- **Gradle 9.5.1** (verify with `gradle --version`)

Gradle 9.5.1 is required because Fabric Loom 1.17, which targets Minecraft 26.2, publishes its plugin metadata with `org.gradle.plugin.api-version = 9.5.0`. Older Gradle versions (including 8.x) refuse to resolve the plugin.

On Debian/Ubuntu, the distro-shipped Gradle is ancient (4.x) and will not work. Install Gradle 9.5.1 manually:

```
cd ~/tmp
wget https://services.gradle.org/distributions/gradle-9.5.1-bin.zip
unzip gradle-9.5.1-bin.zip -d ~/.local/
export PATH="$HOME/.local/gradle-9.5.1/bin:$PATH"
```

Add that `export PATH=` line to your `~/.bashrc` to make it permanent.

### Build

```
cd netflared
gradle wrapper --gradle-version 9.5.1   # once, generates ./gradlew
./gradlew build
```

Output jar lands in `build/libs/netflared-1.0.0+26.2.jar`.

### Develop

```
./gradlew runClient
```

Launches a dev Minecraft client with the mod loaded. This is the fastest way to iterate on UI code.

---

## Before you build for your own server

You don't need to edit any Java code — the domain is a per-profile setting in the GUI and in `netflared.json`. Just fill in your own hostname and port.

If you'd rather ship a jar with sensible defaults baked in, edit `NetflaredConfig.java` and change the seed profile that's created on first run:

```
cfg.profiles.add(new Profile("Netflared Server 1", "play.yourdomain.com", 25565));
```

---

## Server-side setup

**This mod only handles the client half.** Your server still needs a working Cloudflare Tunnel + Access configuration. Specifically:

1. In the Cloudflare Zero Trust dashboard, create a tunnel pointing to `localhost:25565` (or wherever your Minecraft server listens).
2. Add a **Public Hostname** for `play.yourdomain.com`:
   - **Service Type: TCP** (not HTTP/HTTPS)
   - **URL: `localhost:25565`**
3. Under **Network** for your domain, ensure **WebSockets: On**.
4. If you're using Cloudflare Access, configure the policy (email OTP, service token, etc.) as needed.

### The most common failure mode

If you see `websocket: bad handshake` in the game log, it's almost always because the tunnel's ingress is configured as `http://localhost:25565` instead of `tcp://localhost:25565`. Minecraft speaks raw TCP — Cloudflare's HTTP proxy will reject the handshake every time.

Quick sanity check: run the exact command the mod runs, from a plain terminal:

```
cloudflared access tcp --hostname play.yourdomain.com --url localhost:25565
```

If that fails too, the problem is server-side and has nothing to do with this mod.

---

## Things worth knowing

- **The first connection downloads a ~30 MB binary.** It's placed in `config/netflared/bin/`. Nothing is downloaded until you click Connect for the first time.
- **Antivirus / SmartScreen false positives are likely.** A program that downloads and executes a `.exe` (even with explicit user consent) is exactly the pattern Windows Defender heuristics flag. `cloudflared` itself is legitimate and signed by Cloudflare, but you may see a warning the first time.
- **No update pinning.** The download always grabs the *latest* cloudflared release from GitHub. If Cloudflare ever renames a release asset, downloads will start failing. Pinning a specific version tag is a small change to `CLOUDFLARED_RELEASE_BASE` in `TunnelManager.java`.
- **The mod is client-only.** It declares `"environment": "client"` in `fabric.mod.json`, so it does nothing on a server and is safe to leave installed while playing on other servers.
- **Multiple tunnels can run at once.** Each profile is an independent cloudflared process. They're all cleaned up when the client exits.
- **The F9 keybind is rebindable** under Options → Controls → Key Binds → Netflared.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `websocket: bad handshake` | Server-side ingress is HTTP instead of TCP, or WebSockets are disabled | Check Cloudflare dashboard config |
| `Profile domain is empty` | You clicked Connect on a profile with no domain | Fill in the Domain field, Save & Sync |
| Download never finishes | Firewall blocking GitHub release downloads | Check the game log; try downloading `cloudflared` manually and placing it in `config/netflared/bin/` |
| Buttons overlap on small windows | UI scaling | Increase Minecraft GUI scale, or resize the window |
| Game crashes on shutdown (26.2 only) | Client shutdown watchdog firing because the JVM won't exit | Fixed in this mod — tunnels are force-killed via `Runtime.halt(0)` after a short grace period |
| `Unsupported class file major version` during build | Wrong JDK | Ensure `java -version` reports 25 and `JAVA_HOME` points to a JDK 25 |
| `Could not resolve net.fabricmc:fabric-loom:1.17-SNAPSHOT` | Loom version moved | Check [fabricmc.net/develop](https://fabricmc.net/develop) for the current value of `loom_version` |

---

## Project structure

```
src/main/java/com/netflared/
├── NetflaredMod.java                  # Client entrypoint, keybind, lifecycle
├── config/
│   └── NetflaredConfig.java           # JSON persistence, Profile model
├── gui/
│   ├── NetflaredSettingsScreen.java   # Main config UI
│   └── NetflaredStatusScreen.java     # Connection progress / Join
├── mixin/
│   └── MultiplayerScreenMixin.java    # Injects "Netflared" button
└── tunnel/
    ├── Platform.java                  # OS/arch detection
    └── TunnelManager.java             # Binary download + process lifecycle

src/main/resources/
├── fabric.mod.json
├── netflared.mixins.json
└── assets/netflared/lang/en_us.json
```

---

## License

See `LICENSE`.

---

## Disclaimer

This project was made with the help of AI. It is not fully hand-written by a human. Review the source before shipping it to other people.

![Made with AI](https://ai-label.org/image-pack/ai-label_banner-made-with-ai.svg)