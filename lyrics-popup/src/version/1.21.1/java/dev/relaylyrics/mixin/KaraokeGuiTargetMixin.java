package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokePopupTarget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DrawContext.class)
abstract class KaraokeGuiTargetMixin {
    @Redirect(method = "setScissor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getFramebufferHeight()I"))
    private int lyrica$height(Window window) {
        return KaraokePopupTarget.painting() ? KaraokePopupTarget.paintHeight() : window.getFramebufferHeight();
    }

    @Redirect(method = "setScissor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getScaleFactor()D"))
    private double lyrica$scale(Window window) {
        return KaraokePopupTarget.painting() ? KaraokePopupTarget.density() : window.getScaleFactor();
    }
}
