package dev.relaylyrics.mixin;

import dev.relaylyrics.client.RelayLyricsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional integration, loaded only if Ji's letterbox exists. No hard dependency. */
@Pseudo
@Mixin(targets = "com.ji.afkcinematic.render.LetterboxRenderer", remap = false)
public abstract class JiCinematicLyricsMixin {
    @Inject(method = "render", at = @At("TAIL"), remap = false, require = 0)
    private static void lyrica$caption(@Coerce Object context, float delta, CallbackInfo ci) {
        var overlay = RelayLyricsClient.overlay();
        if (overlay != null) overlay.renderCinematicCaption((net.minecraft.client.gui.DrawContext) context);
    }
}
