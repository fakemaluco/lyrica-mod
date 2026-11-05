package dev.relaylyrics.mixin;

import dev.relaylyrics.client.music.PopupHudTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional, captures layout after Xaero has applied its position, scale and pushboxes. */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.module.MinimapRenderer", remap = false)
public abstract class XaeroMinimapAlignmentMixin {
    @Inject(method = "render", at = @At("TAIL"), remap = false, require = 0)
    private void lyrica$captureMinimap(@Coerce Object session, @Coerce Object context,
                                       @Coerce Object graphics, float delta, CallbackInfo ci) {
        PopupHudTargets.captureXaero(context);
    }
}
