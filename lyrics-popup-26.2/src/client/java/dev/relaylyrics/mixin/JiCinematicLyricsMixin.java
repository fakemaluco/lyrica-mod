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
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At("TAIL"), remap = false, require = 0)
    private static void lyrica$caption(@Coerce Object context, float delta, CallbackInfo ci) {
        var overlay = RelayLyricsClient.overlay();
        if (overlay != null) overlay.renderCinematicCaption((net.minecraft.client.gui.GuiGraphicsExtractor) context);
    }

    // The development branch removed tickDelta. Match descriptors explicitly so a missing
    // optional overload is skipped rather than failing injection against a different signature.
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("TAIL"), remap = false, require = 0)
    private static void lyrica$captionWithoutDelta(@Coerce Object context, CallbackInfo ci) {
        var overlay = RelayLyricsClient.overlay();
        if (overlay != null) overlay.renderCinematicCaption((net.minecraft.client.gui.GuiGraphicsExtractor) context);
    }
}
