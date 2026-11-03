package dev.relaylyrics.mixin;

import dev.relaylyrics.client.MusicPresence;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class MusicPresenceNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void lyrica$musicName(Entity entity, CallbackInfoReturnable<Text> cir) {
        if (entity instanceof PlayerEntity && cir.getReturnValue() != null && MusicPresence.track(entity.getUuid()) != null)
            cir.setReturnValue(Text.empty().append(Text.literal("♫ ").formatted(Formatting.GREEN)).append(cir.getReturnValue()));
    }
}
