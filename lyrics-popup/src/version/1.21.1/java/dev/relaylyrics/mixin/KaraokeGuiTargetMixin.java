package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokePopupTarget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static dev.relaylyrics.karaoke.KaraokeWire.HEIGHT;

@Mixin(DrawContext.class)
abstract class KaraokeGuiTargetMixin {
    @Redirect(method = "setScissor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getFramebufferHeight()I"))
    private int lyrica$height(Window window) {
        return KaraokePopupTarget.painting() ? HEIGHT * KaraokePopupTarget.DENSITY : window.getFramebufferHeight();
    }

    @Redirect(method = "setScissor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getScaleFactor()D"))
    private double lyrica$scale(Window window) {
        return KaraokePopupTarget.painting() ? KaraokePopupTarget.DENSITY : window.getScaleFactor();
    }
}
