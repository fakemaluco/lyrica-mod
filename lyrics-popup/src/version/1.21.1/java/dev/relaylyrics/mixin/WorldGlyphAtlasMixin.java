package dev.relaylyrics.mixin;

import dev.relaylyrics.client.ui.WorldGlyphAtlas;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.font.GlyphRenderer;
import net.minecraft.client.font.RenderableGlyph;
import net.minecraft.client.font.TextRenderLayerSet;
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
    @Inject(method = "getGlyphRenderer", at = @At("RETURN"))
    private void lyrica$glyphUploaded(RenderableGlyph glyph, CallbackInfoReturnable<GlyphRenderer> cir) {
        if (cir.getReturnValue() != null) WorldGlyphAtlas.changed(textRenderLayers.normal(),
                ((GlyphAtlasTexture) (Object) this).getGlId(), hasColor);
    }
}
