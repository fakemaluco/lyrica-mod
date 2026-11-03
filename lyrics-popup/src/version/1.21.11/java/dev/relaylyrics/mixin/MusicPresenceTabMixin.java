package dev.relaylyrics.mixin;
import dev.relaylyrics.client.MusicPresence;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerListHud.class)
public abstract class MusicPresenceTabMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void lyrica$musicIcon(PlayerListEntry player, CallbackInfoReturnable<Text> cir) {
        if (MusicPresence.track(player.getProfile().id()) != null)
            cir.setReturnValue(Text.empty().append(Text.literal("♫ ").formatted(Formatting.GREEN)).append(cir.getReturnValue()));
    }
}
