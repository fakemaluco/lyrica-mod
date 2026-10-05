package dev.relaylyrics.mixin;

import dev.relaylyrics.client.InventoryLyricsController;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs before inventory helpers such as Mouse Tweaks can consume a Relay drag. */
@Mixin(value = AbstractContainerScreen.class, priority = 1200)
abstract class InventoryMouseDragMixin {
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void relaylyrics$captureDrag(MouseButtonEvent click, double deltaX, double deltaY,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (InventoryLyricsController.mouseDraggedEvent((Screen) (Object) this,
                click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
        }
    }
}
