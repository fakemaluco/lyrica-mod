package dev.relaylyrics.mixin;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.relaylyrics.client.WorldLyricsRenderHook;
import dev.relaylyrics.client.music.WorldGeminiRenderer;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldRenderer.class)
public abstract class GeminiWorldMixin {
    @Inject(method = "renderMain", at = @At("HEAD"))
    private void lyrica$captureKaraokeFrustum(FrameGraphBuilder graph, Frustum frustum, Matrix4f position,
                                             GpuBufferSlice fog, boolean renderBlockOutline, WorldRenderState state,
                                             RenderTickCounter tickCounter, Profiler profiler, CallbackInfo ci) {
        WorldLyricsRenderHook.captureKaraokeFrustum(frustum);
    }

    @Inject(method="render", at=@At("RETURN"))
    private void lyrica$worldMusic(CallbackInfo ci) { WorldGeminiRenderer.render(); }
}
