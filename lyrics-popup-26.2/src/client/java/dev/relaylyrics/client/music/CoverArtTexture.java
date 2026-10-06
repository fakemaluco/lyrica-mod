package dev.relaylyrics.client.music;

import com.mojang.blaze3d.platform.NativeImage;
import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.CompatibilityManager;
import dev.relaylyrics.client.ui.RoundedGui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Downloads trusted Spotify CDN artwork and owns its Minecraft texture. */
public final class CoverArtTexture implements AutoCloseable {
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4_096;
    private static final long COVER_TRANSITION_NANOS = 1_500_000_000L;
    private static final long MAX_ROUNDED_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ROUNDED_CACHE_ENTRIES = 40;
    private static final long ROUNDED_DRAW_GRACE_NANOS = 100_000_000L;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("relaylyrics/music/artwork");
    private final AtomicLong generation = new AtomicLong();
    private final FluidBackdrop fluidBackdrop = new FluidBackdrop();
    private final EnumMap<Fallback, TextureState> fallbackTextures = new EnumMap<>(Fallback.class);
    private volatile String requestedUrl;
    private volatile TextureState texture;
    private volatile TextureState previousTexture;
    private volatile long coverTransitionStartedNanos;
    private volatile String status = "NO ARTWORK";
    private volatile Fallback fallback = Fallback.NONE;
    private volatile boolean closed;
    private volatile long enhancedBackdropRetryNanos;
    private volatile long lastAttemptNanos;
    private final ArrayList<CachedRoundedCover> roundedCovers = new ArrayList<>();
    private long roundedCacheBytes;

    public CoverArtTexture() {
        loadFallback(Fallback.CONNECT_ACCOUNT, "please_connect.png");
        loadFallback(Fallback.SONG_NOT_FOUND, "song_not_found.png");
        loadFallback(Fallback.ERROR, "cover_error.png");
    }

    public void setFallback(Fallback value) {
        fallback = value == null ? Fallback.NONE : value;
    }

    public void update(String value) {
        String url = value == null ? null : value.strip();
        if (url != null && !allowed(url)) url = null;
        boolean sameRequest = java.util.Objects.equals(url, requestedUrl);
        if (sameRequest && (texture != null || url == null || status.equals("DECODING ARTWORK"))) return;
        long now = System.nanoTime();
        if (sameRequest && now - lastAttemptNanos < Duration.ofSeconds(5).toNanos()) return;
        requestedUrl = url;
        lastAttemptNanos = now;
        long current = generation.incrementAndGet();
        if (url == null) {
            status = "NO ARTWORK";
            Minecraft.getInstance().execute(this::destroyTexture);
            return;
        }
        String selected = url;
        status = "LOADING ARTWORK";
        FanDroidMod.LOGGER.info("Loading cover artwork from {}",
                selected.startsWith("data:image/") ? "system media" : URI.create(selected).getHost());
        CompletableFuture.runAsync(() -> load(selected, current));
    }

    public boolean draw(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        TextureState value = displayedTexture();
        if (value == null) return false;
        try {
            drawCover(context, value, x, y, width, height);
            return true;
        } catch (RuntimeException error) {
            return drawErrorFallback(context, x, y, width, height, 0, error);
        }
    }

    /** Draws the cover through the same runtime-supersampled rounded clip as the border. */
    public boolean draw(GuiGraphicsExtractor context, int x, int y, int width, int height,
                        int cornerRadius) {
        TextureState value = displayedTexture();
        if (value == null || width <= 0 || height <= 0) return false;
        try {
            return drawRoundedCover(context, value, x, y, width, height, cornerRadius, 1, 1);
        } catch (RuntimeException error) {
            return drawErrorFallback(context, x, y, width, height, cornerRadius, error);
        }
    }

    /** Draws a two-cover slide, clipped strictly to the requested album-art rectangle. */
    public boolean drawTransition(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        return drawTransition(context, x, y, width, height, 0);
    }

    /** Draws the same transition through a true rounded alpha-shaped clip. */
    public boolean drawTransition(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                  int cornerRadius) {
        try {
            TextureState current = displayedTexture();
            if (current == null || width <= 0 || height <= 0) return false;
            TextureState previous = texture == null ? null : previousTexture;
            long started = coverTransitionStartedNanos;
            if (previous == null || started == 0) {
                return drawRoundedCover(context, current, x, y, width, height, cornerRadius, 1, 1);
            }
            double linear = Math.clamp((System.nanoTime() - started)
                    / (double) COVER_TRANSITION_NANOS, 0, 1);
            if (linear >= 1) {
                previousTexture = null;
                coverTransitionStartedNanos = 0;
                discardRoundedCover(false);
                if (previous != current) destroy(previous);
                return drawRoundedCover(context, current, x, y, width, height, cornerRadius, 1, 1);
            }
            int[] offsets = transitionOffsets(easeInOutQuint(linear), width);
            RoundedGui.scissor(context, x, y, width, height, () -> {
                drawRoundedCover(context, previous, x + offsets[0], y, width, height,
                        cornerRadius, 1, 1);
                // The incoming cover is intentionally rendered last over the stationary old cover.
                drawRoundedCover(context, current, x + offsets[1], y, width, height,
                        cornerRadius, 1, 1);
            });
            return true;
        } catch (RuntimeException error) {
            return drawErrorFallback(context, x, y, width, height, cornerRadius, error);
        }
    }

