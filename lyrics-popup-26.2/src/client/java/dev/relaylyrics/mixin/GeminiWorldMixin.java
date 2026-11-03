package dev.relaylyrics.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import dev.relaylyrics.client.music.WorldGeminiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelRenderer.class)
public abstract class GeminiWorldMixin {
    @Inject(method="render", at=@At("RETURN"))
    private void lyrica$worldMusic(CallbackInfo ci) { WorldGeminiRenderer.render(); }
}
