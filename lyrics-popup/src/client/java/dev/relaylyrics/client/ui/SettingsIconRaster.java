package dev.relaylyrics.client.ui;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** Official Lucide exports, sampled at framebuffer density. Sources and license are included. */
final class SettingsIconRaster {
    private static final Map<String, BufferedImage> SOURCES = new ConcurrentHashMap<>();
    static SettingsTextRaster.Raster render(String name, double size, double density) {
        String asset = switch (name) {
            case "GENERAL" -> "sliders-horizontal";
            case "APPEARANCE" -> "palette";
            case "CUSTOM" -> "audio-lines";
            case "CONNECTION" -> "cable";
            case "SOURCES" -> "list-music";
            case "ADVANCED" -> "settings-2";
            case "WORLD" -> "box";
            case "close" -> "x";
            default -> name.startsWith("chevron:") ? "chevron-down" : name;
        };
        BufferedImage source = SOURCES.computeIfAbsent(asset, SettingsIconRaster::load);
        int pixels = Math.max(1, (int) Math.ceil(size * density));
        var image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (name.startsWith("chevron:")) g.rotate(-Math.PI * Integer.parseInt(name.substring(8)) / 16., pixels / 2., pixels / 2.);
            g.drawImage(source, 0, 0, pixels, pixels, null);
        } finally { g.dispose(); }
        byte[] alpha = new byte[pixels * pixels];
        for (int y = 0; y < pixels; y++) for (int x = 0; x < pixels; x++) alpha[y * pixels + x] = (byte) (image.getRGB(x, y) >>> 24);
        return new SettingsTextRaster.Raster(pixels, pixels, 0, 0, density, alpha);
    }
    private static BufferedImage load(String name) {
        try (var stream = SettingsIconRaster.class.getResourceAsStream("/assets/relaylyrics/textures/gui/lucide/" + name + ".png")) {
            if (stream != null) {
                var image = ImageIO.read(stream);
                if (image != null) return image;
            }
        } catch (java.io.IOException ignored) { }
        throw new IllegalArgumentException("Missing bundled Lucide icon: " + name);
    }
    private SettingsIconRaster() { }
}
