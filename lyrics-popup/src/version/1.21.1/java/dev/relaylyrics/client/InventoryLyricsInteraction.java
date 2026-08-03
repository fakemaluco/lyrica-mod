package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.LyricsConfigScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screen.Screen;

/** Fabric Screen API adapter for Minecraft 1.21.1. */
public final class InventoryLyricsInteraction {
    private InventoryLyricsInteraction() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof LyricsConfigScreen config) {
                ScreenMouseEvents.allowMouseClick(screen).register((current, mouseX, mouseY, button) ->
                        !config.handleSecondaryClick(mouseX, mouseY, button));
                if (!handles(screen)) return;
            }
            if (!handles(screen)) return;
            boolean firstRegistration = InventoryLyricsController.attach(screen);
            if (!firstRegistration) return;
            ScreenEvents.afterRender(screen).register((current, context, mouseX, mouseY, delta) ->
                    InventoryLyricsController.render(current, context, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((current, mouseX, mouseY, button) ->
                    !InventoryLyricsController.mouseClicked(current, mouseX, mouseY, button));
            ScreenMouseEvents.allowMouseRelease(screen).register((current, mouseX, mouseY, button) ->
                    !InventoryLyricsController.mouseReleased(current, mouseX, mouseY, button));
            ScreenMouseEvents.allowMouseScroll(screen).register((current, mouseX, mouseY, horizontal, vertical) ->
                    !InventoryLyricsController.mouseScrolled(current, mouseX, mouseY, horizontal, vertical));
        });
    }

    public static boolean handles(Screen screen) {
        return screen != null && InventoryLyricsController.supports(screen);
    }
}
