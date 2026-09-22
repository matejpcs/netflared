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
    private Button backButton;
    private Button joinButton;
    private Button cancelButton;

    public NetflaredStatusScreen(Screen parent, NetflaredConfig.Profile profile) {
        super(NetflaredMod.tr("netflared.status.title", profile.domain));
        this.parent = parent;
        this.profile = profile;
    }

    public void updateStatus(String msg, State newState) {
        message = msg;
        state = newState;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (backButton != null) {
                backButton.visible = newState != State.WORKING;
                backButton.active = newState != State.WORKING;
            }
            if (joinButton != null) {
                joinButton.visible = newState == State.SUCCESS;
                joinButton.active = newState == State.SUCCESS;
            }
            if (cancelButton != null) {
                cancelButton.visible = newState == State.WORKING;
                cancelButton.active = newState == State.WORKING;
            }
        });
    }

    public void connect() {
        Thread thread = new Thread(() -> {
            try {
                var manager = NetflaredMod.getTunnelManager();
                if (!manager.isBinaryReady()) {
                    updateStatus(
                            Component.translatable("netflared.status.downloading").getString(),
                            State.WORKING);
                    manager.ensureBinary();
                }

                updateStatus(
                        Component.translatable("netflared.status.connecting").getString(),
                        State.WORKING);
                Process process = manager.startTunnel(profile);
                Thread.sleep(1500);

                if (process.isAlive()) {
                    updateStatus(
                            Component.translatable("netflared.status.ready").getString(),
                            State.SUCCESS);
                } else {
                    updateStatus(
                            Component.translatable("netflared.status.exited").getString(),
                            State.ERROR);
                }
            } catch (Exception e) {
                NetflaredMod.LOGGER.error("[Netflared] Tunnel failed for {}", profile.domain, e);
                String detail = e.getMessage() == null
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                updateStatus(
                        Component.translatable("netflared.status.error_detail", detail).getString(),
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

        backButton = Button.builder(
                Component.translatable("netflared.status.back"),
                btn -> minecraft.gui.setScreen(parent))
                .bounds(centerX - 105, centerY + 50, 100, 20).build();
        backButton.visible = state != State.WORKING;
        backButton.active = state != State.WORKING;
        addRenderableWidget(backButton);

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
                    NetflaredMod.getTunnelManager().stopTunnel(profile.domain);
                    profile.running = false;
                    minecraft.gui.setScreen(parent);
                })
                .bounds(centerX - 50, centerY + 75, 100, 20).build();
        cancelButton.visible = state == State.WORKING;
        cancelButton.active = state == State.WORKING;
        addRenderableWidget(cancelButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = width / 2;
        int centerY = height / 2;
        Component heading = switch (state) {
            case WORKING -> Component.translatable("netflared.status.working");
            case SUCCESS -> Component.translatable("netflared.status.connected");
            case ERROR -> Component.translatable("netflared.status.failed");
        };

        int headingColor = switch (state) {
            case WORKING -> 0xFF63D7FF;
            case SUCCESS -> 0xFF55FF88;
            case ERROR -> 0xFFFF6666;
        };

        drawCentered(graphics, heading, centerX, centerY - 34, headingColor, true);
        drawCentered(graphics, Component.literal(message), centerX, centerY - 12, 0xFFE8E8E8, false);

        if (state == State.SUCCESS) {
            drawCentered(graphics,
                    Component.translatable("netflared.status.local", profile.getJoinAddress()),
                    centerX, centerY + 12, 0xFF63D7FF, false);
            drawCentered(graphics,
                    Component.translatable("netflared.status.domain", profile.domain),
                    centerX, centerY + 27, 0xFFB8A1FF, false);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component text,
                              int centerX, int y, int color, boolean shadow) {
        graphics.text(font, text, centerX - font.width(text) / 2, y, color, shadow);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
