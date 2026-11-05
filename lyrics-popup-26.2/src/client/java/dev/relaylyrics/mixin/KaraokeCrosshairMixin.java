package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokeClient;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class KaraokeCrosshairMixin {
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void lyrica$karaokeCrosshair(CallbackInfo ci) {
        if (KaraokeClient.hideVanillaCrosshair()) ci.cancel();
    }
}
