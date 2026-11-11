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
    private void lyrica$queueKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (KaraokeClient.queueKey(key, action, modifiers)) ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void lyrica$queueCharacter(long window, int codepoint, int modifiers, CallbackInfo ci) {
        if (KaraokeClient.queueCharacter(codepoint)) ci.cancel();
    }
}
