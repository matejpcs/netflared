package com.netflared.gui;

import com.netflared.NetflaredMod;
import com.netflared.config.NetflaredConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Main configuration screen for Netflared tunnel profiles. */
public class NetflaredSettingsScreen extends Screen {
    private final Screen parent;
    private final List<ProfileWidget> profileWidgets = new ArrayList<>();
    private int scrollOffset;
    private String feedbackMessage;
    private long feedbackUntil;
    private static final int ROW_HEIGHT = 28;
    private static final int LIST_TOP = 42;
    private static final int LIST_BOTTOM = 130;

    public NetflaredSettingsScreen(Screen parent) {
        super(NetflaredMod.tr("netflared.settings.title"));
        this.parent = parent;
        NetflaredMod.refreshTranslations();
    }

    public boolean isDebugUser() {
        return minecraft != null && "Mathej2301".equals(minecraft.getUser().getName());
    }

    @Override
    protected void init() {
        rebuildProfileWidgets();
    }

    private void rebuildProfileWidgets() {
        clearWidgets();
        profileWidgets.clear();

        NetflaredConfig cfg = NetflaredMod.getConfig();
        int centerX = width / 2;
        int maxScroll = Math.max(0, cfg.getProfiles().size() * ROW_HEIGHT - (LIST_BOTTOM - LIST_TOP));
        scrollOffset = Math.min(scrollOffset, maxScroll);

        for (int i = 0; i < cfg.getProfiles().size(); i++) {
            int y = LIST_TOP + i * ROW_HEIGHT - scrollOffset;
            ProfileWidget widget = new ProfileWidget(i, cfg.getProfiles().get(i), centerX, y);
            profileWidgets.add(widget);
            widget.addWidgets();
        }

        addRenderableWidget(Button.builder(
                NetflaredMod.tr("netflared.settings.add"),
                btn -> {
                    cfg.addProfile();
                    scrollOffset = Integer.MAX_VALUE;
                    rebuildProfileWidgets();
                }).bounds(centerX - 155, height - 52, 100, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("netflared.settings.save"),
                btn -> saveAndStay())
                .bounds(centerX - 50, height - 52, 100, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("netflared.settings.cancel"),
                btn -> minecraft.gui.setScreen(parent))
                .bounds(centerX + 55, height - 52, 100, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("netflared.settings.back"),
                btn -> minecraft.gui.setScreen(parent))
                .bounds(centerX - 50, height - 25, 100, 20).build());

        if (isDebugUser()) {
            addRenderableWidget(Button.builder(
                    NetflaredMod.tr("netflared.debug.button"),
                    btn -> minecraft.gui.setScreen(new NetflaredDebugScreen(this)))
                    .bounds(centerX + 55, height - 25, 100, 20).build());
        }
    }

    private void saveAndStay() {
        try {
            NetflaredMod.getConfig().save(
                    FabricLoader.getInstance().getConfigDir().resolve(NetflaredMod.MOD_ID));
            showFeedback(Component.translatable("netflared.settings.saved"), 0xFF55FF88);
        } catch (Exception e) {
            NetflaredMod.LOGGER.error("[Netflared] Failed to save settings", e);
            showFeedback(Component.translatable("netflared.settings.save_failed"), 0xFFFF5555);
        }
    }

    private void showFeedback(Component message, int color) {
        feedbackMessage = message.getString();
        feedbackUntil = System.currentTimeMillis() + 3500L;
        feedbackColor = color;
    }

