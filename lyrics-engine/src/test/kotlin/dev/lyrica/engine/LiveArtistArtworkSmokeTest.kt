package dev.lyrica.engine

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optional diagnostic; a missing remote header is a supported album-cover fallback. */
class LiveArtistArtworkSmokeTest {
    @Test fun artistHeaderOrCoverFallback() {
        assumeTrue(System.getenv("LYRICA_ARTIST_SMOKE") == "1")
        DesktopArtistArtworkEngine(Files.createTempDirectory("lyrica-artist-smoke").toFile()).use { engine ->
            val url = engine.find("As It Was", "Harry Styles", "Harry's House", 167_000, "4Dvkj6JhhA12EX05fT7y2e")
                .get(25, TimeUnit.SECONDS)
            if (url == null) println("LIVE_ARTIST: remote header unavailable; album cover fallback")
            else {
                assertTrue(DesktopArtistArtworkEngine.validPhoto(url))
                java.io.File("../build/artist-header-url.txt").writeText(url)
                val connection = java.net.URI(url).toURL().openConnection()
                connection.connectTimeout = 8_000; connection.readTimeout = 8_000
                val bytes = connection.getInputStream().use { it.readNBytes(8 * 1024 * 1024 + 1) }
                val normalized = ArtworkImageDecoder.normalize(bytes)
                val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(normalized))
                assertTrue("Artist header did not decode", image != null && image.width > 0 && image.height > 0)
                println("LIVE_ARTIST: artist header resolved, downloaded and decoded (${image.width}x${image.height})")
            }
        }
    }
}
