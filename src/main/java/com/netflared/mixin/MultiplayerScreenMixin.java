package com.netflared.mixin;

import com.netflared.NetflaredMod;
import com.netflared.gui.NetflaredSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "Netflared" button into the vanilla multiplayer server list
 * screen, next to the standard Add Server / Direct Connection controls.
 *
 * <p>The button opens {@link NetflaredSettingsScreen}, giving players a
 * one-click entry point to configure and connect tunnels without needing
 * to remember the F9 keybind.</p>
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void netflared$addButton(CallbackInfo ci) {
        // Position: just below the standard bottom-row buttons on the
        // multiplayer screen. The vanilla screen places Add Server /
        // Direct Connect / Cancel around y = height - 52.
        int centerX = this.width / 2;
        int y = this.height - 28;

        this.addRenderableWidget(Button.builder(
                        Component.literal("Netflared"),
                        btn -> this.minecraft.gui.setScreen(
                                new NetflaredSettingsScreen(this)))
                .bounds(5, 5, 80, 20)
                .build());
    }
}
