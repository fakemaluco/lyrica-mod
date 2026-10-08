package dev.fandroid.client.music;

import java.util.List;
import java.util.Objects;

/** Glyph coverage shared by every graphics adapter. Colors are applied only when drawing. */
public final class LyricsEffectRaster {
    public static final int MAX_SIDE = 1024;
    public static final int MAX_PIXELS = MAX_SIDE * MAX_SIDE;
    private static final double MAX_SIGMA_PIXELS = 32;

    private LyricsEffectRaster() { }

    /** Alpha is owned by this mask; callers must treat the returned array as read-only. */
    public record Mask(int width, int height, double left, double top,
                       double pixelsPerUnit, byte[] alpha) {
        public Mask {
            validateRaster(width, height, alpha);
            validateOrigin(left, top);
            validatePixelsPerUnit(pixelsPerUnit);
            alpha = alpha.clone();
        }
    }

    /** A glyph atlas region mapped to its actual drawn rectangle, including font bearings. */
    public record Quad(double left, double top, double right, double bottom,
                       int width, int height, byte[] alpha) {
        public Quad {
            validateRaster(width, height, alpha);
            validateOrigin(left, top);
            validateOrigin(right, bottom);
            if (!(right > left) || !(bottom > top)
                    || !Double.isFinite(right - left) || !Double.isFinite(bottom - top)) {
                throw new IllegalArgumentException("Glyph rectangles must have finite positive extents");
            }
            alpha = alpha.clone();
        }
    }

    /** Compose atlas coverage with bilinear pixel-center sampling and ordinary source-over alpha. */
    public static Mask compose(List<Quad> quads, double pixelsPerUnit) {
        Objects.requireNonNull(quads, "quads");
        validatePixelsPerUnit(pixelsPerUnit);
        if (quads.isEmpty()) return new Mask(1, 1, 0, 0, pixelsPerUnit, new byte[1]);
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (Quad quad : quads) {
            Objects.requireNonNull(quad, "quad");
            left = Math.min(left, quad.left);
            top = Math.min(top, quad.top);
            right = Math.max(right, quad.right);
            bottom = Math.max(bottom, quad.bottom);
        }
        double unitsWidth = right - left, unitsHeight = bottom - top;
        if (!Double.isFinite(unitsWidth) || !Double.isFinite(unitsHeight)) {
            throw new IllegalArgumentException("Composition bounds must be finite");
        }
        double actualPpu = Math.min(pixelsPerUnit,
                Math.min(MAX_SIDE / unitsWidth, MAX_SIDE / unitsHeight));
        validatePixelsPerUnit(actualPpu);
        int width = boundedDimension(unitsWidth * actualPpu);
        int height = boundedDimension(unitsHeight * actualPpu);
        double[] coverage = new double[width * height];
        for (Quad quad : quads) {
            int startX = Math.max(0, (int) Math.ceil((quad.left - left) * actualPpu - .5));
            int startY = Math.max(0, (int) Math.ceil((quad.top - top) * actualPpu - .5));
            int endX = Math.min(width, (int) Math.ceil((quad.right - left) * actualPpu - .5));
            int endY = Math.min(height, (int) Math.ceil((quad.bottom - top) * actualPpu - .5));
            for (int y = startY; y < endY; y++) {
                double sourceY = ((top + (y + .5) / actualPpu - quad.top)
                        / (quad.bottom - quad.top)) * quad.height - .5;
                for (int x = startX; x < endX; x++) {
                    double sourceX = ((left + (x + .5) / actualPpu - quad.left)
                            / (quad.right - quad.left)) * quad.width - .5;
                    double alpha = sample(quad.alpha, quad.width, quad.height, sourceX, sourceY);
                    int index = y * width + x;
                    coverage[index] = alpha + coverage[index] * (1 - alpha / 255);
                }
            }
        }
        return new Mask(width, height, left, top, actualPpu, quantize(coverage));
    }

