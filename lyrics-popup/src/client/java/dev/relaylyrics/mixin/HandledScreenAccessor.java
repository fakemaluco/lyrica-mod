package dev.relaylyrics.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact on-screen bounds of inventory/container GUIs. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x") int relaylyrics$getX();
    @Accessor("y") int relaylyrics$getY();
    @Accessor("backgroundWidth") int relaylyrics$getBackgroundWidth();
    @Accessor("backgroundHeight") int relaylyrics$getBackgroundHeight();
}
