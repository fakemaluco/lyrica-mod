package dev.relaylyrics.mixin;

import dev.relaylyrics.client.ui.WorldGlyphAtlas;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.font.GlyphMetrics;
import net.minecraft.client.font.BakedGlyphImpl;
import net.minecraft.client.font.UploadableGlyph;
import net.minecraft.client.font.TextRenderLayerSet;
import net.minecraft.client.texture.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlyphAtlasTexture.class)
public abstract class WorldGlyphAtlasMixin {
    @Shadow @Final private TextRenderLayerSet textRenderLayers;
    @Shadow @Final private boolean hasColor;
    @Inject(method = "bake", at = @At("RETURN"))
    private void lyrica$glyphUploaded(GlyphMetrics metrics, UploadableGlyph glyph,
                                     CallbackInfoReturnable<BakedGlyphImpl> cir) {
        if (cir.getReturnValue() != null && ((GlyphAtlasTexture) (Object) this).getGlTexture() instanceof GlTexture texture)
            WorldGlyphAtlas.changed(textRenderLayers.normal(), texture.getGlId(), hasColor);
    }
}
