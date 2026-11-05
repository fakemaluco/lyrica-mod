package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokePlatform;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class KaraokeHiddenHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void lyrica$paintKaraokeWithoutHud(CallbackInfo ci) {
        KaraokePlatform.paintPopups(true);
    }
}
