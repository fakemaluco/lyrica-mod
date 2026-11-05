package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokeClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
abstract class KaraokeCrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void lyrica$karaokeCrosshair(CallbackInfo ci) {
        if (KaraokeClient.hideVanillaCrosshair()) ci.cancel();
    }
}
