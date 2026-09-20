package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.GoogleFontManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Small vanilla screen for selecting any regular Google Fonts family by name. */
public final class GoogleFontScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final Runnable fontChanged;
    private TextFieldWidget familyField;
    private ButtonWidget applyButton;
    private Text status = Text.empty();
    private int statusColor = 0xFFA0A0A0;

    public GoogleFontScreen(Screen parent, ClientPreferences preferences, Runnable fontChanged) {
        super(Text.translatable("config.fandroid.google_font.title"));
        this.parent = parent;
        this.preferences = preferences;
        this.fontChanged = fontChanged;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(420, width - 32);
        int left = (width - panelWidth) / 2;
        int fieldY = height / 2 - 34;
        familyField = new TextFieldWidget(textRenderer, left, fieldY, panelWidth, 20,
                Text.translatable("config.fandroid.google_font.field"));
        familyField.setMaxLength(80);
        familyField.setText(preferences.getGoogleFontFamily());
        addDrawableChild(familyField);

        int buttonY = fieldY + 30;
        int buttonWidth = (panelWidth - 8) / 3;
        applyButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("config.fandroid.google_font.apply"), ignored -> applyFamily())
                .dimensions(left, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("config.fandroid.google_font.minecraft"),
                ignored -> useMinecraftFont())
                .dimensions(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions(left + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
    }

    private void applyFamily() {
        String family = GoogleFontManager.normalizeFamily(familyField.getText());
        if (family.isEmpty()) {
            status = Text.translatable("config.fandroid.google_font.invalid");
            statusColor = 0xFFFF6B6B;
            return;
        }
        applyButton.active = false;
        familyField.setEditable(false);
        status = Text.translatable("config.fandroid.google_font.loading", family);
        statusColor = 0xFFFFD37A;
        GoogleFontManager.install(family, preferences.getLyricsFontWeight().cssWeight()).thenAccept(result -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            client.execute(() -> {
                applyButton.active = true;
                familyField.setEditable(true);
                if (result.success()) {
                    preferences.setGoogleFontFamily(result.family());
                    preferences.save();
                    if (fontChanged != null) fontChanged.run();
                    status = Text.translatable("config.fandroid.google_font.ready", result.family());
                    statusColor = 0xFF73E49A;
                } else {
                    status = Text.translatable("config.fandroid.google_font.failed", result.message());
                    statusColor = 0xFFFF6B6B;
                }
            });
        });
    }

    private void useMinecraftFont() {
        preferences.setGoogleFontFamily("");
        preferences.save();
        familyField.setText("");
        if (fontChanged != null) fontChanged.run();
        status = Text.translatable("config.fandroid.google_font.minecraft_active");
        statusColor = 0xFF73E49A;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        drawCentered(context, title, height / 2 - 82, 0xFFFFFFFF);
        drawCentered(context, Text.translatable("config.fandroid.google_font.hint"),
                height / 2 - 61, 0xFFB7B7B7);
        if (!status.getString().isEmpty()) drawCentered(context, status, height / 2 + 27, statusColor);
    }

    private void drawCentered(DrawContext context, Text text, int y, int color) {
        context.drawTextWithShadow(textRenderer, text, (width - textRenderer.getWidth(text)) / 2, y, color);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
