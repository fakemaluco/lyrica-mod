package dev.lyrica.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class ArtworkImageDecoderTest {
    @Test fun supportedImagesPassThroughWithoutStartingNativeCodecs() {
        val bytes = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", out)
            out.toByteArray()
        }
        assertSame(bytes, ArtworkImageDecoder.normalize(bytes))
    }
    @Test fun emptyAndOversizedInputsAreRejectedBeforeDecoding() {
        for (bytes in listOf(ByteArray(0), ByteArray(8 * 1024 * 1024 + 1))) {
            try { ArtworkImageDecoder.normalize(bytes); fail("Invalid image accepted") }
            catch (_: IOException) { }
        }
    }
}
