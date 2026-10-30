package dev.lyrica.engine;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.net.URI;
import java.nio.ByteBuffer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

/** Silent, bounded video-loop decoder. Native work and network I/O never run on the game thread.
 * One immutable frame replaces the previous one: a slow GUI cannot build a frame queue.
 */
public final class VideoLoop implements AutoCloseable {
    public static final int MAX_EDGE = 512;
    private static final long VISIBLE_GRACE = Duration.ofMillis(750).toNanos();
    private static final long RELEASE_IDLE = Duration.ofSeconds(3).toNanos();
    private static final long MIN_FRAME = 1_000_000_000L / 24;
    private static final HttpClient PLAYLIST_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
    private final boolean allowLocalFiles;
    private final Object wake = new Object();
    private final AtomicReference<PixelFrame> frame = new AtomicReference<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Thread worker;
    private volatile String source;
    private volatile long generation;
    private volatile long visibleUntil;
    private volatile boolean playing = true;
    private volatile boolean pauseWithMusic = true;
    private volatile boolean closed;
    private volatile String status = "No video";
    private volatile Throwable failure;

    public VideoLoop() { this(false); }
    /** Local inputs are for the explicit development fixture only. */
    public VideoLoop(boolean allowLocalFiles) {
        this.allowLocalFiles = allowLocalFiles;
        worker = Thread.ofPlatform().daemon().name("lyrica-video-decoder").start(this::decode);
    }

    public record PixelFrame(int width, int height, int[] argb, long sequence) { }

    public void setSource(String value) {
        String accepted = validSource(value, allowLocalFiles) ? value : null;
        synchronized (wake) {
            if (Objects.equals(source, accepted)) return;
            source = accepted;
            generation++;
            frame.set(null);
            failure = null;
            status = accepted == null ? "No video" : "Waiting for video to be visible";
            wake.notifyAll();
        }
    }

    public void setPlaying(boolean value, boolean pauseWhenStopped) {
        playing = value;
        pauseWithMusic = pauseWhenStopped;
        synchronized (wake) { wake.notifyAll(); }
    }

    public void touchVisible() {
        visibleUntil = System.nanoTime() + VISIBLE_GRACE;
        synchronized (wake) { wake.notifyAll(); }
    }

    public PixelFrame latest() { return frame.get(); }
    public String status() { return status; }
    public boolean failed() { return status.startsWith("Video unavailable"); }
    /** Diagnostic evidence for tests/logging; never put remote URLs into user-visible status text. */
    public Throwable failure() { return failure; }
    public boolean closed() { return closed; }

    private boolean visible() { return System.nanoTime() < visibleUntil; }

    private void decode() {
        while (!closed) {
            String selected = source;
            long token = generation;
            if (selected == null || !visible()) { await(100); continue; }
            if (failed()) { await(250); continue; }
            // Preserve the held frame even after an invisible view released its native stream.
            if (pauseWithMusic && !playing && frame.get() != null) { status = "Video paused"; await(100); continue; }
            String input = selectHlsVariant(selected);
            if (closed || token != generation) continue;
            try (FFmpegFrameGrabber grabber = selected.startsWith("file:")
                    ? new FFmpegFrameGrabber(java.nio.file.Path.of(URI.create(selected)).toFile())
                    : new FFmpegFrameGrabber(input)) {
                status = "Opening video";
                // FFmpeg's info dump includes signed media URLs; keep normal operation silent.
                av_log_set_level(AV_LOG_ERROR);
                grabber.setOption("rw_timeout", "4000000");
                grabber.setOption("timeout", "4000000");
                // Restrict playlist child URLs too: no arbitrary file/concat/pipe protocols.
                grabber.setOption("protocol_whitelist", allowLocalFiles
                        ? "file,http,https,tcp,tls,crypto,data" : "http,https,tcp,tls,crypto,data");
                grabber.setOption("threads", "2");
                grabber.setVideoOption("threads", "2");
                grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
                grabber.setPixelFormat(AV_PIX_FMT_RGBA);
                grabber.start();
                int originalWidth = grabber.getImageWidth(), originalHeight = grabber.getImageHeight();
                if (originalWidth <= 0 || originalHeight <= 0 || originalWidth > 16_384 || originalHeight > 16_384)
                    throw new IllegalArgumentException("Invalid video dimensions");
                double scale = Math.min(1, MAX_EDGE / (double) Math.max(originalWidth, originalHeight));
                grabber.setImageWidth(Math.max(1, (int) Math.round(originalWidth * scale)));
                grabber.setImageHeight(Math.max(1, (int) Math.round(originalHeight * scale)));
                double fps = grabber.getVideoFrameRate();
                long sourceStep = (long) (1_000_000_000L / (Double.isFinite(fps) && fps >= 1 && fps <= 240 ? fps : 24));
                long nextPublish = 0, nextFrame = System.nanoTime(), idleSince = 0;
                boolean first = true;
                while (!closed && token == generation) {
                    boolean paused = pauseWithMusic && !playing && frame.get() != null;
                    if (!visible() || paused) {
                        if (paused) status = "Video paused";
                        if (!visible()) {
                            if (idleSince == 0) idleSince = System.nanoTime();
                            if (System.nanoTime() - idleSince > RELEASE_IDLE) break;
                        } else idleSince = 0;
                        await(40);
                        nextFrame = System.nanoTime();
                        nextPublish = nextFrame;
                        continue;
                    }
                    idleSince = 0;
                    long delay = nextFrame - System.nanoTime();
                    if (delay > 0) { await(Math.max(1, Math.min(40, delay / 1_000_000L))); continue; }
                    Frame image = grabber.grabImage();
                    if (image == null) {
                        // Both MP4 and finite HLS playlists repeat from their original first frame.
                        try {
                            grabber.setTimestamp(0, true);
                            image = grabber.grabImage();
                        } catch (Exception cannotSeekPlaylist) {
                            if (!first) break; // Reopen a finite, non-seekable HLS playlist.
                            throw cannotSeekPlaylist;
                        }
                        if (image == null) {
                            if (!first) break;
                            throw new IllegalArgumentException("Video has no frames");
                        }
                    }
                    nextFrame = Math.max(nextFrame + sourceStep, System.nanoTime() - sourceStep);
                    long now = System.nanoTime();
                    if (pauseWithMusic && !playing && frame.get() != null) continue;
                    if (!first && now < nextPublish) continue;
                    PixelFrame decoded = pixels(image, sequence.incrementAndGet());
                    if (closed || token != generation) break;
                    frame.set(decoded);
                    status = paused ? "Video paused" : "Video ready";
                    // Accumulated deadlines preserve ~24 fps for 25/30/60 fps sources instead
                    // of rounding each interval up to the source's next frame (which gave 15).
                    nextPublish = Math.max(nextPublish + MIN_FRAME, now - MIN_FRAME);
                    first = false;
                }
            } catch (Exception | LinkageError failure) {
                if (token == generation && !closed) {
                    this.failure = failure;
                    frame.set(null);
                    status = "Video unavailable (" + failure.getClass().getSimpleName() + ")";
                }
            }
        }
        frame.set(null);
        status = "Video closed";
    }

