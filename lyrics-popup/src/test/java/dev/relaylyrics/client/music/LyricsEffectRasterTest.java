package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LyricsEffectRasterTest {
    @Test
    void glyphHolesAndWhitespaceAreNotFilledByTheirBounds() {
        byte[] ring = new byte[13 * 13];
        for (int i = 1; i < 12; i++) {
            ring[13 + i] = ring[11 * 13 + i] = (byte) 255;
            ring[i * 13 + 1] = ring[i * 13 + 11] = (byte) 255;
        }
        var source = LyricsEffectRaster.compose(List.of(
                new LyricsEffectRaster.Quad(0, 0, 13, 13, 13, 13, ring),
                new LyricsEffectRaster.Quad(20, 0, 21, 1, 1, 1, new byte[]{(byte) 255})), 1);
        assertThat(at(source, 6, 6)).isZero();
        assertThat(at(source, 16, 6)).isZero();
        assertThat(at(source, 1, 6)).isEqualTo(255);
        var blurred = LyricsEffectRaster.blur(source, 1);
        assertThat(at(blurred, 6, 6)).isZero();
        assertThat(at(blurred, 16, 6)).isZero();
        assertThat(blurred.alpha()[0]).isZero();
    }

    @Test
    void bilinearSamplingRetainsAntialiasedCoverageAndFontBearings() {
        var mask = LyricsEffectRaster.compose(List.of(new LyricsEffectRaster.Quad(
                -2.25, 3.5, -.25, 4.5, 2, 1, new byte[]{0, (byte) 255})), 2);
        assertThat(mask.left()).isEqualTo(-2.25);
        assertThat(mask.top()).isEqualTo(3.5);
        assertThat(mask.width()).isEqualTo(4);
        assertThat(mask.height()).isEqualTo(2);
        assertThat(unsigned(mask.alpha(), 0, 4)).containsExactly(0, 64, 191, 255);
        assertThat(unsigned(mask.alpha(), 4, 4)).containsExactly(0, 64, 191, 255);
    }

    @Test
    void overlappingGlyphsUseSourceOverAndKeepEmptySpaceClear() {
        var translucent = new LyricsEffectRaster.Quad(0, 0, 1, 1, 1, 1, new byte[]{(byte) 128});
        var distant = new LyricsEffectRaster.Quad(4, 0, 5, 1, 1, 1, new byte[]{(byte) 255});
        var mask = LyricsEffectRaster.compose(List.of(translucent, translucent, distant), 1);
        assertThat(unsigned(mask.alpha(), 0, 5)).containsExactly(192, 0, 0, 0, 255);
        var reverse = LyricsEffectRaster.compose(List.of(distant, translucent, translucent), 1);
        assertThat(reverse.alpha()).containsExactly(mask.alpha());
    }

    @Test
    void cssRadiusUsesHalfRadiusSigmaWithSymmetricPaddingAndStableOffsets() {
        var source = new LyricsEffectRaster.Mask(3, 2, 10.25, -4.5, 2,
                new byte[]{0, (byte) 255, 0, 0, 0, 0});
        var result = LyricsEffectRaster.blur(source, 2);
        // sigma = 2 GUI units / 2 * 2 pixels/unit = 2 pixels; padding = ceil(6) + 2.
        assertThat(result.width()).isEqualTo(19);
        assertThat(result.height()).isEqualTo(18);
        assertThat(result.left()).isEqualTo(6.25);
        assertThat(result.top()).isEqualTo(-8.5);
        assertThat(result.pixelsPerUnit()).isEqualTo(2);
        assertThat(sum(result.alpha())).isEqualTo(sum(source.alpha()));
        assertThat(result.alpha()[0]).isZero();
        assertThat(result.alpha()[result.alpha().length - 1]).isZero();
        int center = 8 * result.width() + 9;
        assertThat(result.alpha()[center] & 255).isBetween(9, 11);
        assertThat(result.alpha()[center + 1] & 255).isLessThanOrEqualTo(result.alpha()[center] & 255);
    }

    @Test
    void blurPreservesEvenSubByteAntialiasMassAndDoesNotChangeSource() {
        byte[] alpha = {1, 4, 32, (byte) 128, (byte) 255};
        var source = new LyricsEffectRaster.Mask(5, 1, 0, 0, 1, alpha);
        var blurred = LyricsEffectRaster.blur(source, 3);
        assertThat(sum(blurred.alpha())).isEqualTo(420);
        assertThat(source.alpha()).containsExactly(alpha);
        assertThat(LyricsEffectRaster.blur(source, 0)).isSameAs(source);
        alpha[0] = 0;
        assertThat(source.alpha()[0]).isEqualTo((byte) 1);
    }

    @Test
    void transparentGlyphsStayCompletelyTransparent() {
        var source = new LyricsEffectRaster.Mask(7, 4, -1, 2, 3, new byte[28]);
        assertThat(sum(LyricsEffectRaster.blur(source, 4).alpha())).isZero();
        var empty = LyricsEffectRaster.compose(List.of(), 2);
        assertThat(empty.width()).isEqualTo(1);
        assertThat(sum(empty.alpha())).isZero();
    }

    @Test
    void oversizedCompositionsAndBlurLowerResolutionInsteadOfCropping() {
        var composition = LyricsEffectRaster.compose(List.of(new LyricsEffectRaster.Quad(
                10, 20, 2058, 532, 1, 1, new byte[]{(byte) 255})), 2);
        assertThat(composition.width()).isEqualTo(1024);
        assertThat(composition.height()).isEqualTo(256);
        assertThat(composition.pixelsPerUnit()).isEqualTo(.5);
        var blurred = LyricsEffectRaster.blur(composition, 30);
        assertThat(blurred.width()).isLessThanOrEqualTo(LyricsEffectRaster.MAX_SIDE);
        assertThat(blurred.height()).isLessThanOrEqualTo(LyricsEffectRaster.MAX_SIDE);
        assertThat(blurred.pixelsPerUnit()).isLessThan(composition.pixelsPerUnit());
        assertThat(blurred.left()).isLessThan(composition.left());
        assertThat(blurred.top()).isLessThan(composition.top());
        assertThat(blurred.left() + blurred.width() / blurred.pixelsPerUnit()).isGreaterThan(2058);
        assertThat(blurred.alpha().length).isLessThanOrEqualTo(LyricsEffectRaster.MAX_PIXELS);
    }

    @Test
    void rejectsMalformedRastersAndNonfiniteInput() {
        assertThatThrownBy(() -> new LyricsEffectRaster.Mask(2, 2, 0, 0, 1, new byte[3]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LyricsEffectRaster.Mask(1025, 1, 0, 0, 1, new byte[1025]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LyricsEffectRaster.Mask(1, 1, Double.NaN, 0, 1, new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LyricsEffectRaster.Quad(0, 0, 0, 1, 1, 1, new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LyricsEffectRaster.compose(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        var source = new LyricsEffectRaster.Mask(1, 1, 0, 0, 1, new byte[1]);
        assertThatThrownBy(() -> LyricsEffectRaster.blur(source, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LyricsEffectRaster.blur(source, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int at(LyricsEffectRaster.Mask mask, double x, double y) {
        int pixelX = (int) Math.floor((x - mask.left()) * mask.pixelsPerUnit());
        int pixelY = (int) Math.floor((y - mask.top()) * mask.pixelsPerUnit());
        return mask.alpha()[pixelY * mask.width() + pixelX] & 255;
    }

    private static int sum(byte[] alpha) {
        int sum = 0;
        for (byte sample : alpha) sum += sample & 255;
        return sum;
    }

    private static int[] unsigned(byte[] alpha, int offset, int length) {
        return Arrays.stream(Arrays.copyOfRange(toInts(alpha), offset, offset + length)).toArray();
    }

    private static int[] toInts(byte[] alpha) {
        int[] result = new int[alpha.length];
        for (int i = 0; i < alpha.length; i++) result[i] = alpha[i] & 255;
        return result;
    }
}
