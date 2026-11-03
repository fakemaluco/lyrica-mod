package dev.relaylyrics.mixin;
import dev.relaylyrics.client.MusicPresence;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerTabOverlay.class)
public abstract class MusicPresenceTabMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void lyrica$musicIcon(PlayerInfo player, CallbackInfoReturnable<Component> cir) {
        if (MusicPresence.track(player.getProfile().id()) != null)
            cir.setReturnValue(Component.empty().append(Component.literal("♫ ").withStyle(ChatFormatting.GREEN)).append(cir.getReturnValue()));
    }
}
