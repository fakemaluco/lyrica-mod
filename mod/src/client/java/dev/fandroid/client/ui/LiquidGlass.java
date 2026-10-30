package dev.fandroid.client.ui;

import dev.fandroid.client.music.GlassButtonRaster;
import dev.fandroid.client.music.PlatformTextures;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Spicy Mobile glass chrome. No framebuffer reads, new textures or CPU rasterization on hover. */
public final class LiquidGlass {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final LinkedHashMap<Key, Cached> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static int bytes;
    private record Key(int width, int height, int radius, double density) { }
    private static final class Cached {
        final Identifier base, light; final GlassButtonRaster.Surface surface; long used;
        Cached(Identifier base, Identifier light, GlassButtonRaster.Surface surface) { this.base = base; this.light = light; this.surface = surface; }
    }

    public static void draw(DrawContext context, int x, int y, int width, int height, int radius,
                            double opacity, double engagement) {
        if (width < 1 || height < 1 || opacity <= .001) return;
        double density = Math.clamp(Math.ceil(PlatformTextures.guiScale() * 4) / 4, .5, 4);
        Key key = new Key(width, height, radius, density);
        Cached cached = CACHE.get(key);
        long now = System.nanoTime();
        if (cached == null) {
            var raster = GlassButtonRaster.render(width, height, radius, density);
            cached = new Cached(upload(raster, raster.base()), upload(raster, raster.light()), raster);
            CACHE.put(key, cached); bytes += raster.width() * raster.height() * 8;
        }
        cached.used = now;
        var raster = cached.surface;
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) (x - raster.padding()), (float) (y - raster.padding()));
        GuiTransform.scale(context, (float) (1 / density), (float) (1 / density));
        try {
            PlatformTextures.draw(context, cached.base, 0, 0, raster.width(), raster.height(), 0, 0,
                    raster.width(), raster.height(), raster.width(), raster.height(), opacity, 1);
            if (engagement > .001) PlatformTextures.draw(context, cached.light, 0, 0, raster.width(), raster.height(), 0, 0,
                    raster.width(), raster.height(), raster.width(), raster.height(), opacity * Math.clamp(engagement, 0, 1), 1);
        } finally { GuiTransform.pop(context); }
        var it = CACHE.entrySet().iterator();
        while ((CACHE.size() > 48 || bytes > 8 * 1024 * 1024) && it.hasNext()) {
            var old = it.next().getValue();
            if (now - old.used < 1_000_000_000L) continue;
            it.remove(); bytes -= old.surface.width() * old.surface.height() * 8;
            PlatformTextures.retireEffectMask(old.base); PlatformTextures.retireEffectMask(old.light);
        }
    }

    private static Identifier upload(GlassButtonRaster.Surface surface, int[] pixels) {
        var texture = PlatformTextures.empty("Spicy glass", surface.width(), surface.height());
        var image = texture.getImage();
        for (int y = 0; y < surface.height(); y++) for (int x = 0; x < surface.width(); x++)
            PlatformTextures.setUiPixel(image, x, y, pixels[y * surface.width() + x]);
        PlatformTextures.upload(texture);
        return PlatformTextures.register("liquid_glass_" + GENERATION.incrementAndGet(), texture);
    }
    private LiquidGlass() { }
}
