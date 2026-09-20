package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.GoogleFontManager;
import dev.fandroid.client.LyricsFontWeight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Compact vanilla screen for lyric typography and interaction preferences. */
public final class LyricsAdvancedScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final LyricsOverlay overlay;
    private final List<Option> options = new ArrayList<>();

    public LyricsAdvancedScreen(Screen parent, ClientPreferences preferences, LyricsOverlay overlay) {
        super(Text.translatable("screen.fandroid.lyrics_advanced"));
        this.parent = parent;
        this.preferences = preferences;
        this.overlay = overlay;
    }

    @Override
    protected void init() {
        options.clear();
        int gap = 8;
        int columnWidth = Math.min(310, Math.max(145, (width - 36 - gap) / 2));
        int left = (width - columnWidth * 2 - gap) / 2;
        int right = left + columnWidth + gap;
        int y = 42;
        option(left, y, columnWidth, "config.fandroid.alignment",
                () -> enumValue("config.fandroid.alignment.", preferences.getLyricsAlignment().name()),
                () -> preferences.setLyricsAlignment(preferences.getLyricsAlignment().next()));
        option(left, y + 24, columnWidth, "config.fandroid.weight",
                () -> enumValue("config.fandroid.weight.", preferences.getLyricsFontWeight().name()),
                () -> changeWeight(preferences.getLyricsFontWeight().next()));
        option(left, y + 48, columnWidth, "settings.fandroid.font", this::fontValue,
                () -> MinecraftClient.getInstance().setScreen(
                        new GoogleFontScreen(this, preferences, overlay::fontChanged)));
        option(left, y + 72, columnWidth, "config.fandroid.glow",
                () -> enumValue("config.fandroid.glow.", preferences.getLyricsGlowIntensity().name()),
                () -> preferences.setLyricsGlowIntensity(preferences.getLyricsGlowIntensity().next()));
        option(left, y + 96, columnWidth, "config.fandroid.transition",
                () -> enumValue("config.fandroid.transition.", preferences.getLyricsTransitionSpeed().name()),
                () -> preferences.setLyricsTransitionSpeed(preferences.getLyricsTransitionSpeed().next()));

        option(right, y, columnWidth, "config.fandroid.click_seek",
                () -> toggleValue(preferences.isLyricsClickSeek()),
                () -> preferences.setLyricsClickSeek(!preferences.isLyricsClickSeek()));
        option(right, y + 24, columnWidth, "settings.fandroid.shadow",
                () -> toggleValue(preferences.isLyricsTextShadow()),
                () -> preferences.setLyricsTextShadow(!preferences.isLyricsTextShadow()));
        option(right, y + 48, columnWidth, "config.fandroid.shadow_opacity",
                () -> Text.literal(preferences.getLyricsShadowOpacity() + "%"),
                () -> preferences.setLyricsShadowOpacity((preferences.getLyricsShadowOpacity() + 10) % 110));
        option(right, y + 72, columnWidth, "config.fandroid.shadow_distance",
                () -> Text.literal(preferences.getLyricsShadowDistance() + " px"),
                () -> preferences.setLyricsShadowDistance(preferences.getLyricsShadowDistance() % 4 + 1));
        option(right, y + 96, columnWidth, "config.fandroid.shadow_softness",
                () -> enumValue("config.fandroid.shadow_softness.",
                        Integer.toString(preferences.getLyricsShadowSoftness())),
                () -> preferences.setLyricsShadowSoftness((preferences.getLyricsShadowSoftness() + 1) % 3));
        option(right, y + 120, columnWidth, "config.fandroid.allow_gui_overlap",
                () -> toggleValue(preferences.isLyricsAllowGuiOverlap()),
                () -> preferences.setLyricsAllowGuiOverlap(!preferences.isLyricsAllowGuiOverlap()));

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions((width - 200) / 2, height - 28, 200, 20).build());
    }

    private void option(int x, int y, int width, String labelKey, Supplier<Text> value, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(message(labelKey, value.get()), ignored -> {
            action.run();
            preferences.save();
            refresh();
        }).dimensions(x, y, width, 20).build();
        options.add(new Option(button, labelKey, value));
        addDrawableChild(button);
    }

    private void changeWeight(LyricsFontWeight weight) {
        preferences.setLyricsFontWeight(weight);
        String family = preferences.getGoogleFontFamily();
        if (family.isEmpty()) {
            overlay.fontChanged();
            return;
        }
        GoogleFontManager.install(family, weight.cssWeight()).thenAccept(result -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (result.success() && client != null) client.execute(overlay::fontChanged);
        });
    }

    private Text fontValue() {
        return preferences.getGoogleFontFamily().isEmpty()
                ? Text.translatable("config.fandroid.google_font.minecraft")
                : Text.literal(preferences.getGoogleFontFamily());
    }

    private static Text enumValue(String prefix, String value) {
        return Text.translatable(prefix + value.toLowerCase());
    }

    private static Text toggleValue(boolean enabled) {
        return Text.translatable(enabled ? "options.on" : "options.off");
    }

    private static Text message(String labelKey, Text value) {
        return Text.translatable(labelKey).append(": ").append(value);
    }

    private void refresh() {
        for (Option option : options) {
            option.button.setMessage(message(option.labelKey, option.value.get()));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        preferences.save();
        MinecraftClient.getInstance().setScreen(parent);
    }

    private record Option(ButtonWidget button, String labelKey, Supplier<Text> value) { }
}
