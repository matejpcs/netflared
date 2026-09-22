package com.netflared.gui;

import com.netflared.NetflaredMod;
import com.netflared.config.NetflaredConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Status / success overlay shown while a tunnel is being established, and
 * after it connects (offering a "Join" button that launches the player
 * directly into the configured server).
 *
 * <p>All tunnel startup happens on a background daemon thread; this screen
 * only reflects state and provides the join action.</p>
 */
public class NetflaredStatusScreen extends Screen {

    public enum State { WORKING, SUCCESS, ERROR }

    private final Screen parent;
    private final NetflaredConfig.Profile profile;

    private volatile State state = State.WORKING;
    private volatile String message = "Preparing...";

    private Button okButton;
    private Button joinButton;
    private Button cancelButton;

    public NetflaredStatusScreen(Screen parent, NetflaredConfig.Profile profile) {
        super(Component.translatable("netflared.status.title", profile.domain));
        this.parent = parent;
        this.profile = profile;
    }

    /** Thread-safe status update callable from the background tunnel thread. */
    public void updateStatus(String msg, State newState) {
        this.message = msg;
        this.state = newState;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (okButton != null) {
                okButton.visible = newState != State.WORKING;
                okButton.active = newState != State.WORKING;
            }
            if (joinButton != null) {
                joinButton.visible = newState == State.SUCCESS;
                joinButton.active = newState == State.SUCCESS;
            }
        });
    }

    /**
     * Kicks off the tunnel on a background thread. Safe to call immediately
     * after switching to this screen.
     */
    public void connect() {
        Thread t = new Thread(() -> {
            try {
                updateStatus("Downloading binary...", State.WORKING);
                NetflaredMod.getTunnelManager().ensureBinary();

                updateStatus("Establishing tunnel...", State.WORKING);
                Process p = NetflaredMod.getTunnelManager().startTunnel(profile);

                // Give cloudflared a moment to bind the local port.
                Thread.sleep(1500);

                if (p.isAlive()) {
                    updateStatus("Successfully connected", State.SUCCESS);
                } else {
                    updateStatus("cloudflared exited unexpectedly", State.ERROR);
                }
            } catch (Exception e) {
                NetflaredMod.LOGGER.error("[Netflared] Tunnel failed for {}", profile.domain, e);
                updateStatus("Error: " + e.getMessage(), State.ERROR);
            }
        }, "netflared-tunnel-setup");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        okButton = Button.builder(
                        Component.literal("OK"),
                        btn -> this.minecraft.gui.setScreen(parent))
                .bounds(centerX - 105, centerY + 50, 100, 20)
                .build();
        okButton.visible = state != State.WORKING;
        okButton.active = state != State.WORKING;
        this.addRenderableWidget(okButton);

        joinButton = Button.builder(
                        Component.literal("Join"),
                        btn -> {
                            // Parse the "host:port" string into a ServerAddress.
                            ServerAddress address = ServerAddress.parseString(
                                    profile.getJoinAddress());

                            ServerData data = new ServerData(
                                    profile.name,
                                    profile.getJoinAddress(),
                                    ServerData.Type.OTHER);

                            // MC 26.2 signature requires TransferState as the
                            // final arg; null means "not a transfer, fresh join".
                            ConnectScreen.startConnecting(
                                    this,
                                    this.minecraft,
                                    address,
                                    data,
                                    false,
                                    null);
                        })
                .bounds(centerX + 5, centerY + 50, 100, 20)
                .build();
        joinButton.visible = state == State.SUCCESS;
        joinButton.active = state == State.SUCCESS;
        this.addRenderableWidget(joinButton);\n\n        cancelButton = Button.builder(Component.translatable("netflared.status.cancel"), btn -> {\n            if (state == State.WORKING || state == State.SUCCESS) {\n                NetflaredMod.getTunnelManager().stopTunnel(profile.domain);\n                profile.running = false;\n            }\n            minecraft.gui.setScreen(parent);\n        }).bounds(centerX - 50, centerY + 75, 100, 20).build();\n        cancelButton.visible = state == State.WORKING || state == State.SUCCESS;\n        this.addRenderableWidget(cancelButton);\n\n        cancelButton = Button.builder(Component.translatable("netflared.status.cancel"), btn -> {\n            if (state == State.WORKING || state == State.SUCCESS) {\n                NetflaredMod.getTunnelManager().stopTunnel(profile.domain);\n                profile.running = false;\n            }\n            minecraft.gui.setScreen(parent);\n        }).bounds(centerX - 50, centerY + 75, 100, 20).build();\n        cancelButton.visible = state == State.WORKING || state == State.SUCCESS;\n        this.addRenderableWidget(cancelButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        String title = switch (state) {
            case WORKING -> "Setting up tunnel...";
            case SUCCESS -> "Successfully connected";
            case ERROR   -> "Tunnel setup failed";
        };
        int titleColor = switch (state) {
            case WORKING -> 0xFFFFFFFF;
            case SUCCESS -> 0xFF55FF55;
            case ERROR   -> 0xFFFF5555;
        };

        drawCentered(graphics, title, centerX, centerY - 30, titleColor);
        drawCentered(graphics, message, centerX, centerY - 10, 0xFFCCCCCC);

        if (state == State.SUCCESS) {
            drawCentered(graphics,
                    "Add server: " + profile.getJoinAddress(),
                    centerX, centerY + 10, 0xFFFFFF55);
            drawCentered(graphics,
                    "Domain: " + profile.domain,
                    centerX, centerY + 25, 0xFFA0A0A0);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        int w = this.font.width(text);
        graphics.text(this.font, text, centerX - w / 2, y, color, true);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
