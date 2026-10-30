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
    private static final class ArtistArtwork { static final String DATA = createArtist(); }
    public static String artistArtwork() {
        String remote = System.getProperty("lyrica.configArtistProbeUrl", "");
        return Boolean.getBoolean("lyrica.configSelfTest") && remote.startsWith("https://i.scdn.co/image/") ? remote : ArtistArtwork.DATA;
    }
    /** Geometric local fixture for testing a wide artist header, never used for real playback. */
    private static String createArtist() {
        BufferedImage image = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(18, 91, 103), 960, 540, new java.awt.Color(208, 112, 64)));
        g.fillRect(0, 0, 960, 540);
        g.setColor(new java.awt.Color(239, 183, 101)); g.fillOval(650, 40, 210, 210);
        g.setColor(new java.awt.Color(26, 31, 46)); g.fillOval(620, 140, 130, 155); g.fillRoundRect(555, 275, 260, 350, 120, 120);
        g.dispose();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
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
