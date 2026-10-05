package dev.relaylyrics.client.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** Minecraft 1.21.11 sprite-font style adapter. */
final class PlatformTextStyles {
    private PlatformTextStyles() { }

    static Component withFont(String value, Identifier font) {
        return Component.literal(value).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(font)));
    }

    static Component withFont(Component value, Identifier font) {
        return value.copy().setStyle(value.getStyle().withFont(new FontDescription.Resource(font)));
    }

    static Component withFontBold(Component value, Identifier font) {
        return value.copy().setStyle(value.getStyle().withFont(new FontDescription.Resource(font)).withBold(true));
    }
}
