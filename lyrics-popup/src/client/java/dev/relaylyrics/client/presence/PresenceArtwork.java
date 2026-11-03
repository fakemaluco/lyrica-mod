package dev.relaylyrics.client.presence;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;

/** System-media artwork stays small; full-resolution images never travel through presence. */
public final class PresenceArtwork {
    private static final String PREFIX = "data:image/png;base64,";
    private PresenceArtwork() { }
    public static boolean thumbnail(String value) {
        if (value == null || !value.startsWith(PREFIX) || value.length() > 27000) return false;
        try {
            byte[] b = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (b.length < 33 || b.length > 20000) return false;
            var bytes = java.nio.ByteBuffer.wrap(b);
            return bytes.getLong() == 0x89504E470D0A1A0AL && bytes.getInt() == 13 && bytes.getInt() == 0x49484452
                    && positiveEdge(bytes.getInt()) && positiveEdge(bytes.getInt());
        } catch (RuntimeException error) { return false; }
    }
    private static boolean positiveEdge(int value) { return value > 0 && value <= 256; }
    public static String prepare(String value) {
        String safe = PresenceTrack.safeCover(value);
        if (!safe.isBlank()) return safe;
        if (value == null || !value.startsWith("data:image/") || value.length() > 6_000_000) return "";
        int comma = value.indexOf(','); if (comma < 0 || !value.substring(0, comma).endsWith(";base64")) return "";
        try {
            byte[] bytes = Base64.getDecoder().decode(value.substring(comma + 1));
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) return "";
                var reader = readers.next();
                BufferedImage source;
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096) return "";
                    source = reader.read(0);
                } finally { reader.dispose(); }
                try {
                    // Prefer full colour; only reduce dimensions when the encoded payload exceeds the budget.
                    for (int edge : new int[]{256, 192, 128, 96, 64}) {
                        var small = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB);
                        var g = small.createGraphics();
                        try {
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            int side = Math.min(source.getWidth(), source.getHeight());
                            int left = (source.getWidth() - side) / 2, top = (source.getHeight() - side) / 2;
                            g.drawImage(source, 0, 0, edge, edge, left, top, left + side, top + side, null);
                        } finally { g.dispose(); }
                        var out = new ByteArrayOutputStream(); ImageIO.write(small, "png", out); small.flush();
                        String result = PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
                        if (thumbnail(result)) return result;
                    }
                    return "";
                } finally { source.flush(); }
            }
        } catch (IOException | RuntimeException error) { return ""; }
    }
}
