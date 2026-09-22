package com.netflared.gui;

import com.netflared.NetflaredMod;
import com.netflared.config.NetflaredConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Internal-only debug menu. It is exposed only to the configured development username.
 * These actions exercise UI states without starting a real cloudflared process.
 */
public class NetflaredDebugScreen extends Screen {
    private final Screen parent;
    private final NetflaredConfig.Profile testProfile =
            new NetflaredConfig.Profile("Debug Server", "debug.example.com", 25565);

    public NetflaredDebugScreen(Screen parent) {
        super(NetflaredMod.tr("netflared.debug.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int top = height / 2 - 55;

        addRenderableWidget(Button.builder(
                NetflaredMod.tr("netflared.debug.working"),
                btn -> openStatus(NetflaredStatusScreen.State.WORKING,
                        NetflaredMod.tr("netflared.debug.working_message")))
                .bounds(centerX - 100, top, 200, 20).build());

        addRenderableWidget(Button.builder(
                NetflaredMod.tr("netflared.debug.success"),
                btn -> openStatus(NetflaredStatusScreen.State.SUCCESS,
                        NetflaredMod.tr("netflared.debug.success_message")))
                .bounds(centerX - 100, top + 25, 200, 20).build());

        addRenderableWidget(Button.builder(
                NetflaredMod.tr("netflared.debug.error"),
                btn -> openStatus(NetflaredStatusScreen.State.ERROR,
                        NetflaredMod.tr("netflared.debug.error_message")))
                .bounds(centerX - 100, top + 50, 200, 20).build());

        addRenderableWidget(Button.builder(
                NetflaredMod.tr("netflared.debug.settings"),
                btn -> minecraft.gui.setScreen(new NetflaredSettingsScreen(this)))
                .bounds(centerX - 100, top + 75, 200, 20).build());

        addRenderableWidget(Button.builder(
                NetflaredMod.tr("netflared.settings.back"),
                btn -> minecraft.gui.setScreen(parent))
                .bounds(centerX - 100, top + 105, 200, 20).build());
    }

    private void openStatus(NetflaredStatusScreen.State state, Component message) {
        NetflaredStatusScreen screen = new NetflaredStatusScreen(this, testProfile);
        screen.updateStatus(message.getString(), state);
        minecraft.gui.setScreen(screen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = width / 2;
        int titleY = height / 2 - 85;
        graphics.text(font, title, centerX - font.width(title) / 2, titleY, 0xFFFFD166, true);

        Component subtitle = NetflaredMod.tr("netflared.debug.subtitle");
        graphics.text(font, subtitle, centerX - font.width(subtitle) / 2,
                titleY + 18, 0xFFAAAAAA, false);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
