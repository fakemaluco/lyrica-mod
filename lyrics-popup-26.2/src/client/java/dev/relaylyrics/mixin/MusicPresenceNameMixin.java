package dev.relaylyrics.mixin;

import dev.relaylyrics.client.MusicPresence;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class MusicPresenceNameMixin {
    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void lyrica$musicName(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (entity instanceof Player && cir.getReturnValue() != null && MusicPresence.track(entity.getUUID()) != null)
            cir.setReturnValue(Component.empty().append(Component.literal("♫ ").withStyle(ChatFormatting.GREEN)).append(cir.getReturnValue()));
    }
}
