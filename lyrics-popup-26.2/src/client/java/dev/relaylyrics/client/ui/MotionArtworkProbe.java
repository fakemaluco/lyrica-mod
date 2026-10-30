package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.music.CoverArtTexture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/** Opt-in, local-video GL verification. Never opens or connects to services in normal use. */
public final class MotionArtworkProbe extends Screen {
    private static final int COVER_X = 48, COVER_Y = 94, COVER_WIDTH = 238, COVER_HEIGHT = 304;
    private final CoverArtTexture artwork = new CoverArtTexture();
    private final List<String> captures = new ArrayList<>();
    private final String fixture;
    private final String missingFixture;
    private int stage, stageTicks, totalTicks;
    private boolean capturePending, finished;
    private long staticHash, playingHash, pausedHash, pausedSequence, playingSequence, playingBackdropHash;

    public MotionArtworkProbe() {
        super(Component.literal("Motion artwork probe"));
        if (!Boolean.getBoolean("lyrica.motionSelfTest")) throw new IllegalStateException("Motion probe is opt-in");
        Path file = Path.of(System.getProperty("lyrica.motionProbeVideo", "build/motion-video-fixture.mp4"))
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IllegalStateException("Generate the local video fixture first: " + file);
        fixture = file.toUri().toString();
        missingFixture = file.resolveSibling("missing-motion-probe-file.mp4").toUri().toString();
        artwork.update(stillImage());
        artwork.setMotionPlaying(true);
    }

