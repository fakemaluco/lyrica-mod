package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.karaoke.KaraokeInvitationLayout;
import dev.relaylyrics.client.karaoke.KaraokeInvitationLayout.Action;
import dev.relaylyrics.karaoke.KaraokeWire.Preview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class KaraokeInvitationRenderer {
    private static final Identifier UI_FONT = Identifier.fromNamespaceAndPath("relaylyrics", "settings");

    private KaraokeInvitationRenderer() { }

    public static void draw(GuiGraphicsExtractor context, KaraokeInvitationLayout layout,
                            double pointerX, double pointerY, boolean spotifySync, Preview preview,
                            KaraokePanelMotion.Buttons buttons, boolean animate) {
        int width = layout.width(), height = layout.height();
        int radius = Math.clamp(Math.min(width, height) / 16, 8, 16);
        RoundedGui.smoothFill(context, 0, 0, width, height, radius, 0xF21F1A2C);
        RoundedGui.smoothOutline(context, 1, 1, width - 2, height - 2, radius - 1, 0x2BD6C9F0);
        GuiTransform.nextLayer(context);

        int middle = width / 2;
        int titleY = height * 34 / 100;
        double titleScale = height < 140 ? 1.2 : 1.45;
        double artistScale = height < 140 ? .9 : 1;
        label(context, preview == null || preview.title().isBlank()
                ? Component.translatable("karaoke.lyrica.unknown_title") : Component.literal(preview.title()), middle, titleY, width - 24, titleScale, true, 0xCDEFEAF8);
        label(context, preview == null || preview.artist().isBlank()
                ? Component.translatable("karaoke.lyrica.unknown_artist") : Component.literal(preview.artist()), middle, titleY + 16, width - 24, artistScale, false, 0x9ECFC4DD);

        Action hovered = layout.actionAt(pointerX, pointerY);
        long now = System.nanoTime();
        button(context, layout, Action.DECLINE, buttons.hover(Action.DECLINE, hovered, now, animate), 1);
        button(context, layout, Action.ACCEPT, buttons.hover(Action.ACCEPT, hovered, now, animate),
                animate ? buttons.pressScale(now) : 1);
        if (spotifySync) {
            label(context, Component.translatable("karaoke.lyrica.spotify_consent"), middle, height - 15,
                    width - 22, .82, false, 0xC0E3D8F2);
        }
    }

    private static void button(GuiGraphicsExtractor context, KaraokeInvitationLayout layout, Action action, double hover, double scale) {
        int x = layout.buttonX(action), y = layout.buttonY(), radius = layout.radius();
        int size = radius * 2;
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context, x, y);
            GuiTransform.scale(context, (float) scale, (float) scale);
            GuiTransform.translate(context, -x, -y);
            RoundedGui.smoothFill(context, x - radius, y - radius, size, size, radius, 0x36171221);
            LiquidGlass.draw(context, x - radius, y - radius, size, size, radius, 1, .24 + .76 * hover);
            GuiTransform.nextLayer(context);

            int ink = SettingsMotion.mix(0xE9EEE7F9, 0xFFFFFFFF, hover);
            if (action == Action.DECLINE) PopupIcons.drawCentered(context, PopupIcons.CLOSE, x, y, radius, ink);
            else SettingsTypography.icon(context, "check", x - radius / 2.0, y - radius / 2.0, radius, ink);
        } finally {
            GuiTransform.pop(context);
        }
    }

    private static void label(GuiGraphicsExtractor context, Component value, int x, int y, int width,
                              double scale, boolean strong, int color) {
        var font = Minecraft.getInstance().font;
        java.util.function.ToIntFunction<String> measure = string -> {
            var literal = Component.literal(string);
            var styled = strong ? PlatformTextStyles.withFontBold(literal, UI_FONT) : PlatformTextStyles.withFont(literal, UI_FONT);
            return font.width(styled);
        };
        String fitted = KaraokeInvitationLayout.fitLabel(value.getString(), (int) (width / scale), measure);
        double advance = measure.applyAsInt(fitted) * scale;
        SettingsTypography.draw(context, fitted, x - advance / 2, y - 3 * scale,
                advance, strong, false, scale, color);
    }
}
