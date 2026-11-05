package dev.relaylyrics.mixin;

import dev.relaylyrics.client.music.AudioDucking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "su.plo.voice.client.audio.source.BaseClientAudioSource", remap = false)
public abstract class PlasmoVoiceDuckingMixin {
    @Inject(method = "write([SJ)V", at = @At("TAIL"), require = 0)
    private void lyrica$hearVoice(short[] samples, long sequence, CallbackInfo ci) {
        AudioDucking.heardPlasmo(samples, this);
    }
}
