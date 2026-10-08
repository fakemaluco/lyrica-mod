package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.music.LyricsAnimation;
import dev.relaylyrics.client.music.LyricsEffectRaster;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;

/** Opt-in GL screenshot fixture; it never opens during ordinary use. */
public final class LyricsEffectsProbe extends Screen {
    private static final int CANVAS_WIDTH = 800;
    private static final int CANVAS_HEIGHT = 480;
    private static final String[] SAMPLES = {
            "Take all your", "O B Ág e\u0301", "مرحبا بالعالم", "faithlessness", "partial fill", "very low alpha"
    };
    private static final double[] OPACITIES = {1, .75, .5, .25, 1, .01};
    private static final double[] GLOW_SIGNALS = {1, 1, 1, .2, 1.4, 1};
    private static final boolean[] EMPHASIZED = {false, true, false, false, true, false};
    private static final int[] SHADOW_SOFTNESS = {0, 1, 2, 2, 2, 2};
    private final LyricsBloomRenderer effects = new LyricsBloomRenderer();
    private int frames;
    private int ticks;
    private boolean captureRequested;
    private boolean captured;

    public LyricsEffectsProbe() { super(Text.literal("Lyrics effects probe")); }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        frames++;
        if (frames == 1) verifyGlyphMasks();
        effects.tick();
        int originX = (width - CANVAS_WIDTH) / 2;
        int originY = (height - CANVAS_HEIGHT) / 2;
        context.fill(0, 0, width, height, 0xFF0B0E13);
        context.drawText(textRenderer, "GLYPH EFFECTS / identical font, opacity, fill and coordinates", originX + 22,
                originY + 18, 0xFFF3F4F6, false);
        String[] labels = {"FACE", "FACE + SHADOW", "FACE + GLOW", "ALL EFFECTS"};
        for (int column = 0; column < labels.length; column++) {
            context.drawText(textRenderer, labels[column], originX + 22 + column * 194,
                    originY + 44, 0xFFABB6C7, false);
        }
        for (int row = 0; row < SAMPLES.length; row++) {
            int top = originY + 65 + row * 66;
            int background = row % 2 == 0 ? 0xFF161C25 : 0xFF778313;
            context.fill(originX + 12, top, originX + 788, top + 60, background);
            for (int column = 0; column < 4; column++) {
                drawSample(context, Text.literal(SAMPLES[row]), originX + 24 + column * 194,
                        top + 11, 2.0, OPACITIES[row], row == 4 ? .52 : 1,
                        column == 1 || column == 3, column >= 2, row);
                context.drawText(textRenderer, "alpha " + OPACITIES[row] + (row == 4 ? " / fill .52" : ""),
                        originX + 24 + column * 194, top + 46, 0xFFAEB8C8, false);
            }
        }
    }

    private static void verifyGlyphMasks() {
        var counter = LyricsGlyphRasterizer.capture(Text.literal("O"));
        if (counter == null || counter.width() < 4 || counter.height() < 4)
            throw new IllegalStateException("Could not capture loaded glyph atlas for effects");
        exportCapturedMask("counter", counter);
        int solid = 0;
        int inkLeft = counter.width(), inkRight = -1, inkTop = counter.height(), inkBottom = -1;
        for (int y = 0; y < counter.height(); y++) for (int x = 0; x < counter.width(); x++) {
            if ((counter.alpha()[y * counter.width() + x] & 255) < 128) continue;
            solid++;
            inkLeft = Math.min(inkLeft, x); inkRight = Math.max(inkRight, x);
            inkTop = Math.min(inkTop, y); inkBottom = Math.max(inkBottom, y);
        }
        // Newer font atlases retain transparent bitmap columns after the glyph.
        // The counter belongs to the center of the ink, not that larger quad.
        int centerAlpha = solid == 0 ? 255 : counter.alpha()[((inkTop + inkBottom) / 2) * counter.width()
                + (inkLeft + inkRight) / 2] & 255;
        if (solid < 4 || centerAlpha != 0)
            throw new IllegalStateException("Effects capture lost glyph silhouette/counter: solid=" + solid
                    + ", center=" + centerAlpha + ", bounds=" + counter.width() + "x" + counter.height()
                    + ", origin=" + counter.left() + "," + counter.top());
        for (int index = 0; index < SAMPLES.length; index++) {
            String text = SAMPLES[index];
            var mask = LyricsGlyphRasterizer.capture(Text.literal(text));
            if (mask == null) throw new IllegalStateException("Could not capture effects sample: " + text);
            exportCapturedMask(Integer.toString(index), mask);
        }
        FanDroidMod.LOGGER.info("Lyrics effects glyph masks passed: bitmap counters, accents, composed glyphs and RTL");
    }

    private static void exportCapturedMask(String sample, LyricsEffectRaster.Mask mask) {
        try {
            String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path directory = Path.of(System.getProperty("lyrica.effectsProbeOutput", "build/visual-qa"))
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String json = "{\"width\":" + mask.width() + ",\"height\":" + mask.height()
                    + ",\"left\":" + mask.left() + ",\"top\":" + mask.top()
                    + ",\"ppu\":" + mask.pixelsPerUnit() + ",\"alpha\":\""
                    + Base64.getEncoder().encodeToString(mask.alpha()) + "\"}";
            Files.writeString(directory.resolve("glyph-mask-" + version + "-" + sample + ".json"), json);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not export captured glyph mask", failure);
        }
    }

    private void drawSample(DrawContext context, Text text, int x, int y, double scale,
                            double opacity, double fill, boolean shadow, boolean glow, int sample) {
        int wordWidth = (int) Math.ceil(textRenderer.getWidth(text) * scale);
        int fillRight = x + (int) Math.ceil(wordWidth * fill);
        int bottom = y + (int) Math.ceil(textRenderer.fontHeight * scale) + 6;
        int color = (int) Math.round(opacity * 255) << 24 | 0xFFFFFF;
        if (glow) {
            var profile = LyricsAnimation.activeGlowProfile(GLOW_SIGNALS[sample], 1, EMPHASIZED[sample]);
            int glowColor = (int) Math.round(opacity * profile.coreOpacity() * 255) << 24 | 0xFFFFFF;
            effects.begin(context, x - 16, y - 12, x + 185, bottom + 12, false);
            effects.drawGlyph(context, textRenderer, text, x, y, scale, glowColor,
                    x - 1, y - 2, fillRight + 1, bottom, x - 1, fillRight + 1, profile.coreRadius());
            effects.finish(context, x - 16, y - 12, x + 185, bottom + 12,
                    (float) profile.coreRadius(), true, true);
        }
        if (shadow) effects.drawShadow(context, textRenderer, text, x, y, scale,
                0xFFFFFFFF, opacity * .65, 1, SHADOW_SOFTNESS[sample]);
        if (fill < 1) drawFace(context, text, x, y, scale, 0xFF718093);
        // Alpha 1-3 is intentionally omitted from the vanilla face; the generated effect
        // texture is still drawn there, checking 1.21.1's opaque-text-alpha regression.
        if ((color >>> 24) < 4) return;
        context.enableScissor(x - 2, y - 2, fillRight + 1, bottom);
        try { drawFace(context, text, x, y, scale, color); }
        finally { context.disableScissor(); }
    }

    private void drawFace(DrawContext context, Text text, int x, int y, double scale, int color) {
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, (float) scale, (float) scale);
        context.drawText(textRenderer, text, 0, 0, color, false);
        GuiTransform.pop(context);
    }

    @Override
    public void tick() {
        if (++ticks > 400 && !captured) {
            throw new IllegalStateException("Lyrics effects screenshot did not complete");
        }
        if (captureRequested || frames < 50 || ticks < 40) return;
        captureRequested = true;
        MinecraftClient client = MinecraftClient.getInstance();
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString();
                Path directory = Path.of(System.getProperty("lyrica.effectsProbeOutput", "build/visual-qa"))
                        .toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Path output = directory.resolve("lyrics-effects-" + version + ".png");
                image.writeTo(output);
                captured = true;
                FanDroidMod.LOGGER.info("Lyrics effects GL screenshot passed: {} ({}x{}, {} frames)",
                        output, image.getWidth(), image.getHeight(), frames);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not save lyrics effects screenshot", failure);
            } finally {
                client.execute(client::scheduleStop);
            }
        };
        try {
            // 1.21.1 returns the image; 1.21.11 schedules GPU readback into a callback.
            try {
                var synchronous = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class);
                writer.accept((NativeImage) synchronous.invoke(null, client.getFramebuffer()));
            } catch (NoSuchMethodException callbackApi) {
                var asynchronous = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class,
                        Consumer.class);
                asynchronous.invoke(null, client.getFramebuffer(), writer);
            }
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            throw new IllegalStateException("Could not capture lyrics effects framebuffer", cause);
        }
    }

    @Override public void removed() { effects.close(); }
    @Override public boolean shouldCloseOnEsc() { return false; }
}
