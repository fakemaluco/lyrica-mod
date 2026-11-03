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
import dev.lyrica.engine.VideoLoop;

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
    private static final AtomicLong TEXTURE_INSTANCE_GENERATION = new AtomicLong();
    private static final long MAX_ROUNDED_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ROUNDED_CACHE_ENTRIES = 40;
    private static final long ROUNDED_DRAW_GRACE_NANOS = 100_000_000L;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("relaylyrics/music/artwork");
    private final AtomicLong generation = new AtomicLong();
    private final long textureInstanceId = TEXTURE_INSTANCE_GENERATION.incrementAndGet();
    private final FluidBackdrop fluidBackdrop = new FluidBackdrop(), islandBackdrop = new FluidBackdrop(true);
    private final StaticIslandPanel islandStillPanel=new StaticIslandPanel();
    private volatile boolean islandBackdropVideoSource;
    public boolean islandBackdropUsesVideo() { return islandBackdropVideoSource; }
    private final GlassLens[] glassLenses = {new GlassLens(), new GlassLens(), new GlassLens(), new GlassLens(), new GlassLens()};
    private volatile CoverArtTexture artistArtwork;
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

    private final MotionSurface canvasSurface = new MotionSurface("canvas");
    private final MotionSurface animatedSurface = new MotionSurface("album_video");
    private volatile boolean canvasOnCover, animatedCoverEnabled, canvasBackdrop, islandMotionEnabled;
    private volatile boolean pauseMotionWithMusic = true, motionPlaying = true;
    private volatile String visibleMotionKind = "static";
    private volatile long islandVideoSequence;
    public long islandFrameSequence(){return islandVideoSequence;}

    /** Called from lookup/snapshot threads. Native decoding is asynchronous; GPU work stays in draw. */
    public void configureMotion(String canvasUrl, String animatedCoverUrl, boolean useCanvasOnCover,
                                boolean useAnimatedCover, boolean useCanvasBackdrop, boolean pauseWithMusic) {
        canvasOnCover = useCanvasOnCover;
        animatedCoverEnabled = useAnimatedCover;
        canvasBackdrop = useCanvasBackdrop;
        pauseMotionWithMusic = pauseWithMusic;
        canvasSurface.setUrl(useCanvasOnCover || useCanvasBackdrop || islandMotionEnabled ? canvasUrl : null);
        animatedSurface.setUrl(useAnimatedCover || islandMotionEnabled ? animatedCoverUrl : null);
        setMotionPlaying(motionPlaying);
    }

    public void setIslandMotionEnabled(boolean enabled) { islandMotionEnabled=enabled; }
    public boolean isIslandMotionEnabled() { return islandMotionEnabled; }
    public boolean islandVideoReady() { return islandMotionEnabled && (canvasSurface.uploadedSequence>0 || animatedSurface.uploadedSequence>0); }

    public void setMotionPlaying(boolean playing) {
        motionPlaying = playing;
        canvasSurface.video.setPlaying(playing, pauseMotionWithMusic);
        animatedSurface.video.setPlaying(playing, pauseMotionWithMusic);
    }

    public void clearMotion() {
        canvasSurface.setUrl(null);
        animatedSurface.setUrl(null);
        visibleMotionKind = "static";
    }

    public String motionStatus() {
        if (canvasSurface.url != null && !canvasSurface.video.failed()) return "Canvas: " + canvasSurface.video.status();
        if (animatedSurface.url != null) return "Animated cover: " + animatedSurface.video.status();
        return canvasSurface.video.failed() ? "Canvas unavailable; static cover" : "Static cover";
    }
    public String motionKind() { return visibleMotionKind; }
    public boolean canvasFailed() { return canvasSurface.url != null && canvasSurface.video.failed(); }
    public boolean animatedCoverFailed() { return animatedSurface.url != null && animatedSurface.video.failed(); }
    public boolean motionReady() { return !visibleMotionKind.equals("static"); }

    /** Full-height, softly blurred video fading into the lyrics background. */
    public boolean drawCanvasSurface(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius) {
        TextureState current = motionCoverTexture();
        if (current == null) return false;
        MotionSurface owner = canvasSurface.owns(current) ? canvasSurface : animatedSurface.owns(current) ? animatedSurface : null;
        return owner != null && owner.drawRounded(context, current, x, y, width, height, radius, .82, .94, true);
    }

    /** A small frosted copy of the exact cover region under a floating utility button. */
    public void drawGlassLens(GuiGraphicsExtractor context, int coverX, int coverY, int coverWidth, int coverHeight,
                              int x, int y, int width, int height, int radius, double opacity, double brightness, int slot) {
        if (opacity <= .001 || width < 1 || height < 1 || coverWidth < 1 || coverHeight < 1) return;
        TextureState original = motionCoverTexture();
        if (original == null) return;
        MotionSurface owner = canvasSurface.owns(original) ? canvasSurface : animatedSurface.owns(original) ? animatedSurface : null;
        TextureState blurred = owner == null ? null : owner.sample(true);
        glassLenses[Math.clamp(slot, 0, glassLenses.length - 1)].draw(context, original, blurred, owner == null ? 0 : owner.uploadedSequence,
                coverX, coverY, coverWidth, coverHeight, x, y, width, height, radius, opacity, brightness);
    }

    /** Read decoded dimensions without allocating or uploading a GPU texture. */
    public double motionCoverAspectRatio() {
        for (int index = 0; index < 2; index++) {
            MotionSurface surface = index == 0 ? canvasSurface : animatedSurface;
            if (surface == canvasSurface ? !canvasOnCover : !animatedCoverEnabled) continue;
            if (surface.url == null || surface.video.failed()) continue;
            surface.video.touchVisible();
            var frame = surface.video.latest();
            if (frame != null) return frame.width() / (double) frame.height();
        }
        return 0;
    }
    public long motionFrameSequence() {
        return switch (visibleMotionKind) {
            case "canvas" -> canvasSurface.uploadedSequence;
            case "animated" -> animatedSurface.uploadedSequence;
            default -> 0;
        };
    }

    private TextureState motionCoverTexture() {
        if (canvasOnCover) {
            TextureState motion = canvasSurface.sample(false);
            if (motion != null) { visibleMotionKind = "canvas"; return motion; }
        }
        if (animatedCoverEnabled) {
            TextureState motion = animatedSurface.sample(false);
            if (motion != null) { visibleMotionKind = "animated"; return motion; }
        }
        visibleMotionKind = "static";
        return displayedTexture();
    }

    private boolean isMotionTexture(TextureState state) {
        return canvasSurface.owns(state) || animatedSurface.owns(state);
    }


    /** Existing GPU cover is shared with the world presence renderer. */
    public Identifier presenceTexture() { TextureState value = texture; return value == null ? null : value.id(); }
    public double presenceAspect() { TextureState value = texture; return value == null || value.height() <= 0 ? 1 : value.width() / (double)value.height(); }

    public static CoverArtTexture forPresence() { return new CoverArtTexture(false); }

    public CoverArtTexture() { this(true); }
    private CoverArtTexture(boolean withFallbacks) {
        if (!withFallbacks) return;
        loadFallback(Fallback.CONNECT_ACCOUNT, "please_connect.png");
        loadFallback(Fallback.SONG_NOT_FOUND, "song_not_found.png");
        loadFallback(Fallback.ERROR, "cover_error.png");
    }

    public boolean artistReady() {
        CoverArtTexture artist = artistArtwork;
        return artist != null && artist.texture != null;
    }
    public void configureArtist(String url) {
        if (closed) return;
        if (url == null) { if (artistArtwork != null) artistArtwork.update(null); return; }
        if (artistArtwork == null) artistArtwork = new CoverArtTexture(false);
        artistArtwork.update(url);
    }
    public boolean drawArtistBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                      double opacity, int radius) {
        CoverArtTexture artist = artistArtwork;
        TextureState photo = artist == null ? null : artist.texture;
        if (photo == null) photo = displayedTexture();
        if (photo == null) return false;
        drawRoundedCover(context, photo, x, y, width, height, radius, opacity, .55);
        RoundedGui.smoothFill(context, x, y, width, height, radius, (int) Math.round(75 * opacity) << 24 | 0x080A10);
        return true;
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
        TextureState value = motionCoverTexture();
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
        TextureState value = motionCoverTexture();
        if (value == null || width <= 0 || height <= 0) return false;
        try {
            return drawRoundedCover(context, value, x, y, width, height, cornerRadius, 1, 1);
        } catch (RuntimeException error) {
            return drawErrorFallback(context, x, y, width, height, cornerRadius, error);
        }
    }

    /** A rounded static cover with uniform alpha for transient presence cards. */
    public boolean draw(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius, double opacity) {
        TextureState current = motionCoverTexture();
        if (current == null || width <= 0 || height <= 0) return false;
        try { return drawRoundedCover(context, current, x, y, width, height, radius, Math.clamp(opacity, 0, 1), 1); }
        catch (RuntimeException error) { return false; }
    }

    /** A true album photo; never samples Canvas or animated-cover video surfaces. */
    public boolean drawStaticCover(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius) {
        return drawStaticCover(context,x,y,width,height,radius,1);
    }
    public boolean drawStaticCover(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius, double opacity) {
        TextureState current = displayedTexture();
        if (current == null || width <= 0 || height <= 0) return false;
        try { return drawRoundedCover(context,current,x,y,width,height,radius,Math.clamp(opacity,0,1),1); }
        catch (RuntimeException error) { return drawErrorFallback(context,x,y,width,height,radius,error); }
    }

    /** Reuses the same Canvas/animated-cover surfaces as the popup; static fallback is always available. */
    public boolean drawIslandCover(GuiGraphicsExtractor context,int x,int y,int width,int height,int radius,double opacity) {
        if(islandMotionEnabled) {
            TextureState value=canvasSurface.sample(false);
            if(value==null) value=animatedSurface.sample(false);
            if(value!=null) {
                try { if(drawRoundedCover(context,value,x,y,width,height,radius,Math.clamp(opacity,0,1),1)) return true; }
                catch(RuntimeException unavailable) { /* Keep the cached photo when video cannot render. */ }
            }
        }
        return drawStaticCover(context,x,y,width,height,radius,opacity);
    }

    /** Full-height left artwork, feathered into the player with the same mask as popup Canvas. */
    public boolean drawIslandPanel(GuiGraphicsExtractor context,int x,int y,int width,int height,int feather,int radius,double opacity,boolean video) {
        return drawIslandPanel(context,x,y,width,height,feather,radius,opacity,video,false);
    }
    public boolean drawIslandPanel(GuiGraphicsExtractor context,int x,int y,int width,int height,int feather,int radius,double opacity,boolean video,boolean autoplay) {
        TextureState source=null;
        islandVideoSequence=0;
        if(video && islandMotionEnabled) { source=canvasSurface.sample(false,autoplay); if(source==null) source=animatedSurface.sample(false,autoplay); }
        if(source!=null) {
            MotionSurface owner=canvasSurface.owns(source)?canvasSurface:animatedSurface;
            islandVideoSequence=owner.uploadedSequence;
            return owner.drawRounded(context,source,x,y,width+feather,height,radius,opacity,1,feather>0);
        }
        source=displayedTexture();
        return source!=null && islandStillPanel.draw(context,source,x,y,width+feather,height,radius,opacity,feather>0);
    }

    /** Stable backing storage and a rounded SDF avoid banded scissor clips during fades. */
    public boolean drawIslandBackdrop(GuiGraphicsExtractor context,int x,int y,int width,int height,int radius,double speed,double opacity) {
        TextureState source=null;
        if(islandMotionEnabled || canvasOnCover) source=canvasSurface.sample(false);
        if(source==null && (islandMotionEnabled || animatedCoverEnabled)) source=animatedSurface.sample(false);
        islandBackdropVideoSource=source!=null;
        if(source==null) source=displayedTexture();
        if(source==null || width<=0 || height<=0 || opacity<=.001) return false;
        if(CompatibilityManager.needsExplicitGuiFlush()) PlatformTextures.flush(context);
        islandBackdrop.draw(context,x,y,width,height,source,2,speed,Math.clamp(opacity,0,1),radius,BackdropFx.plain(0,false,0));
        return true;
    }

    /** Draws a two-cover slide, clipped strictly to the requested album-art rectangle. */
    public boolean drawTransition(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        return drawTransition(context, x, y, width, height, 0);
    }

    /** Draws the same transition through a true rounded alpha-shaped clip. */
    public boolean drawTransition(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                  int cornerRadius) {
        try {
            TextureState current = motionCoverTexture();
            if (current == null || width <= 0 || height <= 0) return false;
            TextureState previous = texture == null || current != texture ? null : previousTexture;
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
        return drawBackdrop(context,x,y,width,height,blurStrength,speedMultiplier,opacity,cornerRadius,fx,true);
    }
    public boolean drawBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                int blurStrength, double speedMultiplier, double opacity,
                                int cornerRadius, BackdropFx fx, boolean allowMotion) {
        TextureState canvas = allowMotion && canvasBackdrop ? canvasSurface.sample(true) : null;
        if (canvas != null) {
            if (CompatibilityManager.needsExplicitGuiFlush()) PlatformTextures.flush(context);
            RoundedGui.scissor(context, x, y, width, height, () -> {
                drawRoundedCover(context, canvas, x, y, width, height, cornerRadius, Math.clamp(opacity, 0, 1), .55);
                int veil = Math.clamp((int) Math.round(120 * opacity), 0, 255);
                if (cornerRadius > 0) RoundedGui.smoothFill(context, x, y, width, height, cornerRadius, veil << 24 | 0x080A10);
                else context.fill(x, y, x + width, y + height, veil << 24 | 0x080A10);
            });
            return true;
        }
        TextureState animated = allowMotion && animatedCoverEnabled ? animatedSurface.sample(false) : null;
        TextureState value = animated != null ? animated : displayedTexture();
        if (value == null) return false;
        int strength = Math.clamp(blurStrength, 0, 3);
        double backgroundOpacity = Math.clamp(opacity, 0, 1);
        if (CompatibilityManager.needsExplicitGuiFlush()) PlatformTextures.flush(context);
        Runnable drawer = () -> {
            try {
                if (System.nanoTime() < enhancedBackdropRetryNanos) {
                    throw new IllegalStateException("fluid backdrop cooling down");
                }
                fluidBackdrop.draw(context, x, y, width, height, value, strength, speedMultiplier,
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
        MotionSurface motion = canvasSurface.owns(state) ? canvasSurface : animatedSurface.owns(state) ? animatedSurface : null;
        if (motion != null) return motion.drawRounded(context, state, x, y, width, height, cornerRadius, opacity, brightness);
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
            RoundedGui.clipped(context, x, y, width, height, cornerRadius,
                    () -> drawCover(context, state, x, y, width, height, opacity, brightness));
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
            Identifier id = PlatformTextures.register("lyrica_cover_" + textureInstanceId + "_"
                            + type.name().toLowerCase(Locale.ROOT),
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
            bytes = dev.lyrica.engine.ArtworkImageDecoder.normalize(bytes);
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
        Identifier id = PlatformTextures.register("relaylyrics_cover_" + textureInstanceId + "_"
                + expectedGeneration, nativeTexture);
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
                    || host.equals("spicylyrics.org") || host.endsWith(".spicylyrics.org")
                    || host.equals("lh3.googleusercontent.com") || host.equals("i.ytimg.com")
                    || host.equals("yt3.ggpht.com");
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
        canvasSurface.video.close();
        animatedSurface.video.close();
        generation.incrementAndGet();
        Minecraft.getInstance().execute(() -> {
            destroyTexture();
            canvasSurface.destroyGpu();
            animatedSurface.destroyGpu();
            fallbackTextures.values().forEach(CoverArtTexture::destroy);
            fallbackTextures.clear();
            fluidBackdrop.close();
            islandBackdrop.close();
            islandStillPanel.close();
            for (GlassLens lens : glassLenses) lens.close();
            if (artistArtwork != null) { artistArtwork.close(); artistArtwork = null; }
        });
    }


    /** One small lens per utility slot. Reuses the video's ambient blur and only uploads new frames. */
    private final class GlassLens implements AutoCloseable {
        private DynamicTexture target;
        private Identifier id, sourceId, staticSource;
        private int[] staticBlur, indices;
        private byte[] coverage;
        private int rw, rh, lastWidth, lastHeight, lastRadius;
        private double lastU, lastV, lastUw, lastVh;
        private long sequence = Long.MIN_VALUE;

        void draw(GuiGraphicsExtractor context, TextureState original, TextureState blurred, long frame,
                  int cx, int cy, int cw, int ch, int x, int y, int width, int height, int radius,
                  double opacity, double brightness) {
            final int size = 64;
            if (blurred == null && !original.id().equals(staticSource)) {
                int[] pixels = new int[size * size];
                for (int py = 0; py < size; py++) for (int px = 0; px < size; px++)
                    pixels[py * size + px] = PlatformTextures.colorArgb(original.image(),
                            Math.min(original.width() - 1, px * original.width() / size),
                            Math.min(original.height() - 1, py * original.height() / size));
                staticBlur = GlassButtonRaster.frost(pixels, size, size); staticSource = original.id();
            }
            double ratio = original.width() / (double) original.height(), aspect = cw / (double) ch;
            double cropW = ratio > aspect ? aspect / ratio : 1, cropH = ratio < aspect ? ratio / aspect : 1;
            double u = (1 - cropW) / 2 + (x - cx) / (double) cw * cropW;
            double v = (1 - cropH) / 2 + (y - cy) / (double) ch * cropH;
            double uw = width / (double) cw * cropW, vh = height / (double) ch * cropH;
            int pw = Math.clamp((int) Math.ceil(width * PlatformTextures.guiScale()), 1, 1024);
            int ph = Math.clamp((int) Math.ceil(height * PlatformTextures.guiScale()), 1, 512);
            boolean geometry = target == null || pw != rw || ph != rh || width != lastWidth || height != lastHeight
                    || radius != lastRadius || u != lastU || v != lastV || uw != lastUw || vh != lastVh;
            if (geometry) {
                if (target == null || pw != rw || ph != rh) {
                    if (id != null) PlatformTextures.retireEffectMask(id);
                    target = PlatformTextures.empty("Frosted glass lens", pw, ph);
                    id = PlatformTextures.register("glass_lens_" + textureInstanceId + "_" + TEXTURE_INSTANCE_GENERATION.incrementAndGet(), target);
                }
                rw = pw; rh = ph; lastWidth = width; lastHeight = height; lastRadius = radius;
                lastU = u; lastV = v; lastUw = uw; lastVh = vh;
                indices = new int[rw * rh]; coverage = new byte[indices.length];
                for (int py = 0; py < rh; py++) for (int px = 0; px < rw; px++) {
                    int sx = Math.clamp((int) ((u + (px + .5) / rw * uw) * size), 0, size - 1);
                    int sy = Math.clamp((int) ((v + (py + .5) / rh * vh) * size), 0, size - 1);
                    indices[py * rw + px] = sy * size + sx;
                    coverage[py * rw + px] = (byte) Math.round(255 * GlassButtonRaster.coverage(
                            (px + .5) * width / rw, (py + .5) * height / rh, width, height, radius, rw / (double) width));
                }
            }
            if (geometry || frame != sequence || !original.id().equals(sourceId)) {
                NativeImage output = target.getPixels();
                for (int py = 0; py < rh; py++) for (int px = 0; px < rw; px++) {
                    int index = py * rw + px, sample = indices[index];
                    int color = blurred == null ? staticBlur[sample]
                            : PlatformTextures.colorArgb(blurred.image(), sample % size, sample / size);
                    PlatformTextures.setUiPixel(output, px, py, (coverage[index] & 255) << 24 | color & 0xFFFFFF);
                }
                PlatformTextures.upload(target); sequence = frame; sourceId = original.id();
            }
            PlatformTextures.draw(context, id, x, y, width, height, 0, 0, rw, rh, rw, rh, opacity, brightness);
        }
        @Override public void close() {
            if (id != null) PlatformTextures.retireEffectMask(id);
            target = null; id = null; sourceId = null; staticSource = null; staticBlur = null; indices = null; coverage = null;
        }
    }

    /** Reuses two GPU textures per stream (full frame + small blurred ambient frame).
     * Animated textures bypass the static rounded-composite cache entirely.
     */
    /** Cached photo pixels use the video mask too, keeping fades crisp on every renderer. */
    private final class StaticIslandPanel {
        private Identifier sourceId;
        private int width,height;
        private int[] pixels;
        private final java.util.LinkedHashMap<Shape,PanelFrame> frames=new java.util.LinkedHashMap<>(8,.75f,true);
        private record Shape(int width,int height,int radius,double density,boolean feather) { }
        private final class PanelFrame {
            final RoundedVideoRaster plan;
            final DynamicTexture texture;
            final Identifier id;
            PanelFrame(Shape shape) {
                plan=new RoundedVideoRaster(width,height,shape.width(),shape.height(),shape.radius(),shape.density(),shape.feather(),false);
                int[] output=new int[plan.width()*plan.height()];plan.render(pixels,output);
                texture=PlatformTextures.empty("Island artwork",plan.width(),plan.height());
                id=PlatformTextures.register("island_art_"+TEXTURE_INSTANCE_GENERATION.incrementAndGet(),texture);
                NativeImage image=texture.getPixels();
                for(int y=0;y<plan.height();y++) for(int x=0;x<plan.width();x++) PlatformTextures.setUiPixel(image,x,y,output[y*plan.width()+x]);
                PlatformTextures.upload(texture);
            }
        }
        boolean draw(GuiGraphicsExtractor context,TextureState source,int x,int y,int w,int h,int radius,double opacity,boolean feather) {
            if(w<=0 || h<=0 || opacity<=.001) return false;
            if(!source.id().equals(sourceId)) {
                close();sourceId=source.id();
                double ratio=Math.min(1,512.0/Math.max(source.width(),source.height()));
                width=Math.max(1,(int)Math.ceil(source.width()*ratio));height=Math.max(1,(int)Math.ceil(source.height()*ratio));
                pixels=new int[width*height];
                for(int py=0;py<height;py++) for(int px=0;px<width;px++) pixels[py*width+px]=PlatformTextures.colorArgb(source.image(),
                        Math.min(source.width()-1,(int)((px+.5)*source.width()/width)),Math.min(source.height()-1,(int)((py+.5)*source.height()/height)));
            }
            Shape shape=new Shape(w,h,radius,PlatformTextures.guiScale(),feather);
            PanelFrame frame=frames.get(shape);
            if(frame==null) { frame=new PanelFrame(shape);frames.put(shape,frame); }
            PlatformTextures.draw(context,frame.id,x,y,w,h,0,0,frame.plan.width(),frame.plan.height(),frame.plan.width(),frame.plan.height(),opacity,1);
            while(frames.size()>6) { var it=frames.entrySet().iterator();var old=it.next();it.remove();PlatformTextures.retireEffectMask(old.getValue().id); }
            return true;
        }
        void close() { for(var frame:frames.values()) PlatformTextures.retireEffectMask(frame.id);frames.clear();pixels=null;sourceId=null; }
    }

    private final class MotionSurface {
        private final String name;
        private final VideoLoop video = new VideoLoop(Boolean.getBoolean("lyrica.motionSelfTest"));
        private volatile String url;
        private volatile long sourceVersion;
        private long gpuVersion = -1, uploadedSequence, ambientSequence, lastPaletteNanos;
        private DynamicTexture videoTexture, ambientTexture;
        private TextureState state, ambient;
        private VideoLoop.PixelFrame pixels;
        private PaletteData palette;

        private final java.util.LinkedHashMap<VideoShape, RoundedFrame> rounded = new java.util.LinkedHashMap<>(8, .75f, true);
        private record VideoShape(boolean ambient, int width, int height, int radius, double density, boolean canvasSurface) { }
        private final class RoundedFrame {
            final RoundedVideoRaster plan;
            final DynamicTexture texture;
            final Identifier id;
            final int[] output;
            long sequence = -1;
            RoundedFrame(VideoShape key, int sourceWidth, int sourceHeight) {
                plan = new RoundedVideoRaster(sourceWidth, sourceHeight, key.width(), key.height(), key.radius(), key.density(), key.canvasSurface());
                output = new int[plan.width() * plan.height()];
                texture = PlatformTextures.empty("Rounded Canvas", plan.width(), plan.height());
                id = PlatformTextures.register("rounded_motion_" + textureInstanceId + "_" + name + "_" + TEXTURE_INSTANCE_GENERATION.incrementAndGet(), texture);
            }
        }
        synchronized boolean drawRounded(GuiGraphicsExtractor context, TextureState source, int x, int y, int width, int height,
                                          int radius, double opacity, double brightness) {
            return drawRounded(context, source, x, y, width, height, radius, opacity, brightness, false);
        }
        synchronized boolean drawRounded(GuiGraphicsExtractor context, TextureState source, int x, int y, int width, int height,
                                          int radius, double opacity, double brightness, boolean canvasSurface) {
            boolean blurred = ambient != null && source.id().equals(ambient.id());
            VideoShape key = new VideoShape(blurred, width, height, radius, PlatformTextures.guiScale(), canvasSurface);
            RoundedFrame frame = rounded.get(key);
            if (frame == null) { frame = new RoundedFrame(key, source.width(), source.height()); rounded.put(key, frame); }
            if (frame.sequence != uploadedSequence) {
                int[] data;
                if (!blurred && pixels != null) data = pixels.argb();
                else {
                    data = new int[source.width() * source.height()];
                    for (int py = 0; py < source.height(); py++) for (int px = 0; px < source.width(); px++)
                        data[py * source.width() + px] = PlatformTextures.colorArgb(source.image(), px, py);
                }
                frame.plan.render(data, frame.output);
                NativeImage image = frame.texture.getPixels();
                for (int py = 0; py < frame.plan.height(); py++) for (int px = 0; px < frame.plan.width(); px++)
                    PlatformTextures.setColorArgb(image, px, py, frame.output[py * frame.plan.width() + px]);
                PlatformTextures.upload(frame.texture); frame.sequence = uploadedSequence;
            }
            PlatformTextures.draw(context, frame.id, x, y, width, height, 0, 0, frame.plan.width(), frame.plan.height(),
                    frame.plan.width(), frame.plan.height(), opacity, brightness);
            while (rounded.size() > 6) { var it = rounded.entrySet().iterator(); var old = it.next(); it.remove(); PlatformTextures.retireEffectMask(old.getValue().id); }
            return true;
        }
        MotionSurface(String name) { this.name = name; }
        synchronized void setUrl(String next) {
            if (java.util.Objects.equals(url, next)) return;
            url = next;
            sourceVersion++;
            video.setSource(next);
            visibleMotionKind = "static";
        }
        boolean owns(TextureState value) {
            return value != null && (state != null && value.id().equals(state.id())
                    || ambient != null && value.id().equals(ambient.id()));
        }
        synchronized TextureState sample(boolean ambientWanted) {return sample(ambientWanted,false);}
        synchronized TextureState sample(boolean ambientWanted,boolean autoplay) {
            if (closed) return null;
            long expected = sourceVersion;
            if (gpuVersion != expected) { destroyGpu(); gpuVersion = expected; }
            if (url == null) return null;
            video.touchVisible(autoplay);
            VideoLoop.PixelFrame latest = video.latest();
            if (latest == null || expected != sourceVersion) return null;
            if (latest.sequence() != uploadedSequence) {
                if (state == null || state.width() != latest.width() || state.height() != latest.height()) {
                    destroyGpu(); gpuVersion = expected;
                    NativeImage image = new NativeImage(latest.width(), latest.height(), true);
                    videoTexture = PlatformTextures.fromImage("Lyrica " + name, image);
                    Identifier id = PlatformTextures.register("fandroid_motion_" + textureInstanceId + "_" + name + "_" + expected, videoTexture);
                    state = new TextureState(id, image, latest.width(), latest.height(), 0xFF26334A, new int[]{0xFF26334A,0xFF3B2D47,0xFF172833});
                }
                NativeImage image = state.image();
                for (int y = 0; y < latest.height(); y++) for (int x = 0; x < latest.width(); x++)
                    PlatformTextures.setColorArgb(image, x, y, latest.argb()[y * latest.width() + x]);
                PlatformTextures.upload(videoTexture);
                if (palette == null || System.nanoTime() - lastPaletteNanos > 100_000_000L) {
                    palette = extractPalette(image); lastPaletteNanos = System.nanoTime();
                }
                state = new TextureState(state.id(), image, latest.width(), latest.height(), palette.accent(), palette.colors());
                pixels = latest;
                uploadedSequence = latest.sequence();
            }
            if (!ambientWanted) return state;
            if (ambientSequence != uploadedSequence && pixels != null) updateAmbient(expected);
            return ambient;
        }
        private void updateAmbient(long expected) {
            final int size = 64;
            if (ambient == null) {
                NativeImage image = new NativeImage(size, size, true);
                ambientTexture = PlatformTextures.fromImage("Lyrica ambient " + name, image);
                Identifier id = PlatformTextures.register("fandroid_motion_ambient_" + textureInstanceId + "_" + name + "_" + expected, ambientTexture);
                ambient = new TextureState(id, image, size, size, state.accent(), state.palette());
            }
            int[] colors = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                colors[y * size + x] = pixels.argb()[Math.min(pixels.height() - 1, y * pixels.height() / size) * pixels.width()
                        + Math.min(pixels.width() - 1, x * pixels.width() / size)];
            for (int pass = 0; pass < 3; pass++) {
                int[] blurred = new int[colors.length];
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    int r = 0, g = 0, b = 0, count = 0;
                    for (int d = -4; d <= 4; d++) {
                        int xx = (pass & 1) == 0 ? Math.clamp(x + d, 0, size - 1) : x;
                        int yy = (pass & 1) == 0 ? y : Math.clamp(y + d, 0, size - 1);
                        int color = colors[yy * size + xx];
                        r += color >>> 16 & 255; g += color >>> 8 & 255; b += color & 255; count++;
                    }
                    blurred[y * size + x] = 0xFF000000 | r / count << 16 | g / count << 8 | b / count;
                }
                colors = blurred;
            }
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                PlatformTextures.setColorArgb(ambient.image(), x, y, colors[y * size + x]);
            PlatformTextures.upload(ambientTexture);
            ambientSequence = uploadedSequence;
        }
        void destroyGpu() {
            for (RoundedFrame frame : rounded.values()) PlatformTextures.retireEffectMask(frame.id);
            rounded.clear();
            if (state != null) destroy(state);
            if (ambient != null) destroy(ambient);
            state = null; ambient = null; videoTexture = null; ambientTexture = null;
            pixels = null; palette = null; uploadedSequence = 0; ambientSequence = 0;
        }
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
        private static final int SIZE = KawarpRaster.SIZE, MAX_COMPOSITE = 1024;
        private final boolean reuseStillFrames;
        private BackdropFx lastFx;
        private FluidBackdrop() { this(false); }
        private FluidBackdrop(boolean reuseStillFrames) { this.reuseStillFrames=reuseStillFrames; }
        private static final AtomicLong COMPOSITE_GENERATION = new AtomicLong();
        private DynamicTexture composite;
        private Identifier compositeId, sourceId;
        private int compositeWidth, compositeHeight, visibleWidth, visibleHeight, paintedWidth, paintedHeight;
        private int compositeRadius = -1, preparedBlur = -1;
        private BackdropSamplingPlan samplingPlan;
        private long lastFrame, lastSource, transitionStarted;
        private double motionTime, currentSpeed, hueShift, bloomGlow;
        private float[] current, previous;
        private final int[] frame = new int[SIZE * SIZE];

        private void draw(GuiGraphicsExtractor context, int x, int y, int width, int height, TextureState source, int blurStrength,
                          double speedMultiplier, double opacity, int cornerRadius, BackdropFx fx) {
            speedMultiplier = Double.isFinite(speedMultiplier) ? Math.clamp(speedMultiplier, 0, 8) : 0;
            long now = System.nanoTime();
            boolean changed = sourceId == null || !sourceId.equals(source.id()) || preparedBlur != blurStrength;
            boolean animatedSource = source.id().getPath().contains("motion");
            if (changed || animatedSource && now - lastSource >= 200_000_000L) {
                if (changed && current != null) {
                    double blend = Math.clamp((now - transitionStarted) / 1_000_000_000.0, 0, 1);
                    if (previous != null && blend < 1) {
                        for (int i = 0; i < current.length; i++) current[i] = (float) (previous[i] + (current[i] - previous[i]) * blend);
                    }
                    previous = current; transitionStarted = now;
                }
                current = prepare(source, switch (blurStrength) { case 0 -> 1; case 1 -> 4; case 3 -> 12; default -> 8; });
                preparedBlur = blurStrength;
                sourceId = source.id(); lastSource = now;
            }
            boolean changing=!reuseStillFrames || speedMultiplier>0 || currentSpeed>.001 || previous!=null || !fx.equals(lastFx);
            boolean animate = changed || lastFrame == 0 || changing && now - lastFrame >= 33_333_333L;
            if (animate) {
                double delta = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1_000_000_000.0, 0, .1);
                if (speedMultiplier <= 0) currentSpeed = 0;
                else currentSpeed += (speedMultiplier - currentSpeed) * (1 - Math.exp(-delta * 3.0776));
                motionTime += delta * currentSpeed;
                double blend = previous == null ? 1 : Math.clamp((now - transitionStarted) / 1_000_000_000.0, 0, 1);
                KawarpRaster.render(previous, current, blend, motionTime, frame);
                if (blend >= 1) previous = null;
                hueShift = fx.hueAuto() ? (hueShift + fx.huePulse() * 150 * delta) % 360 : fx.huePulse() * 80;
                bloomGlow += (fx.bloomPulse() - bloomGlow) * (1 - Math.exp(-delta * 5));
                for (int py = 0; py < SIZE; py++) for (int px = 0; px < SIZE; px++) {
                    int color = frame[py * SIZE + px];
                    if (Math.abs(hueShift) > .01) color = rotateHue(color, hueShift);
                    if (fx.tint()) color = tint(color, fx.centroid() < .5 ? 0xFFE06020 : 0xFF3090E8,
                            Math.abs(Math.clamp(fx.centroid(), 0, 1) - .5) * 2 * fx.tintStrength());
                    if (bloomGlow > .01) {
                        double lift = 1 + bloomGlow * .25;
                        color = 0xFF000000 | Math.min(255, (int) ((color >>> 16 & 255) * lift)) << 16
                                | Math.min(255, (int) ((color >>> 8 & 255) * lift)) << 8 | Math.min(255, (int) ((color & 255) * lift));
                    }
                    frame[py * SIZE + px] = color;
                }
                lastFrame = now; lastFx=fx;
            }
            resizeComposite(width, height);
            if (animate || compositeRadius != cornerRadius || paintedWidth!=visibleWidth || paintedHeight!=visibleHeight)
                compositeField(width, height, cornerRadius);
            PlatformTextures.draw(context, compositeId, x, y, width, height, 0, 0, visibleWidth, visibleHeight,
                    compositeWidth, compositeHeight, opacity, 1);
        }

        private static float[] prepare(TextureState source, int passes) {
            int[] pixels = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                double sx = (x + .5) * source.width() / SIZE - .5, sy = (y + .5) * source.height() / SIZE - .5;
                int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy);
                double tx = sx - ix, ty = sy - iy;
                int a = PlatformTextures.colorArgb(source.image(), Math.clamp(ix, 0, source.width() - 1), Math.clamp(iy, 0, source.height() - 1));
                int b = PlatformTextures.colorArgb(source.image(), Math.clamp(ix + 1, 0, source.width() - 1), Math.clamp(iy, 0, source.height() - 1));
                int c = PlatformTextures.colorArgb(source.image(), Math.clamp(ix, 0, source.width() - 1), Math.clamp(iy + 1, 0, source.height() - 1));
                int d = PlatformTextures.colorArgb(source.image(), Math.clamp(ix + 1, 0, source.width() - 1), Math.clamp(iy + 1, 0, source.height() - 1));
                int color = 0xFF000000;
                for (int shift = 0; shift < 24; shift += 8) color |= (int) Math.round(bilinear(a >>> shift & 255, b >>> shift & 255, c >>> shift & 255, d >>> shift & 255, tx, ty)) << shift;
                pixels[y * SIZE + x] = color;
            }
            return KawarpRaster.prepare(pixels, SIZE, SIZE, passes);
        }

        private void resizeComposite(int width, int height) {
            double density=reuseStillFrames?Math.min(Math.max(1,PlatformTextures.guiScale()),Math.min(384.0/Math.max(1,width),160.0/Math.max(1,height))):1;
            visibleWidth=Math.clamp((int)Math.round(width*density),1,reuseStillFrames?384:MAX_COMPOSITE);
            visibleHeight=Math.clamp((int)Math.round(height*density),1,reuseStillFrames?160:MAX_COMPOSITE);
            int compositeWidth = reuseStillFrames?384:visibleWidth;
            int compositeHeight = reuseStillFrames?160:visibleHeight;
            if (composite != null && this.compositeWidth == compositeWidth
                    && this.compositeHeight == compositeHeight) return;
            if (composite != null) {
                PlatformTextures.retireEffectMask(compositeId);
                composite = null;
                compositeId = null;
            }
            composite = PlatformTextures.empty("FanDroid fluid composite",
                    compositeWidth, compositeHeight);
            compositeId = PlatformTextures.register("relaylyrics_fluid_composite_"
                    + COMPOSITE_GENERATION.incrementAndGet(), composite);
            this.compositeWidth = compositeWidth;
            this.compositeHeight = compositeHeight;
            this.compositeRadius = -1;
            this.samplingPlan = null;
        }

        /** Upscales the 128px field to display resolution and clips it with the popup's
         *  signed-distance rounding (same SDF as the border), so corners stay crisp. */
        private void compositeField(int width, int height, int cornerRadius) {
            NativeImage target = composite.getPixels();
            if (target == null) return;
            int outputWidth = visibleWidth;
            int outputHeight = visibleHeight;
            int radius = reuseStillFrames?(int)Math.round(cornerRadius*outputWidth/(double)Math.max(1,width)):cornerRadius;
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
                    int c00 = frame[y0 * SIZE + x0];
                    int c10 = frame[y0 * SIZE + x1];
                    int c01 = frame[y1 * SIZE + x0];
                    int c11 = frame[y1 * SIZE + x1];
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
            if(outputWidth<compositeWidth) for(int py=0;py<outputHeight;py++) PlatformTextures.setUiPixel(target,outputWidth,py,0);
            if(outputHeight<compositeHeight) for(int px=0;px<=Math.min(outputWidth,compositeWidth-1);px++) PlatformTextures.setUiPixel(target,px,outputHeight,0);
            composite.upload();
            compositeRadius = cornerRadius; paintedWidth=outputWidth; paintedHeight=outputHeight;
        }

        private static double bilinear(double a, double b, double c, double d,
                                       double tx, double ty) {
            double top = a + (b - a) * tx;
            double bottom = c + (d - c) * tx;
            return top + (bottom - top) * ty;
        }


        @Override public void close() {
            if (compositeId != null) PlatformTextures.retireEffectMask(compositeId);
            composite = null; compositeId = null; sourceId = null;
            current = null; previous = null; samplingPlan = null;
            compositeWidth = compositeHeight = visibleWidth = visibleHeight = paintedWidth = paintedHeight = 0; compositeRadius = -1;
            lastFrame = lastSource = 0; motionTime = currentSpeed = hueShift = bloomGlow = 0;
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
