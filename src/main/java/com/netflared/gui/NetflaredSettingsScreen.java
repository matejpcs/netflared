package com.netflared.gui;

import com.netflared.NetflaredMod;
import com.netflared.config.NetflaredConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Main configuration screen for Netflared.
 *
 * <p>Shows a scrollable list of tunnel profiles. Each profile has:
 * domain input, local port input, Connect/Disconnect button, and a
 * remove button. A "+ Add Server" button appends new profiles.</p>
 *
 * <p>On save, all profiles are synced into the vanilla multiplayer
 * server list as "Netflared Server N" (editable by the player).</p>
 */
public class NetflaredSettingsScreen extends Screen {

    private final Screen parent;
    private final List<ProfileWidget> profileWidgets = new ArrayList<>();
    private EditBox dummy; // keeps focus handling sane

    private int scrollOffset = 0;
    private static final int ROW_HEIGHT = 26;

    public NetflaredSettingsScreen(Screen parent) {
        super(Component.literal("Netflared Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        profileWidgets.clear();

        NetflaredConfig cfg = NetflaredMod.getConfig();
        int centerX = this.width / 2;
        int top = 40;

        // Build one row of widgets per profile.
        for (int i = 0; i < cfg.getProfiles().size(); i++) {
            NetflaredConfig.Profile profile = cfg.getProfiles().get(i);
            ProfileWidget pw = new ProfileWidget(i, profile, centerX, top + i * ROW_HEIGHT);
            profileWidgets.add(pw);
            pw.addWidgets();
        }

        // "+ Add Server" button — always at the bottom.
        int listBottom = top + Math.max(1, profileWidgets.size()) * ROW_HEIGHT;
        this.addRenderableWidget(Button.builder(
                        Component.literal("+ Add Server"),
                        btn -> {
                            cfg.addProfile();
                            this.rebuildWidgets();
                        })
                .bounds(centerX - 100, listBottom + 10, 90, 20)
                .build());

        // Save & Sync button.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Save & Sync"),
                        btn -> {
                            cfg.save(NetflaredMod.getInstance() != null
                                    ? this.minecraft.gameDirectory.toPath().resolve("config").resolve("netflared")
                                    : null);
                            this.minecraft.gui.setScreen(parent);
                        })
                .bounds(centerX - 100, listBottom + 35, 90, 20)
                .build());

        // Cancel button.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Cancel"),
                        btn -> this.minecraft.gui.setScreen(parent))
                .bounds(centerX + 10, listBottom + 35, 90, 20)
                .build());

        // Back button.
        this.addRenderableWidget(Button.builder(
                        Component.literal("Back"),
                        btn -> this.minecraft.gui.setScreen(parent))
                .bounds(centerX - 50, this.height - 30, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        // Title
        graphics.text(this.font, "Netflared Settings", centerX - this.font.width("Netflared Settings") / 2, 15, 0xFFFFFFFF, true);

        // Column headers
        int top = 28;
        graphics.text(this.font, "Name",   centerX - 160, top, 0xFFAAAAAA, false);
        graphics.text(this.font, "Domain", centerX - 90,  top, 0xFFAAAAAA, false);
        graphics.text(this.font, "Port",   centerX + 60,  top, 0xFFAAAAAA, false);

        // Profile rows
        for (ProfileWidget pw : profileWidgets) {
            pw.render(graphics, mouseX, mouseY, delta);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    // ------------------------------------------------------------------
    // Inner widget holder for one profile row
    // ------------------------------------------------------------------

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
            nameBox = new EditBox(NetflaredSettingsScreen.this.font, centerX - 160, y, 80, 18,
                    Component.literal("Name"));
            nameBox.setValue(profile.name);
            nameBox.setResponder(s -> profile.name = s);
            NetflaredSettingsScreen.this.addRenderableWidget(nameBox);

            domainBox = new EditBox(NetflaredSettingsScreen.this.font, centerX - 75, y, 130, 18,
                    Component.literal("play.example.com"));
            domainBox.setValue(profile.domain);
            domainBox.setResponder(s -> profile.domain = s);
            NetflaredSettingsScreen.this.addRenderableWidget(domainBox);

            portBox = new EditBox(NetflaredSettingsScreen.this.font, centerX + 60, y, 50, 18,
                    Component.literal("25565"));
            portBox.setValue(String.valueOf(profile.port));
            portBox.setResponder(s -> {
                try {
                    profile.port = Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) { /* keep last valid */ }
            });
            NetflaredSettingsScreen.this.addRenderableWidget(portBox);

            // Connect / Disconnect toggle.
            connectBtn = Button.builder(
                            Component.literal(NetflaredMod.getTunnelManager().isTunnelRunning(profile.domain)
                                    ? "Disconnect" : "Connect"),
                            btn -> {
                                var tm = NetflaredMod.getTunnelManager();
                                if (tm.isTunnelRunning(profile.domain)) {
                                    tm.stopTunnel(profile.domain);
                                    profile.running = false;
                                    btn.setMessage(Component.literal("Connect"));
                                } else {
                                    // Show status screen and connect in background.
                                    NetflaredStatusScreen status = new NetflaredStatusScreen(
                                            NetflaredSettingsScreen.this, profile);
                                    NetflaredSettingsScreen.this.minecraft.gui.setScreen(status);
                                    status.connect();
                                    btn.setMessage(Component.literal("Disconnect"));
                                }
                            })
                    .bounds(centerX + 115, y, 70, 18)
                    .build();
            NetflaredSettingsScreen.this.addRenderableWidget(connectBtn);

            // Remove button (only if more than one profile).
            removeBtn = Button.builder(
                            Component.literal("X"),
                            btn -> {
                                NetflaredMod.getConfig().removeProfile(index);
                                NetflaredSettingsScreen.this.rebuildWidgets();
                            })
                    .bounds(centerX + 190, y, 18, 18)
                    .build();
            removeBtn.active = NetflaredMod.getConfig().getProfiles().size() > 1;
            NetflaredSettingsScreen.this.addRenderableWidget(removeBtn);
        }

        void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            // Row background stripe for readability.
            int bg = (index % 2 == 0) ? 0x22000000 : 0x11000000;
            graphics.fill(centerX - 170, y - 2, centerX + 215, y + 20, bg);
        }
    }
}
