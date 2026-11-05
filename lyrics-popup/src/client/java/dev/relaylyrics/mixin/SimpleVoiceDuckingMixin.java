package dev.relaylyrics.mixin;

import dev.relaylyrics.client.music.AudioDucking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe decoded audio delivered to the voice speaker, with the channel's current volume. */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.speaker.ALSpeakerBase", remap = false)
public abstract class SimpleVoiceDuckingMixin {
    @Inject(method = {
            "writeSync([SFLnet/minecraft/class_243;Ljava/lang/String;F)V",
            "writeSync([SFLnet/minecraft/util/math/Vec3d;Ljava/lang/String;F)V",
            "writeSync([SFLnet/minecraft/world/phys/Vec3;Ljava/lang/String;F)V"
    }, at = @At("TAIL"), require = 0)
    private void lyrica$hearVoice(short[] audio, float volume, @Coerce Object position,
                                  String category, float maxDistance, CallbackInfo ci) {
        AudioDucking.heard(audio, volume);
    }
}
