package dev.relaylyrics.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.relaylyrics.client.karaoke.KaraokePopupTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
abstract class KaraokeGuiTargetMixin {
    @Redirect(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget lyrica$target(GameRenderer renderer) {
        var target = KaraokePopupTarget.active();
        return target == null ? renderer.mainRenderTarget() : target;
    }

    @Redirect(method = {"draw", "enableScissor"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/GameRenderState;windowRenderState:Lnet/minecraft/client/renderer/state/WindowRenderState;"))
    private WindowRenderState lyrica$window(GameRenderState state) {
        return KaraokePopupTarget.active() == null ? state.windowRenderState : KaraokePopupTarget.window();
    }
}
