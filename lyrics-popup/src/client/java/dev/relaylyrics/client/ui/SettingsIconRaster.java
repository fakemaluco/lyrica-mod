package dev.relaylyrics.client.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Small vector controls sampled at framebuffer density, with round joins and caps. */
final class SettingsIconRaster {
    static SettingsTextRaster.Raster render(String name, double size, double density) {
        int pixels = Math.max(1, (int) Math.ceil(size * density));
        var image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(pixels / 14.0, pixels / 14.0); g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            var p = new Path2D.Double();
            switch (name) {
                case "close" -> { p.moveTo(3, 3); p.lineTo(11, 11); p.moveTo(11, 3); p.lineTo(3, 11); g.draw(p); }
                case "search" -> { g.draw(new Ellipse2D.Double(2, 2, 7.5, 7.5)); p.moveTo(8.5, 8.5); p.lineTo(12, 12); g.draw(p); }
                case "eye" -> { p.moveTo(1, 7); p.curveTo(4, 2, 10, 2, 13, 7); p.curveTo(10, 12, 4, 12, 1, 7); g.draw(p); g.draw(new Ellipse2D.Double(5, 5, 4, 4)); }
                case "GENERAL" -> { for (int i = 0; i < 3; i++) { double y = 3 + i * 4; p.moveTo(1.5, y); p.lineTo(12.5, y); } g.draw(p); for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Double(i == 1 ? 8 : 3, 1.5 + i * 4, 3, 3)); }
                case "APPEARANCE" -> { g.draw(new Ellipse2D.Double(1.5, 1.5, 11, 11)); g.fill(new Ellipse2D.Double(4, 4, 6, 6)); }
                case "CUSTOM" -> { g.fill(new RoundRectangle2D.Double(2, 8, 2, 4, 1, 1)); g.fill(new RoundRectangle2D.Double(6, 4, 2, 8, 1, 1)); g.fill(new RoundRectangle2D.Double(10, 1, 2, 11, 1, 1)); }
                case "CONNECTION" -> { g.rotate(-.55, 7, 7); g.draw(new RoundRectangle2D.Double(2, 1.5, 7, 7, 4, 4)); g.draw(new RoundRectangle2D.Double(5, 5.5, 7, 7, 4, 4)); }
                case "SOURCES" -> { for (int i = 0; i < 3; i++) { double y = 3 + i * 4; p.moveTo(5, y); p.lineTo(12, y); g.fill(new Ellipse2D.Double(1, y - 1, 2, 2)); } g.draw(p); }
                case "ADVANCED" -> { g.draw(new Ellipse2D.Double(3, 3, 8, 8)); for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; p.moveTo(7 + 4 * Math.cos(a), 7 + 4 * Math.sin(a)); p.lineTo(7 + 5.5 * Math.cos(a), 7 + 5.5 * Math.sin(a)); } g.draw(p); g.draw(new Ellipse2D.Double(5.5, 5.5, 3, 3)); }
                case "WORLD" -> { g.draw(new RoundRectangle2D.Double(1.5, 1.5, 11, 11, 2, 2)); p.moveTo(7, 2); p.lineTo(7, 12); p.moveTo(2, 7); p.lineTo(12, 7); g.draw(p); }
                default -> { if (name.startsWith("chevron:")) { double t = Integer.parseInt(name.substring(8)) / 16.0, sign = 1 - 2 * t; p.moveTo(3, 7 - 2 * sign); p.lineTo(7, 7 + 2 * sign); p.lineTo(11, 7 - 2 * sign); g.draw(p); } }
            }
        } finally { g.dispose(); }
        byte[] alpha = new byte[pixels * pixels];
        for (int y = 0; y < pixels; y++) for (int x = 0; x < pixels; x++) alpha[y * pixels + x] = (byte) (image.getRGB(x, y) >>> 24);
        return new SettingsTextRaster.Raster(pixels, pixels, 0, 0, density, alpha);
    }
    private SettingsIconRaster() { }
}