    /** CSS text-shadow radius: Gaussian sigma is half the supplied radius in drawing units. */
    public static Mask blur(Mask source, double cssBlurRadiusInUnits) {
        Objects.requireNonNull(source, "source");
        if (!Double.isFinite(cssBlurRadiusInUnits) || cssBlurRadiusInUnits < 0) {
            throw new IllegalArgumentException("Blur radius must be finite and nonnegative");
        }
        if (cssBlurRadiusInUnits == 0) return source;
        double sigmaUnits = cssBlurRadiusInUnits / 2;
        double unitsWidth = source.width / source.pixelsPerUnit;
        double unitsHeight = source.height / source.pixelsPerUnit;
        double ppu = source.pixelsPerUnit;
        // Lower raster resolution only when the complete physical effect would exceed the budget.
        ppu = Math.min(ppu, MAX_SIGMA_PIXELS / sigmaUnits);
        ppu = Math.min(ppu, (MAX_SIDE - 8) / (unitsWidth + 6 * sigmaUnits));
        ppu = Math.min(ppu, (MAX_SIDE - 8) / (unitsHeight + 6 * sigmaUnits));
        validatePixelsPerUnit(ppu);
        Mask prepared = ppu < source.pixelsPerUnit ? resample(source, ppu) : source;
        double sigma = sigmaUnits * prepared.pixelsPerUnit;
        int radius = (int) Math.ceil(3 * sigma);
        int padding = radius + 2;
        int width = prepared.width + 2 * padding;
        int height = prepared.height + 2 * padding;
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new IllegalArgumentException("Blur bounds exceed the raster budget");
        }
        double[] kernel = gaussian(sigma, radius);
        double[] horizontal = new double[width * height];
        double[] blurred = new double[width * height];
        // Scatter only actual glyph samples; transparent margins do not add work or color.
        for (int y = 0; y < prepared.height; y++) {
            int targetRow = (y + padding) * width;
            for (int x = 0; x < prepared.width; x++) {
                int alpha = prepared.alpha[y * prepared.width + x] & 255;
                if (alpha == 0) continue;
                for (int k = -radius; k <= radius; k++) {
                    horizontal[targetRow + x + padding + k] += alpha * kernel[k + radius];
                }
            }
        }
        for (int y = padding; y < prepared.height + padding; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                double alpha = horizontal[row + x];
                if (alpha == 0) continue;
                for (int k = -radius; k <= radius; k++) {
                    blurred[(y + k) * width + x] += alpha * kernel[k + radius];
                }
            }
        }
        return new Mask(width, height,
                prepared.left - padding / prepared.pixelsPerUnit,
                prepared.top - padding / prepared.pixelsPerUnit,
                prepared.pixelsPerUnit, quantize(blurred));
    }

    private static Mask resample(Mask source, double pixelsPerUnit) {
        int width = boundedDimension(source.width / source.pixelsPerUnit * pixelsPerUnit);
        int height = boundedDimension(source.height / source.pixelsPerUnit * pixelsPerUnit);
        double ratio = source.pixelsPerUnit / pixelsPerUnit;
        double[] result = new double[width * height];
        for (int y = 0; y < height; y++) {
            double sourceY = (y + .5) * ratio - .5;
            for (int x = 0; x < width; x++) {
                double sourceX = (x + .5) * ratio - .5;
                // The extra ceil-sized pixel is transparent if its center lies outside the quad.
                if (sourceX + .5 >= source.width || sourceY + .5 >= source.height) continue;
                result[y * width + x] = sample(source.alpha, source.width, source.height, sourceX, sourceY);
            }
        }
        return new Mask(width, height, source.left, source.top, pixelsPerUnit, quantize(result));
    }

    private static double sample(byte[] alpha, int width, int height, double x, double y) {
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1), y1 = Math.min(height - 1, y0 + 1);
        double tx = x - x0, ty = y - y0;
        double upper = (alpha[y0 * width + x0] & 255) * (1 - tx)
                + (alpha[y0 * width + x1] & 255) * tx;
        double lower = (alpha[y1 * width + x0] & 255) * (1 - tx)
                + (alpha[y1 * width + x1] & 255) * tx;
        return upper * (1 - ty) + lower * ty;
    }

    private static double[] gaussian(double sigma, int radius) {
        double[] kernel = new double[radius * 2 + 1];
        if (sigma < 1e-8) {
            kernel[radius] = 1;
            return kernel;
        }
        double sum = 0;
        for (int x = -radius; x <= radius; x++) {
            double weight = Math.exp(-.5 * (x / sigma) * (x / sigma));
            kernel[x + radius] = weight;
            sum += weight;
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        return kernel;
    }

    private static byte[] quantize(double[] coverage) {
        byte[] alpha = new byte[coverage.length];
        double remainder = 0;
        for (int i = 0; i < coverage.length; i++) {
            double value = Math.max(0, Math.min(255, coverage[i]));
            // Carry sub-byte rounding error so antialiased strokes retain their total coverage.
            int rounded = (int) Math.max(0, Math.min(255, Math.round(value + remainder)));
            remainder += value - rounded;
            alpha[i] = (byte) rounded;
        }
        return alpha;
    }

    private static int boundedDimension(double pixels) {
        return Math.max(1, Math.min(MAX_SIDE, (int) Math.ceil(pixels)));
    }

    private static void validateRaster(int width, int height, byte[] alpha) {
        Objects.requireNonNull(alpha, "alpha");
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                || (long) width * height > MAX_PIXELS || alpha.length != width * height) {
            throw new IllegalArgumentException("Invalid or oversized glyph coverage raster");
        }
    }

    private static void validateOrigin(double left, double top) {
        if (!Double.isFinite(left) || !Double.isFinite(top)) {
            throw new IllegalArgumentException("Raster coordinates must be finite");
        }
    }

    private static void validatePixelsPerUnit(double pixelsPerUnit) {
        if (!Double.isFinite(pixelsPerUnit) || pixelsPerUnit <= 0) {
            throw new IllegalArgumentException("Raster resolution must be finite and positive");
        }
    }
}
