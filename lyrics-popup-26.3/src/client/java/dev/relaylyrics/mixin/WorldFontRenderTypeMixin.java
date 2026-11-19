package dev.relaylyrics.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlyphStitcher.class)
abstract class WorldFontRenderTypeMixin {
    @Shadow @Final private Identifier texturePrefix;

    @Redirect(method = "stitch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/font/GlyphRenderTypes;createForGrayscaleTexture(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/gui/font/GlyphRenderTypes;"))
    private GlyphRenderTypes lyrica$worldFont(Identifier atlas) {
        var original = GlyphRenderTypes.createForGrayscaleTexture(atlas);
        if (!texturePrefix.getNamespace().equals("relaylyrics")
                && !texturePrefix.equals(Identifier.fromNamespaceAndPath("lyrica", "google"))) return original;
        var world = RenderType.create("lyrica_world_glyph", RenderSetup.builder(WorldPipeline.NORMAL)
                .setOitPipelines(WorldPipeline.OIT)
                .withTexture("Sampler0", atlas, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                .useLightmap()
                .sortOnUpload()
                .createRenderSetup());
        return new GlyphRenderTypes(world, original.seeThrough(), original.polygonOffset(), original.guiPipeline());
    }

    private static final class WorldPipeline {
        private static final Identifier SHADER = Identifier.fromNamespaceAndPath("relaylyrics", "core/world_glyph");
        static final RenderPipeline NORMAL = RenderPipelines.register(builder()
                .withLocation(Identifier.fromNamespaceAndPath("relaylyrics", "pipeline/world_glyph"))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .build());
        static final OitPipelineSet OIT = RenderPipelines.register(OitPipelineSet.builder("lyrica_world_glyph", builder()).build());

        private static RenderPipeline.Builder builder() {
            return RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET).withFragmentShader(SHADER);
        }
    }
}
