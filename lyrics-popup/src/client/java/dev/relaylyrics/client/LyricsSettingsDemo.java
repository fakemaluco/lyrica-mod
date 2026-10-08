package dev.relaylyrics.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** Small original color field for the preview, prepared once without a download. */
public final class LyricsSettingsDemo {
    private LyricsSettingsDemo() { }
    private static final class Artwork { static final String DATA = create(); }
    public static String artwork() { return Artwork.DATA; }
    private static String create() {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
            double a = Math.exp(-((x - 20.) * (x - 20.) + (y - 27.) * (y - 27.)) / 1600.);
            double b = Math.exp(-((x - 78.) * (x - 78.) + (y - 72.) * (y - 72.)) / 1000.);
            int r = (int) Math.min(255, 22 + 95 * a + 40 * b);
            int g = (int) Math.min(255, 28 + 55 * a + 115 * b);
            int blue = (int) Math.min(255, 60 + 130 * a + 110 * b);
            image.setRGB(x, y, r << 16 | g << 8 | blue);
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException failure) { throw new IllegalStateException("Could not prepare preview artwork", failure); }
    }
}
