package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.PlatformTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, color-independent text cache shared across settings tabs and instances. */
final class SettingsTypography {
    private static final int MAX_ENTRIES = 384, MAX_BYTES = 24 * 1024 * 1024;
    private static final LinkedHashMap<Key, Cached> CACHE = new LinkedHashMap<>(128, .75f, true);
    private static final AtomicLong GENERATION = new AtomicLong();
    private static int bytes;
    private record Key(String text, boolean strong, boolean compact, double scale, double density, double advance) { }
    private static final class Cached {
        final Identifier id; final SettingsTextRaster.Raster raster; long lastUsed;
        Cached(Identifier id, SettingsTextRaster.Raster raster) { this.id = id; this.raster = raster; }
    }
    static void icon(GuiGraphicsExtractor context, String name, double x, double y, double size, int color) {
        draw(context, "@icon:" + name, x, y, size, false, false, 1, color);
    }
    static void draw(GuiGraphicsExtractor context, String value, double x, double y, double advance,
                     boolean strong, boolean compact, double scale, int color) {
        if (value.isBlank() || color >>> 24 == 0) return;
        double density = PlatformTextures.guiScale();
        Key key = new Key(value, strong, compact, scale, density, advance);
        Cached cached = CACHE.get(key);
        long now = System.nanoTime();
        if (cached == null) {
            var raster = value.startsWith("@icon:") ? SettingsIconRaster.render(value.substring(6), advance, density)
                    : SettingsTextRaster.render(value, strong, compact, scale, density, advance);
            Identifier id = PlatformTextures.registerUiMask("settings_text_" + GENERATION.incrementAndGet(), raster.width(), raster.height(), raster.alpha());
            cached = new Cached(id, raster); CACHE.put(key, cached); bytes += raster.width() * raster.height() * 4;
        }
        cached.lastUsed = now;
        var raster = cached.raster;
        // The GUI extractor may batch tiny translucent icons beneath a previous
        // surface when their bounds are fractional after GUI adaptation.
        if (value.startsWith("@icon:")) GuiTransform.nextLayer(context);
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) (Math.round((x + raster.left()) * density) / density),
                (float) (Math.round((y + raster.top()) * density) / density));
        GuiTransform.scale(context, (float) (1 / raster.density()), (float) (1 / raster.density()));
        PlatformTextures.drawTinted(context, cached.id, 0, 0, raster.width(), raster.height(), 0, 0,
                raster.width(), raster.height(), raster.width(), raster.height(), color);
        GuiTransform.pop(context);
        var it = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_ENTRIES || bytes > MAX_BYTES) && it.hasNext()) {
            Cached old = it.next().getValue();
            // Current GUI commands may still reference textures while the frame is being extracted.
            if (now - old.lastUsed < 1_000_000_000L) continue;
            it.remove(); bytes -= old.raster.width() * old.raster.height() * 4;
            PlatformTextures.retireEffectMask(old.id);
        }
    }
    private SettingsTypography() { }
}
