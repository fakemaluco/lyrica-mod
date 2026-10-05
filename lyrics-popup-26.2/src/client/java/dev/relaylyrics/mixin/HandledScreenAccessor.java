package dev.relaylyrics.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact on-screen bounds of inventory/container GUIs. */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
    @Accessor("leftPos") int relaylyrics$getX();
    @Accessor("topPos") int relaylyrics$getY();
    @Accessor("imageWidth") int relaylyrics$getBackgroundWidth();
    @Accessor("imageHeight") int relaylyrics$getBackgroundHeight();
}
