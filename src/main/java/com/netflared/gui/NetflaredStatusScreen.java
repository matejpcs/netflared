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

/** Status screen for starting, joining, or cancelling a Netflared tunnel. */
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

    public void updateStatus(String msg, State newState) {
        message = msg;
        state = newState;
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
            if (cancelButton != null) {
                cancelButton.visible = newState == State.WORKING || newState == State.SUCCESS;
                cancelButton.active = cancelButton.visible;
            }
        });
    }

    public void connect() {
        Thread thread = new Thread(() -> {
            try {
                var manager = NetflaredMod.getTunnelManager();
                if (!manager.isBinaryReady()) {
                    updateStatus("Downloading binary...", State.WORKING);
                    manager.ensureBinary();
                }

                updateStatus("Establishing tunnel...", State.WORKING);
                Process process = manager.startTunnel(profile);
                Thread.sleep(1500);

                if (process.isAlive()) {
                    updateStatus("Successfully connected", State.SUCCESS);
                } else {
                    updateStatus("cloudflared exited unexpectedly", State.ERROR);
                }
            } catch (Exception e) {
                NetflaredMod.LOGGER.error("[Netflared] Tunnel failed for {}", profile.domain, e);
                updateStatus("Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                        State.ERROR);
            }
        }, "netflared-tunnel-setup");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int centerY = height / 2;

        okButton = Button.builder(
                Component.literal("OK"),
                btn -> minecraft.gui.setScreen(parent))
                .bounds(centerX - 105, centerY + 50, 100, 20).build();
        okButton.visible = state != State.WORKING;
        okButton.active = state != State.WORKING;
        addRenderableWidget(okButton);

        joinButton = Button.builder(
                Component.translatable("netflared.status.join"),
                btn -> {
                    ServerAddress address = ServerAddress.parseString(profile.getJoinAddress());
                    ServerData data = new ServerData(
                            profile.name, profile.getJoinAddress(), ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(this, minecraft, address, data, false, null);
                })
                .bounds(centerX + 5, centerY + 50, 100, 20).build();
        joinButton.visible = state == State.SUCCESS;
        joinButton.active = state == State.SUCCESS;
        addRenderableWidget(joinButton);

        cancelButton = Button.builder(
                Component.translatable("netflared.status.cancel"),
                btn -> {
                    if (state == State.WORKING || state == State.SUCCESS) {
                        NetflaredMod.getTunnelManager().stopTunnel(profile.domain);
                        profile.running = false;
                    }
                    minecraft.gui.setScreen(parent);
                })
                .bounds(centerX - 50, centerY + 75, 100, 20).build();
        cancelButton.visible = state == State.WORKING || state == State.SUCCESS;
        addRenderableWidget(cancelButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = width / 2;
        int centerY = height / 2;
        String title = switch (state) {
            case WORKING -> "Setting up tunnel...";
            case SUCCESS -> "Successfully connected";
            case ERROR -> "Tunnel setup failed";
        };

        drawCentered(graphics, title, centerX, centerY - 30);
        drawCentered(graphics, message, centerX, centerY - 10);

        if (state == State.SUCCESS) {
            drawCentered(graphics, "Local: " + profile.getJoinAddress(), centerX, centerY + 10);
            drawCentered(graphics, "Domain: " + profile.domain, centerX, centerY + 25);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y) {
        int x = centerX - font.width(text) / 2;
        graphics.text(font, text, x, y, 0xFFFFFFFF, true);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