    public static void start() { if (Boolean.getBoolean("lyrica.canvasLayoutSelfTest")) { CanvasLayoutProbe.start(); return; } Minecraft.getInstance().gui.setScreen(new MotionArtworkProbe()); }

    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xFF0B0E13);
        context.text(font, "MOTION ARTWORK / local MP4, shared cover and background", 24, 20, 0xFFF3F4F6, false);
        context.text(font, "stage " + stage + " / " + artwork.motionKind() + " / frame " + artwork.motionFrameSequence(),
                24, 39, 0xFFAEB8C8, false);
        context.text(font, artwork.motionStatus(), 24, 56, 0xFF9DA9BD, false);
        artwork.drawTransition(context, COVER_X, COVER_Y, COVER_WIDTH, COVER_HEIGHT, 18);
        artwork.drawBackdrop(context, 320, 94, 486, 304, 2, 1.0, 1.0, 18);
        artwork.draw(context, 738, 110, 50, 50, 7);
        context.text(font, "Rounded cover", COVER_X, 416, 0xFFBEC9DA, false);
        context.text(font, "Video backdrop + shared thumbnail", 320, 416, 0xFFBEC9DA, false);
    }

    @Override public void tick() {
        if (finished) return;
        if (++totalTicks > 1200) throw new IllegalStateException("Motion GL probe timed out at stage " + stage
                + ": " + artwork.motionStatus());
        if (capturePending) return;
        stageTicks++;
        switch (stage) {
            case 0 -> { if (stageTicks >= 35) capture("static"); }
            case 1 -> { if (artwork.motionReady() && artwork.motionFrameSequence() >= 3) capture("playing-a"); }
            case 2 -> { if (artwork.motionFrameSequence() >= playingSequence + 8) capture("playing-b"); }
            case 3 -> { if (stageTicks >= 10) capture("paused-a"); }
            case 4 -> { if (stageTicks >= 10) capture("paused-b"); }
            case 5 -> {
                if (artwork.motionReady() && artwork.motionKind().equals("animated") && stageTicks >= 12)
                    capture("animated-cover");
            }
            case 6 -> { if (stageTicks >= 12) capture("cleared"); }
            case 7 -> { if (stageTicks >= 40) capture("failed-video-fallback"); }
            default -> throw new IllegalStateException("Unknown motion probe stage");
        }
    }

    private void captured(String name, long hash, long backdropHash) {
        switch (stage) {
            case 0 -> {
                staticHash = hash;
                artwork.configureMotion(fixture, fixture, true, true, true, true);
            }
            case 1 -> {
                if (!artwork.motionKind().equals("canvas")) throw new IllegalStateException("Canvas did not take priority");
                if (hash == staticHash) throw new IllegalStateException("Decoded video never reached the framebuffer");
                playingHash = hash; playingBackdropHash = backdropHash; playingSequence = artwork.motionFrameSequence();
            }
            case 2 -> {
                if (hash == playingHash) throw new IllegalStateException("Video cover pixels did not change between frames");
                if (backdropHash == playingBackdropHash) throw new IllegalStateException("Video backdrop pixels did not change between frames");
                artwork.setMotionPlaying(false);
            }
            case 3 -> { pausedHash = hash; pausedSequence = artwork.motionFrameSequence(); }
            case 4 -> {
                if (hash != pausedHash || pausedSequence != artwork.motionFrameSequence())
                    throw new IllegalStateException("Paused music did not hold the same video frame");
                // A paused song must still decode and display its first album-cover frame.
                artwork.setMotionPlaying(false);
                artwork.configureMotion(null, fixture, false, true, false, true);
            }
            case 5 -> {
                if (hash == staticHash) throw new IllegalStateException("Animated album cover did not render");
                artwork.clearMotion();
            }
            case 6 -> {
                if (hash != staticHash) throw new IllegalStateException("Clearing the song failed to restore the still cover");
                artwork.configureMotion(missingFixture, null, true, true, true, true);
            }
            case 7 -> {
                if (artwork.motionReady() || hash != staticHash)
                    throw new IllegalStateException("Failed video did not restore the still cover");
                finished = true;
                artwork.close();
                FanDroidMod.LOGGER.info("Motion artwork GL probe passed: changing MP4 frames, Canvas priority, shared backdrop, pause, paused-first album frame, song clear and failed-video fallback ({})", captures);
                Minecraft.getInstance().stop();
            }
            default -> throw new IllegalStateException("Unknown capture stage " + name);
        }
        stage++; stageTicks = 0; capturePending = false;
    }

    private void capture(String name) {
        capturePending = true;
        var client = Minecraft.getInstance();
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString();
                Path directory = Path.of(System.getProperty("lyrica.motionProbeOutput", "build/motion-artwork-qa"))
                        .toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Path output = directory.resolve("motion-" + version + "-" + name + ".png");
                image.writeToFile(output);
                BufferedImage pixels = ImageIO.read(output.toFile());
                double densityX = pixels.getWidth() / (double) width, densityY = pixels.getHeight() / (double) height;
                int corner = pixels.getRGB((int) ((COVER_X + 1) * densityX), (int) ((COVER_Y + 1) * densityY)) & 0xFFFFFF;
                if (corner != 0x0B0E13) throw new IllegalStateException("Canvas cover has a nontransparent square corner");

                long hash = cropHash(pixels, COVER_X + 30, COVER_Y + 30, COVER_WIDTH - 60, COVER_HEIGHT - 60);
                long backdropHash = cropHash(pixels, 350, 180, 330, 160);
                captures.add(name + ":" + Long.toHexString(hash));
                FanDroidMod.LOGGER.info("Motion screenshot: {}", output);
                captured(name, hash, backdropHash);
            } catch (Exception error) { throw new IllegalStateException("Motion framebuffer capture failed", error); }
        };
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), writer);
    }

    private long cropHash(BufferedImage image, int x0, int y0, int w, int h) {
        // Sample inside the panel: excludes rounded edges, labels and outside effects.
        double sx = image.getWidth() / (double) width, sy = image.getHeight() / (double) height;
        int left = (int) (x0 * sx), top = (int) (y0 * sy);
        int right = (int) ((x0 + w) * sx), bottom = (int) ((y0 + h) * sy);
        long hash = 0xcbf29ce484222325L;
        for (int y = top; y < bottom; y += 3) for (int x = left; x < right; x += 3) {
            hash ^= image.getRGB(x, y); hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String stillImage() {
        try {
            BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++)
                image.setRGB(x, y, ((x / 12 + y / 12) & 1) == 0 ? 0x47285D : 0x8B5CA8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception error) { throw new IllegalStateException("Could not generate still artwork fixture", error); }
    }

    @Override public void removed() { artwork.close(); }
    @Override public boolean shouldCloseOnEsc() { return false; }
}
