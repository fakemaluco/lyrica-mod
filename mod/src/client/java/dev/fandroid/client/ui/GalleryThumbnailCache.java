package dev.fandroid.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small, bounded screenshot thumbnails used by the Relay gallery grid. */
final class GalleryThumbnailCache implements AutoCloseable {
    private static final int SOURCE_WIDTH = 320;
    private static final int SOURCE_HEIGHT = 162;
    private final Map<Path, Identifier> textures = new HashMap<>();
    private final Set<Path> failed = new HashSet<>();

    boolean draw(DrawContext context, Path file, int x, int y, int width, int height) {
        Path key = file.toAbsolutePath().normalize();
        if (failed.contains(key)) return false;
        Identifier texture = textures.computeIfAbsent(key, this::load);
        if (texture == null) {
            failed.add(key);
            return false;
        }
        PlatformGalleryTextures.draw(context, texture, x, y, width, height,
                SOURCE_WIDTH, SOURCE_HEIGHT);
        return true;
    }

    private Identifier load(Path file) {
        try {
            BufferedImage source = ImageIO.read(file.toFile());
            if (source == null) return null;
            BufferedImage thumbnail = new BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                double scale = Math.max(SOURCE_WIDTH / (double) source.getWidth(),
                        SOURCE_HEIGHT / (double) source.getHeight());
                int drawWidth = (int) Math.ceil(source.getWidth() * scale);
                int drawHeight = (int) Math.ceil(source.getHeight() * scale);
                graphics.drawImage(source, (SOURCE_WIDTH - drawWidth) / 2, (SOURCE_HEIGHT - drawHeight) / 2,
                        drawWidth, drawHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "png", encoded);
            NativeImage image = NativeImage.read(new ByteArrayInputStream(encoded.toByteArray()));
            return PlatformGalleryTextures.install("relay_gallery_"
                    + Integer.toUnsignedString(file.hashCode(), 36), image);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        textures.values().stream().filter(id -> id != null).forEach(id ->
                MinecraftClient.getInstance().getTextureManager().destroyTexture(id));
        textures.clear();
        failed.clear();
    }
}
