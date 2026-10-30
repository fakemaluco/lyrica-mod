package dev.lyrica.engine;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

/** Converts CDN WebP/AVIF images into the PNG understood by every supported Minecraft version.
 * Called on the artwork I/O worker; native codec startup never blocks the GUI thread. */
public final class ArtworkImageDecoder {
    private static final int MAX_BYTES = 8 * 1024 * 1024, MAX_EDGE = 4096;
    private ArtworkImageDecoder() { }
    public static byte[] normalize(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid artwork size");
        boolean webp = bytes.length > 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP");
        boolean avif = false;
        if (bytes.length > 16 && ascii(bytes, 4, "ftyp"))
            for (int i = 8; i + 4 <= Math.min(64, bytes.length); i += 4)
                avif |= ascii(bytes, i, "avif") || ascii(bytes, i, "avis");
        if (!webp && !avif) return bytes;
        try (var grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes), bytes.length)) {
            av_log_set_level(AV_LOG_ERROR);
            if (webp) grabber.setFormat("webp_pipe");
            grabber.setPixelFormat(AV_PIX_FMT_RGBA);
            grabber.setOption("threads", "1");
            grabber.setOption("protocol_whitelist", "pipe");
            grabber.start();
            int width = grabber.getImageWidth(), height = grabber.getImageHeight();
            if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE)
                throw new IOException("Invalid artwork dimensions");
            Frame frame = grabber.grabImage();
            if (frame == null || frame.image == null || frame.imageChannels != 4 || !(frame.image[0] instanceof ByteBuffer pixels))
                throw new IOException("Unsupported artwork pixels");
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * frame.imageStride + x * 4;
                    row[x] = (pixels.get(i + 3) & 255) << 24 | (pixels.get(i) & 255) << 16
                            | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255;
                }
                image.setRGB(0, y, width, 1, row, 0, width);
            }
            try (var output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
                return output.toByteArray();
            }
        } catch (IOException failure) { throw failure; }
          catch (Exception | LinkageError failure) { throw new IOException("Could not decode CDN artwork", failure); }
    }
    private static boolean ascii(byte[] bytes, int at, String value) {
        if (at + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[at + i] != value.charAt(i)) return false;
        return true;
    }
}