    private int feedbackColor = 0xFF55FF88;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseY >= LIST_TOP && mouseY <= LIST_BOTTOM) {
            int maxScroll = Math.max(0,
                    NetflaredMod.getConfig().getProfiles().size() * ROW_HEIGHT - (LIST_BOTTOM - LIST_TOP));
            scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - deltaY * ROW_HEIGHT));
            rebuildProfileWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = width / 2;
        graphics.text(font, title, centerX - font.width(title) / 2, 15, 0xFF55FFFF, true);
        graphics.text(font, Component.translatable("netflared.settings.name"), centerX - 170, 31, 0xFFFFD166, false);
        graphics.text(font, Component.translatable("netflared.settings.domain"), centerX - 85, 31, 0xFFB8A1FF, false);
        graphics.text(font, Component.translatable("netflared.settings.port"), centerX + 55, 31, 0xFF63D7FF, false);

        for (ProfileWidget pw : profileWidgets) {
            if (pw.y + ROW_HEIGHT > LIST_TOP && pw.y < LIST_BOTTOM) {
                int bg = (pw.index % 2 == 0) ? 0x22000000 : 0x11000000;
                graphics.fill(centerX - 175, Math.max(LIST_TOP, pw.y - 3),
                        centerX + 175, Math.min(LIST_BOTTOM, pw.y + 21), bg);
            }
        }

        int maxScroll = Math.max(0,
                NetflaredMod.getConfig().getProfiles().size() * ROW_HEIGHT - (LIST_BOTTOM - LIST_TOP));
        if (maxScroll > 0) {
            int trackHeight = LIST_BOTTOM - LIST_TOP;
            int thumbHeight = Math.max(18, trackHeight * trackHeight / (trackHeight + maxScroll));
            int thumbY = LIST_TOP + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
            graphics.fill(centerX + 174, LIST_TOP, centerX + 178, LIST_BOTTOM, 0x33000000);
            graphics.fill(centerX + 174, thumbY, centerX + 178, thumbY + thumbHeight, 0xFF55FFFF);
        }

        if (feedbackMessage != null && System.currentTimeMillis() < feedbackUntil) {
            int y = height - 68;
            graphics.text(font, feedbackMessage, centerX - font.width(feedbackMessage) / 2, y, feedbackColor, true);
        } else if (feedbackMessage != null) {
            feedbackMessage = null;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private class ProfileWidget {
        final int index;
        final NetflaredConfig.Profile profile;
        final int centerX;
        final int y;
        EditBox nameBox;
        EditBox domainBox;
        EditBox portBox;
        Button connectBtn;
        Button removeBtn;

        ProfileWidget(int index, NetflaredConfig.Profile profile, int centerX, int y) {
            this.index = index;
            this.profile = profile;
            this.centerX = centerX;
            this.y = y;
        }

        void addWidgets() {
            nameBox = new EditBox(font, centerX - 170, y, 80, 18,
                    Component.translatable("netflared.settings.name"));
            nameBox.setValue(profile.name);
            nameBox.setMaxLength(64);
            nameBox.setResponder(s -> profile.name = s.trim());
            addRenderableWidget(nameBox);

            domainBox = new EditBox(font, centerX - 85, y, 130, 18,
                    Component.translatable("netflared.settings.domain"));
            domainBox.setValue(profile.domain);
            domainBox.setMaxLength(253);
            domainBox.setResponder(s -> profile.domain = s.trim());
            addRenderableWidget(domainBox);

            portBox = new EditBox(font, centerX + 55, y, 55, 18,
                    Component.translatable("netflared.settings.port"));
            portBox.setValue(Integer.toString(profile.port));
            portBox.setMaxLength(5);
            portBox.setResponder(s -> {
                if (s.matches("\\d{1,5}")) {
                    try {
                        int port = Integer.parseInt(s);
                        if (port >= 1 && port <= 65535) profile.port = port;
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            addRenderableWidget(portBox);

            connectBtn = Button.builder(
                    NetflaredMod.tr(NetflaredMod.getTunnelManager().isTunnelRunning(profile.domain)
                            ? "netflared.status.disconnect" : "netflared.status.connect"),
                    btn -> toggleTunnel(btn))
                    .bounds(centerX + 115, y, 75, 18).build();
            addRenderableWidget(connectBtn);

            removeBtn = Button.builder(Component.literal("×"), btn -> {
                if (NetflaredMod.getTunnelManager().isTunnelRunning(profile.domain)) {
                    NetflaredMod.getTunnelManager().stopTunnel(profile.domain);
                }
                NetflaredMod.getConfig().removeProfile(index);
                rebuildProfileWidgets();
            }).bounds(centerX + 195, y, 18, 18).build();
            removeBtn.active = NetflaredMod.getConfig().getProfiles().size() > 1;
            addRenderableWidget(removeBtn);

            boolean visible = y + ROW_HEIGHT > LIST_TOP && y < LIST_BOTTOM;
            nameBox.visible = domainBox.visible = portBox.visible = connectBtn.visible = removeBtn.visible = visible;
            nameBox.active = domainBox.active = portBox.active = connectBtn.active = removeBtn.active = visible;
        }

        private void toggleTunnel(Button btn) {
            var tm = NetflaredMod.getTunnelManager();
            if (tm.isTunnelRunning(profile.domain)) {
                tm.stopTunnel(profile.domain);
                profile.running = false;
                btn.setMessage(NetflaredMod.tr("netflared.status.connect"));
                return;
            }

            NetflaredStatusScreen status = new NetflaredStatusScreen(NetflaredSettingsScreen.this, profile);
            minecraft.gui.setScreen(status);
            status.connect();
        }
    }
}
