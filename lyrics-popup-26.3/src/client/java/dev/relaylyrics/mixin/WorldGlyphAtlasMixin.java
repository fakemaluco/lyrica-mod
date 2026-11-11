package dev.relaylyrics.mixin;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import dev.relaylyrics.client.ui.WorldGlyphAtlas;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontTexture.class)
public abstract class WorldGlyphAtlasMixin {
    @Shadow @Final private GlyphRenderTypes renderTypes;
    @Shadow @Final private boolean colored;
    @Inject(method = "add", at = @At("RETURN"))
    private void lyrica$glyphUploaded(GlyphInfo metrics, GlyphBitmap glyph,
                                     CallbackInfoReturnable<BakedSheetGlyph> cir) {
        if (cir.getReturnValue() != null)
            WorldGlyphAtlas.changed(renderTypes.normal(), ((FontTexture) (Object) this).getTexture(), colored);
    }
}
