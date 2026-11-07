package dev.relaylyrics.mixin;

import dev.relaylyrics.client.karaoke.KaraokePopupTarget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
abstract class KaraokeGuiTargetMixin {
    @Redirect(method = "renderPreparedDraws", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;getFramebuffer()Lnet/minecraft/client/gl/Framebuffer;"))
    private Framebuffer lyrica$target(MinecraftClient client) {
        var target = KaraokePopupTarget.active();
        return target == null ? client.getFramebuffer() : target;
    }

    @Redirect(method = "renderPreparedDraws", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getFramebufferWidth()I"))
    private int lyrica$width(Window window) {
        var target = KaraokePopupTarget.active();
        return target == null ? window.getFramebufferWidth() : target.textureWidth;
    }

    @Redirect(method = {"renderPreparedDraws", "enableScissor"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getFramebufferHeight()I"))
    private int lyrica$height(Window window) {
        var target = KaraokePopupTarget.active();
        return target == null ? window.getFramebufferHeight() : target.textureHeight;
    }

    @Redirect(method = {"renderPreparedDraws", "enableScissor"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;getScaleFactor()I"))
    private int lyrica$scale(Window window) {
        return KaraokePopupTarget.active() == null ? window.getScaleFactor() : KaraokePopupTarget.density();
    }
}
