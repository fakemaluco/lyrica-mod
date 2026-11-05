package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokeClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class KaraokeInputMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void lyrica$panelClick(CallbackInfoReturnable<Boolean> ci) {
        if (KaraokeClient.click(false)) ci.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void lyrica$panelUse(CallbackInfo ci) {
        if (KaraokeClient.click(true)) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void lyrica$panelDrag(boolean breaking, CallbackInfo ci) {
        if (breaking && KaraokeClient.consumesAttack()) ci.cancel();
    }
}