    private boolean drawErrorFallback(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                      int cornerRadius, RuntimeException failure) {
        if (!status.equals("ARTWORK FAILED")) {
            status = "ARTWORK FAILED";
            FanDroidMod.LOGGER.warn("Cover rendering failed; using bundled error artwork", failure);
        }
        TextureState error = fallbackTextures.get(Fallback.ERROR);
        if (error == null || error == texture) return false;
        try {
            return drawRoundedCover(context, error, x, y, width, height, cornerRadius, 1, 1);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Replays the slide when two consecutive tracks intentionally reuse the same album artwork. */
    public void transitionCurrent() {
        TextureState current = texture;
        if (current == null) return;
        TextureState discarded = previousTexture;
        if (discarded != null && discarded != current) destroy(discarded);
        previousTexture = current;
        coverTransitionStartedNanos = System.nanoTime();
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height, boolean enhanced) {
        return drawBackdrop(context, x, y, width, height, enhanced ? 2 : 0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height, int blurStrength) {
        return drawBackdrop(context, x, y, width, height, blurStrength, 1.0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier) {
        return drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, 1.0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity) {
        return drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, opacity, 0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity,
                                int cornerRadius) {
        return drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, opacity,
                cornerRadius, 0, false, 0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity,
                                int cornerRadius, double huePulse) {
        return drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, opacity,
                cornerRadius, huePulse, false, 0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity,
                                int cornerRadius, double huePulse, boolean hueAuto) {
        return drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, opacity,
                cornerRadius, huePulse, hueAuto, 0);
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity,
                                int cornerRadius, double huePulse, boolean hueAuto,
                                double bloomPulse) {
        return drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, opacity,
                cornerRadius, BackdropFx.plain(huePulse, hueAuto, bloomPulse));
    }

    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity,
                                int cornerRadius, BackdropFx fx) {
        TextureState value = displayedTexture();
        if (value == null) return false;
        int strength = Math.clamp(blurStrength, 0, 3);
        double backgroundOpacity = Math.clamp(opacity, 0, 1);
        if (CompatibilityManager.needsExplicitGuiFlush()) PlatformTextures.flush(context);
        Runnable drawer = () -> {
            try {
                if (System.nanoTime() < enhancedBackdropRetryNanos) {
                    throw new IllegalStateException("fluid backdrop cooling down");
                }
                fluidBackdrop.draw(context, x, y, width, height, value.palette(), speedMultiplier,
                        backgroundOpacity, cornerRadius, fx);
                enhancedBackdropRetryNanos = 0;
            } catch (RuntimeException enhancedFailure) {
                status = "COMPATIBLE ARTWORK";
                // A shader reload or GUI batch switch is normally transient. Retry instead of
                // permanently removing the dynamic background for the rest of the session.
                enhancedBackdropRetryNanos = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                drawRoundedCover(context, value, x, y, width, height, cornerRadius,
                        backgroundOpacity, .58);
            }
            int veil = 0x1C + (3 - strength) * 4;
            int veilAlpha = (int) Math.round(veil * backgroundOpacity);
            if (veilAlpha > 0) {
                if (cornerRadius > 0) {
                    RoundedGui.smoothFill(context, x, y, width, height, cornerRadius,
                            veilAlpha << 24 | 0x101217);
                } else {
                    context.fill(x, y, x + width, y + height, veilAlpha << 24 | 0x101217);
                }
            }
        };
        RoundedGui.scissor(context, x, y, width, height, drawer);
        return true;
    }

    private static void drawCover(GuiGraphicsExtractor context, TextureState texture,
                                  int x, int y, int width, int height) {
        drawCover(context, texture, x, y, width, height, 1, 1);
    }

    private static void drawCover(GuiGraphicsExtractor context, TextureState texture,
                                  int x, int y, int width, int height,
                                  double opacity, double brightness) {
        if (width <= 0 || height <= 0) return;
        int sourceWidth = texture.width();
        int sourceHeight = texture.height();
        double targetRatio = width / (double) height;
        double sourceRatio = sourceWidth / (double) sourceHeight;
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        int cropX = 0;
        int cropY = 0;
        if (sourceRatio > targetRatio) {
            cropWidth = Math.max(1, (int) Math.round(sourceHeight * targetRatio));
            cropX = (sourceWidth - cropWidth) / 2;
        } else if (sourceRatio < targetRatio) {
            cropHeight = Math.max(1, (int) Math.round(sourceWidth / targetRatio));
            cropY = (sourceHeight - cropHeight) / 2;
        }
        PlatformTextures.draw(context, texture.id(), x, y, width, height, cropX, cropY,
                cropWidth, cropHeight, sourceWidth, sourceHeight, opacity, brightness);
    }

    /** Draws the cover from a cached per-pixel-rounded composite, so corners stay crisp
     *  at display resolution even with render mods that batch the GUI. Falls back to the
     *  plain cover when no rounding is needed. */
    private boolean drawRoundedCover(GuiGraphicsExtractor context, TextureState state,
                                     int x, int y, int width, int height,
                                     int cornerRadius, double opacity, double brightness) {
        if (width <= 0 || height <= 0) return false;
        if (cornerRadius <= 0) {
            drawCover(context, state, x, y, width, height, opacity, brightness);
            return true;
        }
        if (!PlatformTextures.supportsRoundedCoverComposite()) {
            // 1.21.11 corrupts freshly uploaded CPU composites on some render stacks.
            // Clip the original high-quality artwork instead, preserving rounded corners
            // without creating another dynamic texture.
            RoundedGui.clipped(context, x, y, width, height, cornerRadius,
                    () -> drawCover(context, state, x, y, width, height, opacity, brightness));
            return true;
        }
        RoundedCover rounded = roundedCover(state, width, height, cornerRadius);
        if (rounded == null) {
            drawCover(context, state, x, y, width, height, opacity, brightness);
            return true;
        }
        PlatformTextures.draw(context, rounded.id(), x, y, width, height,
                0, 0, rounded.textureWidth(), rounded.textureHeight(),
                rounded.textureWidth(), rounded.textureHeight(), opacity, brightness);
        return true;
    }

    private RoundedCover roundedCover(TextureState state, int width, int height, int radius) {
        double guiScale = PlatformTextures.guiScale();
        long now = System.nanoTime();
        for (int index = 0; index < roundedCovers.size(); index++) {
            CachedRoundedCover cached = roundedCovers.get(index);
            if (cached.cover.matches(state, width, height, radius, guiScale)) {
                cached.lastUsedNanos = now;
                roundedCovers.remove(index);
                roundedCovers.add(cached);
                return cached.cover;
            }
        }
        RoundedCover built = RoundedCover.build(state, width, height, radius, guiScale);
        if (built == null) return null;
        long bytes = 4L * built.textureWidth() * built.textureHeight();
        roundedCovers.add(new CachedRoundedCover(built, bytes, now));
        roundedCacheBytes += bytes;
        while (roundedCovers.size() > 2 && (roundedCovers.size() > MAX_ROUNDED_CACHE_ENTRIES
                || roundedCacheBytes > MAX_ROUNDED_CACHE_BYTES)) {
            CachedRoundedCover oldest = roundedCovers.getFirst();
            // GUI draws are submitted before the texture batch is flushed. Retain recently
            // used sizes so the inventory and popup can draw the same cover in one frame.
            if (now - oldest.lastUsedNanos < ROUNDED_DRAW_GRACE_NANOS) break;
            roundedCovers.removeFirst();
            roundedCacheBytes -= oldest.bytes;
            oldest.cover.close();
        }
        return built;
    }

    private void discardRoundedCover(boolean current) {
        for (int index = roundedCovers.size() - 1; index >= 0; index--) {
            CachedRoundedCover cached = roundedCovers.get(index);
            if ((cached.cover.state() == texture) == current) {
                roundedCovers.remove(index);
                roundedCacheBytes -= cached.bytes;
                cached.cover.close();
            }
        }
    }

    private static final class CachedRoundedCover {
        private final RoundedCover cover;
        private final long bytes;
        private long lastUsedNanos;

        private CachedRoundedCover(RoundedCover cover, long bytes, long now) {
            this.cover = cover;
            this.bytes = bytes;
            this.lastUsedNanos = now;
        }
    }

    public int accentColor() {
        TextureState value = displayedTexture();
        return value == null ? 0 : value.accent();
    }

    public String status() { return status; }

    private TextureState displayedTexture() {
        TextureState current = texture;
        if (current != null && !status.equals("ARTWORK FAILED")) return current;
        Fallback selected = status.equals("ARTWORK FAILED") ? Fallback.ERROR : fallback;
        return fallbackTextures.get(selected);
    }

    private void loadFallback(Fallback type, String fileName) {
        CompletableFuture.runAsync(() -> {
            String path = "/assets/lyrica/textures/gui/lyrics/" + fileName;
            try (InputStream stream = CoverArtTexture.class.getResourceAsStream(path)) {
                if (stream == null) throw new IOException("missing bundled cover " + path);
                byte[] bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    throw new IOException("empty or oversized bundled cover " + path);
                }
                Minecraft.getInstance().execute(() -> installFallback(type, bytes));
            } catch (Exception error) {
                FanDroidMod.LOGGER.warn("Could not load bundled {} cover: {}", type, error.toString());
            }
        });
    }

