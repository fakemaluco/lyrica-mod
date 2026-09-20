package dev.fandroid.client.ui;

import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.11 sprite-font style adapter. */
final class PlatformTextStyles {
    private PlatformTextStyles() { }

    static Text withFont(String value, Identifier font) {
        return Text.literal(value).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(font)));
    }

    static Text withFont(Text value, Identifier font) {
        return value.copy().setStyle(value.getStyle().withFont(new StyleSpriteSource.Font(font)));
    }

    static Text withFontBold(Text value, Identifier font) {
        return value.copy().setStyle(value.getStyle().withFont(new StyleSpriteSource.Font(font)).withBold(true));
    }
}
