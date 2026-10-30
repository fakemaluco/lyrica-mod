package dev.relaylyrics.client.ui;

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;

/** Inter outlines rasterized once at the actual framebuffer density, including local UI scale.
 * This avoids enlarging Minecraft's small, nearest-sampled glyph atlas. */
final class SettingsTextRaster {
    private static final Font REGULAR = load("settings_regular.ttf"), BOLD = load("settings_semibold.ttf");
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    record Raster(int width, int height, double left, double top, double density, byte[] alpha) { }

    static Raster render(String value, boolean strong, boolean compact, double scale, double density, double advance) {
        if (value == null || value.isBlank() || scale <= 0 || density <= 0 || advance <= 0)
            return new Raster(1, 1, 0, 0, 1, new byte[1]);
        double ppu = Math.min(density, 4000 / Math.max(1, advance));
        float size = (float) ((compact ? 9 : 14) * scale * ppu);
        Font face = (strong ? BOLD : REGULAR).deriveFont(size);
        AttributedString text = new AttributedString(value);
        text.addAttribute(TextAttribute.FONT, face);
        for (int at = 0; at < value.length();) {
            int cp = value.codePointAt(at), end = at + Character.charCount(cp);
            if (!face.canDisplay(cp)) text.addAttribute(TextAttribute.FONT,
                    new Font(Font.SANS_SERIF, strong ? Font.BOLD : Font.PLAIN, 1).deriveFont(size), at, end);
            at = end;
        }
        TextLayout layout = new TextLayout(text.getIterator(), FRC);
        double sx = advance * ppu / Math.max(.001, layout.getAdvance());
        Shape outline = layout.getOutline(AffineTransform.getScaleInstance(sx, 1));
        double baseline = 7 * scale * ppu;
        Rectangle2D bounds = outline.getBounds2D();
        int left = (int) Math.floor(bounds.getX()) - 1, top = (int) Math.floor(bounds.getY() + baseline) - 1;
        int width = Math.max(1, (int) Math.ceil(bounds.getMaxX()) - left + 1);
        int height = Math.max(1, (int) Math.ceil(bounds.getMaxY() + baseline) - top + 1);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.translate(-left, baseline - top); g.setColor(java.awt.Color.WHITE); g.fill(outline);
        } finally { g.dispose(); }
        byte[] alpha = new byte[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) alpha[y * width + x] = (byte) (image.getRGB(x, y) >>> 24);
        return new Raster(width, height, left / ppu, top / ppu, ppu, alpha);
    }
    private static Font load(String name) {
        try (var in = SettingsTextRaster.class.getResourceAsStream("/assets/relaylyrics/font/" + name)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) { }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    }
    private SettingsTextRaster() { }
}
