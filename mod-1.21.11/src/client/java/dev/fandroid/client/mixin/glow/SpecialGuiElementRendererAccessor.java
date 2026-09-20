package dev.fandroid.client.mixin.glow;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpecialGuiElementRenderer.class)
public interface SpecialGuiElementRendererAccessor {
    @Accessor("texture")
    GpuTexture fandroid$getTexture();

    @Accessor("textureView")
    GpuTextureView fandroid$getTextureView();
}
