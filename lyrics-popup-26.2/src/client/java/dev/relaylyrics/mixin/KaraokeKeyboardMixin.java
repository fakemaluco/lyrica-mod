package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokeClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KaraokeKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void lyrica$queueKey(long window, int action, net.minecraft.client.input.KeyEvent input, CallbackInfo ci) {
        if (KaraokeClient.queueKey(input.key(), action, input.modifiers())) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void lyrica$queueCharacter(long window, net.minecraft.client.input.CharacterEvent input, CallbackInfo ci) {
        if (KaraokeClient.queueCharacter(input.codepoint())) ci.cancel();
    }
}
