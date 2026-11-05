package dev.relaylyrics.mixin;

import dev.relaylyrics.client.compat.JiAfkPlayback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.ji.afkcinematic.music.CinematicMusicManager", remap = false)
public abstract class JiAfkMusicMixin {
    @Inject(method = {"checkAndPlayMusic()V", "playCinematicMusicSafe"}, at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void lyrica$preventMixedPlayback(CallbackInfo ci) {
        if (JiAfkPlayback.blocksJiMusic()) ci.cancel();
    }
}
