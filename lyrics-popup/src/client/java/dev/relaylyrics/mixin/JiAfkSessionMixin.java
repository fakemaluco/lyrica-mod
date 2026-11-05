package dev.relaylyrics.mixin;

import dev.relaylyrics.client.compat.JiAfkPlayback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.ji.afkcinematic.cinematic.CinematicManager", remap = false)
public abstract class JiAfkSessionMixin {
    @Inject(method = "onAFKTriggered()V", at = @At("TAIL"), require = 0)
    private void lyrica$resumeExternalMusic(CallbackInfo ci) {
        JiAfkPlayback.onAfkStarted();
    }
}