    /** Select one small HLS rendition before FFmpeg opens a master playlist. Otherwise its
     * demuxer can open a 4K variant even though the final uploaded texture is only 512 pixels.
     */
    private static String selectHlsVariant(String source) {
        try {
            URI uri = URI.create(source);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !uri.getPath().toLowerCase().endsWith(".m3u8")) return source;
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Lyrica/0.8 (silent video)").GET().build();
            var response = PLAYLIST_HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(256 * 1024 + 1);
                if (response.statusCode() != 200 || bytes.length > 256 * 1024) return source;
                return chooseHlsVariant(uri, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception failure) { return source; }
    }

    static String chooseHlsVariant(URI master, String playlist) {
        String[] lines = playlist.split("\\R");
        URI best = null;
        int bestEdge = 0;
        for (int index = 0; index + 1 < lines.length; index++) {
            if (!lines[index].strip().startsWith("#EXT-X-STREAM-INF:")) continue;
            var match = RESOLUTION.matcher(lines[index]);
            if (!match.find()) continue;
            int edge;
            try { edge = Math.max(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))); }
            catch (NumberFormatException failure) { continue; }
            String child = lines[++index].strip();
            if (child.isEmpty() || child.startsWith("#")) continue;
            URI uri;
            try { uri = master.resolve(child); } catch (IllegalArgumentException failure) { continue; }
            if (!validSource(uri.toString(), false)) continue;
            boolean better = best == null || edge <= 720 && (bestEdge > 720 || edge > bestEdge)
                    || edge > 720 && bestEdge > 720 && edge < bestEdge;
            if (better) { best = uri; bestEdge = edge; }
        }
        return best == null ? master.toString() : best.toString();
    }

    private static PixelFrame pixels(Frame frame, long serial) {
        if (frame.image == null || frame.image.length == 0 || !(frame.image[0] instanceof ByteBuffer source))
            throw new IllegalArgumentException("Video decoder returned no RGBA plane");
        int width = frame.imageWidth, height = frame.imageHeight;
        if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE)
            throw new IllegalArgumentException("Video decoder exceeded output dimensions");
        ByteBuffer bytes = source.duplicate();
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * frame.imageStride;
            for (int x = 0; x < width; x++) {
                int at = row + x * 4;
                result[y * width + x] = (bytes.get(at + 3) & 255) << 24
                        | (bytes.get(at) & 255) << 16 | (bytes.get(at + 1) & 255) << 8 | bytes.get(at + 2) & 255;
            }
        }
        return new PixelFrame(width, height, result, serial);
    }

    static boolean validSource(String value, boolean local) {
        if (value == null || value.isBlank() || value.length() > 16_384) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    || local && "file".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException error) { return false; }
    }

    private void await(long millis) {
        synchronized (wake) { try { wake.wait(millis); } catch (InterruptedException ignored) { } }
    }

    @Override public void close() {
        closed = true;
        generation++;
        source = null;
        frame.set(null);
        synchronized (wake) { wake.notifyAll(); }
    }
}
