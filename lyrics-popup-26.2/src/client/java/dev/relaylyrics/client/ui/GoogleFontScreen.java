package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.GoogleFontManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small vanilla screen for selecting any regular Google Fonts family by name. */
public final class GoogleFontScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final Runnable fontChanged;
    private EditBox familyField;
    private Button applyButton;
    private Component status = Component.empty();
    private int statusColor = 0xFFA0A0A0;

    public GoogleFontScreen(Screen parent, ClientPreferences preferences, Runnable fontChanged) {
        super(Component.translatable("config.relaylyrics.google_font.title"));
        this.parent = parent;
        this.preferences = preferences;
        this.fontChanged = fontChanged;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(420, width - 32);
        int left = (width - panelWidth) / 2;
        int fieldY = height / 2 - 34;
        familyField = new EditBox(font, left, fieldY, panelWidth, 20,
                Component.translatable("config.relaylyrics.google_font.field"));
        familyField.setMaxLength(80);
        familyField.setValue(preferences.getGoogleFontFamily());
        addRenderableWidget(familyField);

        int buttonY = fieldY + 30;
        int buttonWidth = (panelWidth - 8) / 3;
        applyButton = addRenderableWidget(Button.builder(
                Component.translatable("config.relaylyrics.google_font.apply"), ignored -> applyFamily())
                .bounds(left, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("config.relaylyrics.google_font.minecraft"),
                ignored -> useMinecraftFont())
                .bounds(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
    }

    private void applyFamily() {
        String family = GoogleFontManager.normalizeFamily(familyField.getValue());
        if (family.isEmpty()) {
            status = Component.translatable("config.relaylyrics.google_font.invalid");
            statusColor = 0xFFFF6B6B;
            return;
        }
        applyButton.active = false;
        familyField.setEditable(false);
        status = Component.translatable("config.relaylyrics.google_font.loading", family);
        statusColor = 0xFFFFD37A;
        GoogleFontManager.install(family, preferences.getLyricsFontWeight().cssWeight()).thenAccept(result -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                applyButton.active = true;
                familyField.setEditable(true);
                if (result.success()) {
                    preferences.setGoogleFontFamily(result.family());
                    preferences.save();
                    if (fontChanged != null) fontChanged.run();
                    status = Component.translatable("config.relaylyrics.google_font.ready", result.family());
                    statusColor = 0xFF73E49A;
                } else {
                    status = Component.translatable("config.relaylyrics.google_font.failed", result.message());
                    statusColor = 0xFFFF6B6B;
                }
            });
        });
    }

    private void useMinecraftFont() {
        preferences.setGoogleFontFamily("");
        preferences.save();
        familyField.setValue("");
        if (fontChanged != null) fontChanged.run();
        status = Component.translatable("config.relaylyrics.google_font.minecraft_active");
        statusColor = 0xFF73E49A;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        drawCentered(context, title, height / 2 - 82, 0xFFFFFFFF);
        drawCentered(context, Component.translatable("config.relaylyrics.google_font.hint"),
                height / 2 - 61, 0xFFB7B7B7);
        if (!status.getString().isEmpty()) drawCentered(context, status, height / 2 + 27, statusColor);
    }

    private void drawCentered(GuiGraphicsExtractor context, Component text, int y, int color) {
        context.text(font, text, (width - font.width(text)) / 2, y, color);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