    private void installFallback(Fallback type, byte[] bytes) {
        if (closed) return;
        NativeImage image = null;
        try {
            image = decode(bytes);
            if (image.getWidth() <= 0 || image.getHeight() <= 0
                    || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                throw new IOException("invalid bundled cover dimensions");
            }
            PaletteData palette = extractPalette(image);
            DynamicTexture nativeTexture = PlatformTextures.fromImage(
                    "Lyrica " + type.name().toLowerCase(Locale.ROOT) + " cover", image);
            Identifier id = PlatformTextures.register("lyrica_cover_" + type.name().toLowerCase(Locale.ROOT),
                    nativeTexture);
            TextureState previous = fallbackTextures.put(type, new TextureState(id, image,
                    image.getWidth(), image.getHeight(), palette.accent(), palette.colors()));
            image = null;
            destroy(previous);
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Could not decode bundled {} cover: {}", type, error.toString());
        } finally {
            if (image != null) image.close();
        }
    }

    private void load(String url, long expectedGeneration) {
        try {
            Files.createDirectories(cache);
            Path file = cache.resolve(hash(url) + ".image");
            boolean cached = Files.isRegularFile(file) && Files.size(file) <= MAX_IMAGE_BYTES;
            byte[] bytes = cached ? Files.readAllBytes(file) : download(url);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                throw new IOException("empty or oversized artwork response");
            }
            if (!Files.exists(file)) Files.write(file, bytes);
            status = "DECODING ARTWORK";
            scheduleDecode(bytes, expectedGeneration, cached, url, 0);
        } catch (Exception error) {
            status = "ARTWORK FAILED";
            FanDroidMod.LOGGER.warn("Could not load cover artwork from {}: {}", url, error.toString());
        }
    }

    private void scheduleDecode(byte[] bytes, long expectedGeneration, boolean cached,
                                String url, int attempt) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (expectedGeneration != generation.get()) return;
            if (client.gui.overlay() != null && attempt < 120) {
                CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> scheduleDecode(bytes, expectedGeneration, cached, url, attempt + 1));
                return;
            }
            decodeAndInstall(bytes, expectedGeneration, cached, url);
        });
    }

    private void decodeAndInstall(byte[] bytes, long expectedGeneration, boolean cached, String url) {
        if (expectedGeneration != generation.get()) return;
        NativeImage image = null;
        try {
            image = decode(bytes);
            if (image.getWidth() <= 0 || image.getHeight() <= 0
                    || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                throw new IOException("invalid artwork dimensions");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            PaletteData palette = extractPalette(image);
            install(image, expectedGeneration, palette);
            image = null; // Texture manager owns the NativeImage after installation.
            status = cached ? "ARTWORK CACHED" : "ARTWORK DOWNLOADED";
            FanDroidMod.LOGGER.info("Cover artwork ready ({}x{}, {})", width, height,
                    cached ? "cache" : "download");
        } catch (Exception error) {
            status = "ARTWORK FAILED";
            FanDroidMod.LOGGER.warn("Could not decode cover artwork from {}: {}", url, error.toString());
        } finally {
            if (image != null) image.close();
        }
    }

    private byte[] download(String url) throws Exception {
        if (url.startsWith("data:image/")) {
            int separator = url.indexOf(',');
            if (separator < 0 || !url.substring(0, separator).endsWith(";base64")) {
                throw new IOException("invalid embedded artwork");
            }
            byte[] decoded = Base64.getDecoder().decode(url.substring(separator + 1));
            if (decoded.length == 0 || decoded.length > MAX_IMAGE_BYTES) {
                throw new IOException("empty or oversized embedded artwork");
            }
            return decoded;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", "FanDroid/0.1")
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg")
                .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("artwork HTTP " + response.statusCode());
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!type.startsWith("image/") || response.body().length > MAX_IMAGE_BYTES) {
            throw new IOException("invalid artwork response: " + type);
        }
        return response.body();
    }

    private static NativeImage decode(byte[] bytes) throws IOException {
        try {
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (OutOfMemoryError nativeFailure) {
            String message = String.valueOf(nativeFailure.getMessage()).toLowerCase(Locale.ROOT);
            if (!message.contains("stack")) throw nativeFailure;
            return decodeWithImageIo(bytes, nativeFailure);
        } catch (IOException | RuntimeException nativeFailure) {
            return decodeWithImageIo(bytes, nativeFailure);
        }
    }

    private static NativeImage decodeWithImageIo(byte[] bytes, Throwable nativeFailure) throws IOException {
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
        if (buffered == null) {
            throw new IOException("unsupported artwork image", nativeFailure);
        }
        NativeImage converted = new NativeImage(buffered.getWidth(), buffered.getHeight(), true);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                int argb = buffered.getRGB(x, y);
                int abgr = argb & 0xFF00FF00 | argb >>> 16 & 0xFF | (argb & 0xFF) << 16;
                converted.setPixelABGR(x, y, abgr);
            }
        }
        return converted;
    }

    private void install(NativeImage image, long expectedGeneration, PaletteData palette) {
        if (expectedGeneration != generation.get()) {
            image.close();
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        DynamicTexture nativeTexture = PlatformTextures.fromImage("FanDroid cover", image);
        Identifier id = PlatformTextures.register("relaylyrics_cover_" + expectedGeneration, nativeTexture);
        TextureState discarded = previousTexture;
        TextureState outgoing = texture;
        previousTexture = outgoing;
        texture = new TextureState(id, image, width, height, palette.accent(), palette.colors());
        enhancedBackdropRetryNanos = 0;
        coverTransitionStartedNanos = outgoing == null ? 0 : System.nanoTime();
        if (discarded != null && discarded != outgoing) destroy(discarded);
    }

    private static PaletteData extractPalette(NativeImage image) {
        int[] counts = new int[4_096];
        long[] red = new long[4_096];
        long[] green = new long[4_096];
        long[] blue = new long[4_096];
        int stepX = Math.max(1, image.getWidth() / 128);
        int stepY = Math.max(1, image.getHeight() / 128);
        for (int y = stepY / 2; y < image.getHeight(); y += stepY) {
            for (int x = stepX / 2; x < image.getWidth(); x += stepX) {
                int argb = PlatformTextures.colorArgb(image, x, y);
                int a = argb >>> 24;
                int r = argb >>> 16 & 255;
                int g = argb >>> 8 & 255;
                int b = argb & 255;
                if (a < 180) continue;
                int bucket = (r >> 4) << 8 | (g >> 4) << 4 | b >> 4;
                counts[bucket]++;
                red[bucket] += r;
                green[bucket] += g;
                blue[bucket] += b;
            }
        }
        int[] colors = selectPaletteColors(counts, red, green, blue);
        return new PaletteData(colors[0], colors);
    }

    static int[] selectPaletteColors(int[] counts, long[] red, long[] green, long[] blue) {
        int maxCount = 0;
        int total = 0;
        for (int count : counts) {
            total += count;
            if (count > maxCount) maxCount = count;
        }
        int accentFloor = Math.max(2, total / 256);
        int detailFloor = Math.max(2, total / 128);
        int[] selectedBuckets = {-1, -1, -1, -1, -1, -1};
        int[] colors = new int[6];
        int selected = 0;
        for (int slot = 0; slot < colors.length; slot++) {
            int best = -1;
            double bestScore = -1;
            double bestDiversity = 0;
            for (int bucket = 0; bucket < counts.length; bucket++) {
                if (counts[bucket] == 0) continue;
                boolean alreadySelected = false;
                for (int index = 0; index < selected; index++) {
                    if (selectedBuckets[index] == bucket) { alreadySelected = true; break; }
                }
                if (alreadySelected) continue;
                int r = (int) (red[bucket] / counts[bucket]);
                int g = (int) (green[bucket] / counts[bucket]);
                int b = (int) (blue[bucket] / counts[bucket]);
                int maximum = Math.max(r, Math.max(g, b));
                int minimum = Math.min(r, Math.min(g, b));
                double saturation = (maximum - minimum) / 255.0;
                double luminance = (r * .2126 + g * .7152 + b * .0722) / 255.0;
                double diversity = 1;
                for (int index = 0; index < selected; index++) {
                    int other = selectedBuckets[index];
                    int or = (int) (red[other] / counts[other]);
                    int og = (int) (green[other] / counts[other]);
                    int ob = (int) (blue[other] / counts[other]);
                    double distance = ((r - or) * (r - or) + (g - og) * (g - og)
                            + (b - ob) * (b - ob)) / (255.0 * 255.0 * 3.0);
                    diversity = Math.min(diversity, Math.sqrt(distance));
                }
                double population = counts[bucket] / (double) Math.max(1, maxCount);
                double score;
                if (selected == 0) {
                    // The accent should carry the cover's mood, not its biggest
                    // neutral surface. Saturated regions keep a mild (log-like)
                    // population weight so a small vivid blotch outranks a giant
                    // white/black backdrop; anything saturated below the 0.4%
                    // floor is rejected so isolated pixels cannot hijack the
                    // palette. Covers without such a region fall back to plain
                    // population, keeping neutral accents honest.
                    if (saturation > .12 && counts[bucket] < accentFloor) continue;
                    double vibrancy = .02 + Math.pow(saturation, 1.4);
                    double populationWeight = Math.pow(population, .18);
                    double luminanceWeight = .30 + Math.sin(Math.PI * luminance) * 1.6;
                    score = populationWeight * vibrancy * luminanceWeight;
                } else {
                    double colorWeight = .55 + Math.pow(saturation, 1.15) * .95;
                    score = Math.pow(counts[bucket], .72) * colorWeight
                            * (.58 + Math.sin(Math.PI * luminance) * .56)
                            * (.18 + diversity * 1.72);
                }
                if (score > bestScore) {
                    best = bucket;
                    bestScore = score;
                    bestDiversity = diversity;
                }
            }
            if (best < 0) break;
            if (selected > 0 && (bestDiversity < .08
                    || counts[best] < detailFloor)) break;
            selectedBuckets[selected++] = best;
            int count = counts[best];
            colors[slot] = vibrant((int) (red[best] / count), (int) (green[best] / count),
                    (int) (blue[best] / count));
        }
        int[] fallback = {0xFF111111, 0xFF383838, 0xFF686868, 0xFF929292, 0xFFBDBDBD, 0xFFE8E8E8};
        if (selected == 0) return fallback.clone();
        int seedCount = selected;
        while (selected < colors.length) {
            int seed = colors[(selected - seedCount) % seedCount];
            int variant = selected - seedCount;
            colors[selected++] = familyVariant(seed, variant);
        }
        return colors;
    }

    static int vibrant(int r, int g, int b) {
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float saturation = hsb[1] < .14F
                ? (float) Math.clamp(hsb[1] * .55, 0, .075)
                : (float) Math.clamp(hsb[1] * 1.08 + .015, 0, .92);
        float brightness = (float) Math.clamp(hsb[2] * 1.03 + .008, .07, .96);
        return 0xFF000000 | Color.HSBtoRGB(hsb[0], saturation, brightness) & 0xFFFFFF;
    }

    static int familyVariant(int color, int variant) {
        float[] hsb = Color.RGBtoHSB(color >>> 16 & 255, color >>> 8 & 255, color & 255, null);
        double[] brightnessFactors = {.72, 1.18, .52, 1.34, .88};
        double[] saturationFactors = {.88, 1.06, .72, 1.14, .96};
        int index = Math.floorMod(variant, brightnessFactors.length);
        float saturation = hsb[1] < .10F ? 0
                : (float) Math.clamp(hsb[1] * saturationFactors[index], .04, .94);
        float brightness = (float) Math.clamp(hsb[2] * brightnessFactors[index], .055, .97);
        // Hue is deliberately untouched: derived colors stay in the cover's own family.
        return 0xFF000000 | Color.HSBtoRGB(hsb[0], saturation, brightness) & 0xFFFFFF;
    }

    static int rotateHue(int color, double degrees) {
        if (degrees == 0 || color == 0) return color;
        float[] hsb = Color.RGBtoHSB(color >>> 16 & 255, color >>> 8 & 255, color & 255, null);
        if (hsb[1] < .05F) return color;
        float hue = (float) (((hsb[0] * 360 + degrees) % 360 + 360) % 360) / 360;
        return 0xFF000000 | Color.HSBtoRGB(hue, hsb[1], hsb[2]) & 0xFFFFFF;
    }

    static int tint(int color, int tintColor, double amount) {
        if (amount <= 0 || color == 0) return color;
        double blend = Math.clamp(amount, 0, 1);
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        int tr = tintColor >>> 16 & 255;
        int tg = tintColor >>> 8 & 255;
        int tb = tintColor & 255;
        r = Math.clamp((int) Math.round(r + (tr - r) * blend), 0, 255);
        g = Math.clamp((int) Math.round(g + (tg - g) * blend), 0, 255);
        b = Math.clamp((int) Math.round(b + (tb - b) * blend), 0, 255);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static boolean isNeutralPalette(int[] palette) {
        if (palette == null || palette.length == 0) return true;
        for (int color : palette) {
            int r = color >>> 16 & 255;
            int g = color >>> 8 & 255;
            int b = color & 255;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 18) return false;
        }
        return true;
    }

    static int backdropBaseColor(int[] palette) {
        if (palette == null || palette.length == 0) return 0xFF242424;
        double red = 0;
        double green = 0;
        double blue = 0;
        double totalWeight = 0;
        for (int index = 0; index < palette.length; index++) {
            int color = palette[index];
            int r = color >>> 16 & 255;
            int g = color >>> 8 & 255;
            int b = color & 255;
            int maximum = Math.max(r, Math.max(g, b));
            int minimum = Math.min(r, Math.min(g, b));
            double saturation = maximum == 0 ? 0 : (maximum - minimum) / (double) maximum;
            double weight = (index == 0 ? 1.45 : 1) * (.55 + saturation * .85);
            red += r * weight;
            green += g * weight;
            blue += b * weight;
            totalWeight += weight;
        }
        double averageLuminance = (red + green * 2 + blue) / (totalWeight * 255.0 * 4.0);
        double strength = (isNeutralPalette(palette) ? .66 : .62)
                * Math.clamp(1.35 - averageLuminance * .85, .55, 1.15);
        int r = Math.clamp((int) Math.round(red / totalWeight * strength), 0, 255);
        int g = Math.clamp((int) Math.round(green / totalWeight * strength), 0, 255);
        int b = Math.clamp((int) Math.round(blue / totalWeight * strength), 0, 255);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private void destroyTexture() {
        TextureState current = texture;
        TextureState previous = previousTexture;
        texture = null;
        previousTexture = null;
        coverTransitionStartedNanos = 0;
        enhancedBackdropRetryNanos = 0;
        discardRoundedCover(true);
        discardRoundedCover(false);
        destroy(current);
        if (previous != current) destroy(previous);
    }

    private static void destroy(TextureState state) {
        if (state != null) Minecraft.getInstance().getTextureManager().release(state.id());
    }

    static int[] transitionOffsets(double progress, int width) {
        double clamped = Math.clamp(progress, 0, 1);
        int safeWidth = Math.max(0, width);
        return new int[]{0,
                (int) Math.round(safeWidth * (1 - clamped))};
    }

    static double easeInOutQuint(double value) {
        double clamped = Math.clamp(value, 0, 1);
        return clamped < .5
                ? 16 * Math.pow(clamped, 5)
                : 1 - Math.pow(-2 * clamped + 2, 5) / 2;
    }

    private static boolean allowed(String value) {
        if (value.length() <= 12 * 1024 * 1024 && (value.startsWith("data:image/png;base64,")
                || value.startsWith("data:image/jpeg;base64,")
                || value.startsWith("data:image/webp;base64,")
                || value.startsWith("data:image/gif;base64,"))) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("i.scdn.co") || host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com")
                    || host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net")
                    || host.equals("spicylyrics.org") || host.endsWith(".spicylyrics.org");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        Minecraft.getInstance().execute(() -> {
            destroyTexture();
            fallbackTextures.values().forEach(CoverArtTexture::destroy);
            fallbackTextures.clear();
            fluidBackdrop.close();
        });
    }

    public enum Fallback {
        NONE,
        CONNECT_ACCOUNT,
        SONG_NOT_FOUND,
        ERROR
    }

    /** Reactive parameters for the fluid backdrop (hue, bloom, spectral tint). */
    public record BackdropFx(double huePulse, boolean hueAuto, double bloomPulse,
                             boolean tint, double tintStrength, double centroid) {
        public static BackdropFx plain(double huePulse, boolean hueAuto, double bloomPulse) {
            return new BackdropFx(huePulse, hueAuto, bloomPulse, false, 0, .5);
        }
    }

    private record PaletteData(int accent, int[] colors) {
        private PaletteData {
            colors = Arrays.copyOf(colors, colors.length);
        }
    }

    private record TextureState(Identifier id, NativeImage image, int width, int height,
                                int accent, int[] palette) {
        private TextureState {
            palette = Arrays.copyOf(palette, palette.length);
        }
    }

    static int roundedCompositeSize(int logicalSize, double guiScale, int maximum) {
        return Math.clamp((int) Math.ceil(Math.max(1, logicalSize) * Math.max(1.0, guiScale)),
                1, Math.max(1, maximum));
    }

    static int roundedCompositeRadius(int logicalRadius, double guiScale) {
        return Math.max(0, (int) Math.round(logicalRadius * Math.max(1.0, guiScale)));
    }

    /** Bakes the cover at physical GUI resolution with per-pixel signed-distance rounding,
     *  so rounded covers retain the source detail at every GUI scale and render-mod setup. */
    private record RoundedCover(TextureState state, int drawWidth, int drawHeight, int drawRadius,
                                double guiScale, int textureWidth, int textureHeight, int textureRadius,
                                DynamicTexture texture, Identifier id)
            implements AutoCloseable {
        private static final int MAX_COMPOSITE = 1024;

        boolean matches(TextureState state, int width, int height, int radius, double guiScale) {
            return this.state == state && drawWidth == width && drawHeight == height
                    && drawRadius == radius && Double.compare(this.guiScale, guiScale) == 0;
        }

        static RoundedCover build(TextureState state, int width, int height, int radius,
                                  double guiScale) {
            NativeImage source = state.image();
            if (source == null || width <= 0 || height <= 0) return null;
            double safeGuiScale = Math.max(1.0, guiScale);
            int outputWidth = roundedCompositeSize(width, safeGuiScale, MAX_COMPOSITE);
            int outputHeight = roundedCompositeSize(height, safeGuiScale, MAX_COMPOSITE);
            int sourceWidth = state.width();
            int sourceHeight = state.height();
            double targetRatio = outputWidth / (double) outputHeight;
            double sourceRatio = sourceWidth / (double) sourceHeight;
            int cropWidth = sourceWidth;
            int cropHeight = sourceHeight;
            int cropX = 0;
            int cropY = 0;
            if (sourceRatio > targetRatio) {
                cropWidth = Math.max(1, (int) Math.round(sourceHeight * targetRatio));
                cropX = (sourceWidth - cropWidth) / 2;
            } else if (sourceRatio < targetRatio) {
                cropHeight = Math.max(1, (int) Math.round(sourceWidth / targetRatio));
                cropY = (sourceHeight - cropHeight) / 2;
            }
            DynamicTexture texture = PlatformTextures.empty(
                    "FanDroid rounded cover", outputWidth, outputHeight);
            Identifier id = PlatformTextures.register("relaylyrics_rounded_cover_"
                    + COVER_GENERATION.incrementAndGet(), texture);
            NativeImage target = texture.getPixels();
            if (target == null) {
                Minecraft.getInstance().getTextureManager().release(id);
                return null;
            }
            double scaleX = cropWidth / (double) outputWidth;
            double scaleY = cropHeight / (double) outputHeight;
            double halfW = outputWidth / 2.0;
            double halfH = outputHeight / 2.0;
            int safeRadius = Math.max(0, Math.min(roundedCompositeRadius(radius, safeGuiScale),
                    Math.min(outputWidth, outputHeight) / 2));
            for (int py = 0; py < outputHeight; py++) {
                double fy = cropY + (py + .5) * scaleY - .5;
                int y0 = Math.clamp((int) Math.floor(fy), 0, sourceHeight - 1);
                double ty = Math.clamp(fy - y0, 0, 1);
                int y1 = Math.min(sourceHeight - 1, y0 + 1);
                for (int px = 0; px < outputWidth; px++) {
                    double fx = cropX + (px + .5) * scaleX - .5;
                    int x0 = Math.clamp((int) Math.floor(fx), 0, sourceWidth - 1);
                    double tx = Math.clamp(fx - x0, 0, 1);
                    int x1 = Math.min(sourceWidth - 1, x0 + 1);
                    int c00 = PlatformTextures.colorArgb(source, x0, y0);
                    int c10 = PlatformTextures.colorArgb(source, x1, y0);
                    int c01 = PlatformTextures.colorArgb(source, x0, y1);
                    int c11 = PlatformTextures.colorArgb(source, x1, y1);
                    double r = bilinear(c00 >>> 16 & 255, c10 >>> 16 & 255,
                            c01 >>> 16 & 255, c11 >>> 16 & 255, tx, ty);
                    double g = bilinear(c00 >>> 8 & 255, c10 >>> 8 & 255,
                            c01 >>> 8 & 255, c11 >>> 8 & 255, tx, ty);
                    double b = bilinear(c00 & 255, c10 & 255, c01 & 255, c11 & 255, tx, ty);
                    int alpha = 255;
                    if (safeRadius > 0) {
                        double qx = Math.abs(px + .5 - halfW) - Math.max(0, halfW - safeRadius);
                        double qy = Math.abs(py + .5 - halfH) - Math.max(0, halfH - safeRadius);
                        double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
                        double distance = outside + Math.min(Math.max(qx, qy), 0) - safeRadius;
                        alpha = Math.clamp((int) Math.round(
                                Math.clamp(.5 - distance, 0, 1) * 255), 0, 255);
                    }
                    PlatformTextures.setColorArgb(target, px, py,
                            alpha << 24 | (int) Math.round(r) << 16
                                    | (int) Math.round(g) << 8 | (int) Math.round(b));
                }
            }
            texture.upload();
            return new RoundedCover(state, width, height, radius, safeGuiScale,
                    outputWidth, outputHeight, safeRadius, texture, id);
        }

        private static double bilinear(double a, double b, double c, double d,
                                       double tx, double ty) {
            double top = a + (b - a) * tx;
            double bottom = c + (d - c) * tx;
            return top + (bottom - top) * ty;
        }

        @Override
        public void close() {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    private static final AtomicLong COVER_GENERATION = new AtomicLong();

    private static final class FluidBackdrop implements AutoCloseable {
        private static final int SIZE = 96;
        private static final int MAX_COMPOSITE = 1024;
        private static final long FRAME_NANOS = 33_333_333L;
        private static final double MAX_HUE_SHIFT = 80.0;
        private static final double AUTO_HUE_RATE = 150.0;
        private NativeImage fieldImage;
        private DynamicTexture composite;
        private Identifier compositeId;
        private int compositeWidth;
        private int compositeHeight;
        private int compositeRadius = -1;
        private long lastFrame;
        private long lastMotionFrame;
        private final double[] animated = new double[18];
        private final double[] centersX = new double[6];
        private final double[] centersY = new double[6];
        private final double[] radiiX = new double[6];
        private final double[] radiiY = new double[6];
        private final double[] radiusXSquared = new double[6];
        private final double[] radiusYSquared = new double[6];
        private final double[] lobeXSquared = new double[6];
        private final double[] lobeYSquared = new double[6];
        private final double[] lobeOffsetX = new double[6];
        private final double[] lobeOffsetY = new double[6];
        private BackdropSamplingPlan samplingPlan;
        private int animatedColors;
        private boolean initialized;
        private double motionTime;
        private double hueShift;
        private double bloomGlow;
        private double centroidSmooth = .5;

        private void draw(GuiGraphicsExtractor context, int x, int y, int width, int height, int[] palette,
                          double speedMultiplier, double opacity, int cornerRadius,
            BackdropFx fx) {
            ensureField();
            long now = System.nanoTime();
            boolean animate = lastFrame == 0 || now - lastFrame >= FRAME_NANOS
                    || width != compositeWidth || height != compositeHeight;
            if (animate) {
                double aspect = height <= 0 ? 1 : Math.clamp(width / (double) height, .55, 2.2);
                updateField(palette, now, Math.clamp(speedMultiplier, 0.0, 8.0), aspect, fx);
            }
            resizeComposite(width, height);
            if (animate || composite == null || compositeRadius != cornerRadius) {
                compositeField(width, height, cornerRadius);
            }
            PlatformTextures.draw(context, compositeId, x, y, width, height,
                    0, 0, compositeWidth, compositeHeight,
                    compositeWidth, compositeHeight, opacity, 1);
        }

        private void ensureField() {
            if (fieldImage != null) return;
            // The 96 px field is a CPU-only source for the display-size composite. Registering
            // and uploading it as a second GPU texture performed work that was never sampled.
            fieldImage = new NativeImage(SIZE, SIZE, true);
        }

        private void resizeComposite(int width, int height) {
            int compositeWidth = Math.clamp(width, 1, MAX_COMPOSITE);
            int compositeHeight = Math.clamp(height, 1, MAX_COMPOSITE);
            if (composite != null && this.compositeWidth == compositeWidth
                    && this.compositeHeight == compositeHeight) return;
            if (composite != null) {
                Minecraft.getInstance().getTextureManager().release(compositeId);
                composite = null;
                compositeId = null;
            }
            composite = PlatformTextures.empty("FanDroid fluid composite",
                    compositeWidth, compositeHeight);
            compositeId = PlatformTextures.register("relaylyrics_fluid_composite", composite);
            this.compositeWidth = compositeWidth;
            this.compositeHeight = compositeHeight;
            this.compositeRadius = -1;
            this.samplingPlan = null;
        }

        /** Upscales the 96px field to display resolution and clips it with the popup's
         *  signed-distance rounding (same SDF as the border), so corners stay crisp. */
        private void compositeField(int width, int height, int cornerRadius) {
            NativeImage source = fieldImage;
            NativeImage target = composite.getPixels();
            if (source == null || target == null) return;
            int outputWidth = compositeWidth;
            int outputHeight = compositeHeight;
            int radius = cornerRadius;
            BackdropSamplingPlan plan = samplingPlan;
            if (plan == null || !plan.matches(outputWidth, outputHeight, radius)) {
                plan = new BackdropSamplingPlan(outputWidth, outputHeight, radius, SIZE);
                samplingPlan = plan;
            }
            for (int py = 0; py < outputHeight; py++) {
                int y0 = plan.y0[py];
                int y1 = plan.y1[py];
                double ty = plan.ty[py];
                for (int px = 0; px < outputWidth; px++) {
                    int x0 = plan.x0[px];
                    int x1 = plan.x1[px];
                    double tx = plan.tx[px];
                    int c00 = PlatformTextures.colorArgb(source, x0, y0);
                    int c10 = PlatformTextures.colorArgb(source, x1, y0);
                    int c01 = PlatformTextures.colorArgb(source, x0, y1);
                    int c11 = PlatformTextures.colorArgb(source, x1, y1);
                    double r = bilinear(c00 >>> 16 & 255, c10 >>> 16 & 255,
                            c01 >>> 16 & 255, c11 >>> 16 & 255, tx, ty);
                    double g = bilinear(c00 >>> 8 & 255, c10 >>> 8 & 255,
                            c01 >>> 8 & 255, c11 >>> 8 & 255, tx, ty);
                    double b = bilinear(c00 & 255, c10 & 255, c01 & 255, c11 & 255, tx, ty);
                    int alpha = plan.alpha[py * outputWidth + px];
                    target.setPixelABGR(px, py,
                            alpha << 24 | (int) Math.round(b) << 16
                                    | (int) Math.round(g) << 8 | (int) Math.round(r));
                }
            }
            composite.upload();
            compositeRadius = radius;
        }

        private static double bilinear(double a, double b, double c, double d,
                                       double tx, double ty) {
            double top = a + (b - a) * tx;
            double bottom = c + (d - c) * tx;
            return top + (bottom - top) * ty;
        }

        private void updateField(int[] palette, long now, double speedMultiplier, double aspect,
                            BackdropFx fx) {
            NativeImage image = fieldImage;
            if (image == null) return;
            boolean firstMotionFrame = lastMotionFrame == 0;
            double delta = firstMotionFrame ? .08
                    : Math.clamp((now - lastMotionFrame) / 1_000_000_000.0, .01, .25);
            lastMotionFrame = now;
            double effectDelta = firstMotionFrame ? .016 : delta;
            if (fx.hueAuto()) {
                hueShift = (hueShift + Math.clamp(fx.huePulse(), 0, 1) * AUTO_HUE_RATE * effectDelta) % 360;
            } else {
                double targetHue = Math.clamp(fx.huePulse(), 0, 1) * MAX_HUE_SHIFT;
                hueShift += (targetHue - hueShift) * (1 - Math.exp(-effectDelta * 5));
            }
            // Fast attack on each beat, slow release so the glow lingers after it.
            double targetGlow = Math.clamp(fx.bloomPulse(), 0, 3);
            double response = targetGlow > bloomGlow
                    ? 1 - Math.exp(-effectDelta * 6)
                    : 1 - Math.exp(-effectDelta * 1.4);
            bloomGlow += (targetGlow - bloomGlow) * response;
            motionTime += delta * speedMultiplier;
            int colorCount = Math.clamp(palette.length, 1, 6);
            boolean neutralPalette = isNeutralPalette(palette);
            double tintMix = fx.tint()
                    ? Math.abs(Math.clamp(fx.centroid(), 0, 1) - .5) * 2 * Math.clamp(fx.tintStrength(), 0, 3)
                    : 0;
            int tintColor = fx.centroid() < .5 ? 0xFFE06020 : 0xFF3090E8;
            for (int index = 0; index < colorCount; index++) {
                int color = tint(rotateHue(palette[index], hueShift), tintColor, tintMix);
                int offset = index * 3;
                double targetR = color >>> 16 & 255;
                double targetG = color >>> 8 & 255;
                double targetB = color & 255;
                if (!initialized || index >= animatedColors || neutralPalette) {
                    animated[offset] = targetR;
                    animated[offset + 1] = targetG;
                    animated[offset + 2] = targetB;
                } else {
                    double blend = 1 - Math.exp(-delta * 2.7);
                    animated[offset] += (targetR - animated[offset]) * blend;
                    animated[offset + 1] += (targetG - animated[offset + 1]) * blend;
                    animated[offset + 2] += (targetB - animated[offset + 2]) * blend;
                }
            }
            double targetCentroid = Math.clamp(fx.centroid(), 0, 1);
            centroidSmooth += (targetCentroid - centroidSmooth) * (1 - Math.exp(-delta * 6));
            initialized = true;
            animatedColors = colorCount;
            double time = motionTime;
            for (int index = 0; index < colorCount; index++) {
                double angle = Math.PI * 2 * index / colorCount + index * .37;
                double direction = index % 2 == 0 ? 1 : -1;
                double drift = time * (.34 + index * .047) * direction;
                double orbit = index == 0 ? .16 : .28 + (index % 3) * .045;
                centersX[index] = .5 + orbit * Math.sin(angle + drift)
                        + .075 * Math.sin(time * (.73 + index * .035) + index * 2.13);
                centersY[index] = .5 + orbit * .82 * Math.cos(angle * 1.11 - drift * .87)
                        + .068 * Math.cos(time * (.61 + index * .043) + index * 1.71);
                radiiX[index] = (index == 0 ? .48 : .27 + (index % 3) * .038)
                        + .085 * Math.sin(time * (.58 + index * .052) + index * 1.29);
                radiiY[index] = (index == 0 ? .55 : .32 + (index % 2) * .055)
                        + .096 * Math.cos(time * (.49 + index * .061) + index * 1.53);
                radiusXSquared[index] = radiiX[index] * radiiX[index];
                radiusYSquared[index] = radiiY[index] * radiiY[index];
                lobeXSquared[index] = radiusXSquared[index] * .68;
                lobeYSquared[index] = radiusYSquared[index] * .66;
                lobeOffsetX[index] = .16 * Math.sin(time * (.88 + index * .047) + index * 1.93);
                lobeOffsetY[index] = .18 * Math.cos(time * (.74 + index * .039) + index * 1.47);
            }
            int baseColor = tint(rotateHue(backdropBaseColor(palette), hueShift), tintColor, tintMix);
            double baseRed = baseColor >>> 16 & 255;
            double baseGreen = baseColor >>> 8 & 255;
            double baseBlue = baseColor & 255;
            for (int py = 0; py < SIZE; py++) {
                double v = py / (double) (SIZE - 1);
                for (int px = 0; px < SIZE; px++) {
                    double u = px / (double) (SIZE - 1);
                    double radialX = u - .5;
                    double radialY = v - .5;
                    double radial = Math.sqrt(radialX * radialX + radialY * radialY);
                    double swirl = .24 * Math.sin(time * .72 + radial * 9.0);
                    double sinSwirl = Math.sin(swirl);
                    double cosSwirl = Math.cos(swirl);
                    double spunU = .5 + radialX * cosSwirl - radialY * sinSwirl;
                    double spunV = .5 + radialX * sinSwirl + radialY * cosSwirl;
                    double warpedU = spunU + .082 * Math.sin(spunV * Math.PI * 2.0 + time * 1.08)
                            + .034 * Math.sin((spunU + spunV) * Math.PI * 4.0 - time * 1.42);
                    double warpedV = spunV + .074 * Math.sin(spunU * Math.PI * 2.0 - time * .94)
                            + .031 * Math.cos((spunU - spunV) * Math.PI * 5.0 + time * 1.27);
                    double total = .72;
                    double red = baseRed * total;
                    double green = baseGreen * total;
                    double blue = baseBlue * total;
                    for (int index = 0; index < colorCount; index++) {
                        double dx = (warpedU - centersX[index]) * aspect;
                        double dy = warpedV - centersY[index];
                        double mainField = Math.exp(-1.92 * (dx * dx / radiusXSquared[index]
                                + dy * dy / radiusYSquared[index]));
                        double lobeX = dx + lobeOffsetX[index];
                        double lobeY = dy - lobeOffsetY[index];
                        double lobeField = .78 * Math.exp(-2.48 * (lobeX * lobeX / lobeXSquared[index]
                                + lobeY * lobeY / lobeYSquared[index]));
                        double ripple = .10 * Math.sin((dx * 5.2 - dy * 4.4) * Math.PI
                                + time * (1.35 + index * .11));
                        double field = Math.clamp(mainField + lobeField + ripple * mainField, 0, 1.48);
                        double weight = Math.pow(field, 1.20) * (index == 0 ? 2.85 : 2.35);
                        int offset = index * 3;
                        red += animated[offset] * weight;
                        green += animated[offset + 1] * weight;
                        blue += animated[offset + 2] * weight;
                        total += weight;
                    }
                    double vignette = Math.clamp((u - .5) * (u - .5) + (v - .5) * (v - .5), 0, .5);
                    double flowLight = .045 * Math.sin((warpedU * 2.7 + warpedV * 1.8) * Math.PI
                            + time * 1.18);
                    double light = .94 - vignette * .38 + flowLight;
                    if (bloomGlow > .01) {
                        double bloom = bloomGlow
                                * (.22 + .78 * Math.exp(-radial * 3.8));
                        light += bloom * .55;
                    }
                    int r = Math.clamp((int) Math.round(red / total * light), 0, 255);
                    int g = Math.clamp((int) Math.round(green / total * light), 0, 255);
                    int b = Math.clamp((int) Math.round(blue / total * light), 0, 255);
                    image.setPixelABGR(px, py, 255 << 24 | b << 16 | g << 8 | r);
                }
            }
            lastFrame = now;
        }

        private static double roundedRectDistance(double x, double y, double halfWidth,
                                                  double halfHeight, double radius) {
            double qx = Math.abs(x - halfWidth) - Math.max(0, halfWidth - radius);
            double qy = Math.abs(y - halfHeight) - Math.max(0, halfHeight - radius);
            double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
            return outside + Math.min(Math.max(qx, qy), 0) - radius;
        }

        @Override
        public void close() {
            if (fieldImage != null) fieldImage.close();
            if (compositeId != null) {
                Minecraft.getInstance().getTextureManager().release(compositeId);
            }
            fieldImage = null;
            composite = null;
            compositeId = null;
            compositeWidth = 0;
            compositeHeight = 0;
            compositeRadius = -1;
            samplingPlan = null;
            initialized = false;
            animatedColors = 0;
            lastFrame = 0;
            lastMotionFrame = 0;
            motionTime = 0;
            hueShift = 0;
            bloomGlow = 0;
            centroidSmooth = .5;
        }
    }

    /** Immutable sampling geometry reused while the backdrop size and radius stay unchanged. */
    static final class BackdropSamplingPlan {
        private final int width;
        private final int height;
        private final int radius;
        private final int[] x0;
        private final int[] x1;
        private final double[] tx;
        private final int[] y0;
        private final int[] y1;
        private final double[] ty;
        private final int[] alpha;

        BackdropSamplingPlan(int width, int height, int radius, int sourceSize) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.radius = Math.max(0, radius);
            int safeSourceSize = Math.max(2, sourceSize);
            x0 = new int[this.width];
            x1 = new int[this.width];
            tx = new double[this.width];
            y0 = new int[this.height];
            y1 = new int[this.height];
            ty = new double[this.height];
            alpha = new int[this.width * this.height];
            fillAxis(x0, x1, tx, safeSourceSize);
            fillAxis(y0, y1, ty, safeSourceSize);
            double halfWidth = this.width / 2.0;
            double halfHeight = this.height / 2.0;
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    int value = 255;
                    if (this.radius > 0) {
                        double distance = roundedRectDistance(x + .5, y + .5,
                                halfWidth, halfHeight, this.radius);
                        value = Math.clamp((int) Math.round(
                                Math.clamp(.5 - distance, 0, 1) * 255), 0, 255);
                    }
                    alpha[y * this.width + x] = value;
                }
            }
        }

        boolean matches(int width, int height, int radius) {
            return this.width == width && this.height == height && this.radius == radius;
        }

        int alpha(int x, int y) { return alpha[y * width + x]; }
        int sourceX(int x) { return x0[x]; }
        int sourceY(int y) { return y0[y]; }
        double fractionX(int x) { return tx[x]; }
        double fractionY(int y) { return ty[y]; }

        private static void fillAxis(int[] lower, int[] upper, double[] fraction, int sourceSize) {
            double scale = (sourceSize - 1) / (double) Math.max(1, lower.length - 1);
            for (int index = 0; index < lower.length; index++) {
                double sample = Math.clamp(index * scale, 0, sourceSize - 1);
                lower[index] = Math.min(sourceSize - 2, (int) Math.floor(sample));
                upper[index] = lower[index] + 1;
                fraction[index] = sample - lower[index];
            }
        }

        private static double roundedRectDistance(double x, double y, double halfWidth,
                                                  double halfHeight, double radius) {
            double qx = Math.abs(x - halfWidth) - Math.max(0, halfWidth - radius);
            double qy = Math.abs(y - halfHeight) - Math.max(0, halfHeight - radius);
            double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
            return outside + Math.min(Math.max(qx, qy), 0) - radius;
        }
    }
}
