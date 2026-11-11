package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokeClient;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
abstract class KaraokeKeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void lyrica$queueKey(long window, int action, net.minecraft.client.input.KeyInput input, CallbackInfo ci) {
        if (KaraokeClient.queueKey(input.key(), action, input.modifiers())) ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void lyrica$queueCharacter(long window, net.minecraft.client.input.CharInput input, CallbackInfo ci) {
        if (KaraokeClient.queueCharacter(input.codepoint())) ci.cancel();
    }
}
