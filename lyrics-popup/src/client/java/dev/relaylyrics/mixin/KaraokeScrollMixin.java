package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokeClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
abstract class KaraokeScrollMixin {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void lyrica$scrollFloatingPopup(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (KaraokeClient.scrollPopup(vertical)) ci.cancel();
    }
}
