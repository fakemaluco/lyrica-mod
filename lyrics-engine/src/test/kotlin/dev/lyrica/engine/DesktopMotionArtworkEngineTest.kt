package dev.lyrica.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class DesktopMotionArtworkEngineTest {
    @Test fun `a direct ID without Canvas tries a confidently matched alternate edition`() {
        val alternate = "7ouMYWpwJ422jRcDASZB7P"
        val asked = mutableListOf<String>()
        DesktopMotionArtworkEngine.forTests(canvas = { asked += it; if (it == alternate) canvas else null },
            alternatives = { listOf(id, alternate, alternate) }).use { engine ->
            val answer = engine.lookup(coverOn = false)
            assertEquals(canvas, answer.canvasUrl)
            assertEquals(alternate, answer.spotifyId)
            assertEquals(listOf(id, alternate), asked)
        }
    }
    private val id = "4Dvkj6JhhA12EX05fT7y2e"
    private val canvas = "https://canvaz.scdn.co/upload/video/test.mp4"
    private val cover = "https://video-ssl.itunes.apple.com/test/playlist.m3u8"

    private fun DesktopMotionArtworkEngine.lookup(canvasOn: Boolean = true, coverOn: Boolean = true, trackId: String? = id) =
        find("As It Was", "Harry Styles", "Harry's House", 167_000, trackId, canvasOn, coverOn) {}.get(3, TimeUnit.SECONDS)

    @Test fun `disabled kinds never query their services or Spotify matching`() {
        val calls = AtomicInteger()
        DesktopMotionArtworkEngine.forTests({ calls.incrementAndGet(); canvas }, { _, _, _ -> calls.incrementAndGet(); cover },
            { calls.incrementAndGet(); id }).use { engine ->
            val answer = engine.lookup(false, false, null)
            assertNull(answer.canvasUrl)
            assertNull(answer.animatedCoverUrl)
            assertEquals(0, calls.get())
        }
    }

    @Test fun `direct Spotify ID avoids matching and finds both media types`() {
        DesktopMotionArtworkEngine.forTests({ assertEquals(id, it); canvas }, { artist, album, title ->
            assertEquals("Harry Styles", artist); assertEquals("Harry's House", album); assertEquals("As It Was", title); cover
        }, { error("must not search with a direct ID") }).use { engine ->
            val answer = engine.lookup()
            assertEquals(canvas, answer.canvasUrl)
            assertEquals(cover, answer.animatedCoverUrl)
            assertEquals(id, answer.spotifyId)
        }
    }

    @Test fun `album cover progresses independently while canvas awaits matching`() {
        val fast = CountDownLatch(1)
        DesktopMotionArtworkEngine.forTests({ delay(80); canvas }, { _, _, _ -> cover }, { delay(80); id }).use { engine ->
            val answer = engine.find("Song", "Artist", "Album", 1_000, null, true, true) {
                if (it.animatedCoverUrl == cover && it.canvasUrl == null) fast.countDown()
            }
            assertTrue(fast.await(1, TimeUnit.SECONDS))
            assertEquals(canvas, answer.get(3, TimeUnit.SECONDS).canvasUrl)
        }
    }

    @Test fun `missing or failing canvas still returns animated album cover`() {
        DesktopMotionArtworkEngine.forTests({ throw java.io.IOException("offline") }, { _, _, _ -> cover }).use { engine ->
            val answer = engine.lookup()
            assertNull(answer.canvasUrl)
            assertEquals(cover, answer.animatedCoverUrl)
        }
    }

    @Test fun `uncertain Spotify match never queries canvas but does not suppress cover`() {
        DesktopMotionArtworkEngine.forTests({ error("no match must not be substituted") }, { _, _, _ -> cover }, { null }).use { engine ->
            val answer = engine.lookup(trackId = null)
            assertNull(answer.canvasUrl)
            assertEquals(cover, answer.animatedCoverUrl)
            assertTrue(answer.diagnostics.contains("no confident Spotify match"))
        }
    }

    @Test fun `deadline returns ready cover without waiting for slow canvas`() {
        DesktopMotionArtworkEngine.forTests({ delay(10_000); canvas }, { _, _, _ -> cover }, deadlineMs = 100).use { engine ->
            val answer = engine.lookup()
            assertNull(answer.canvasUrl)
            assertEquals(cover, answer.animatedCoverUrl)
            assertTrue(answer.diagnostics.contains("deadline"))
        }
    }

    @Test fun `cancellation does not publish stale results`() {
        val started = CountDownLatch(1)
        val published = AtomicInteger()
        DesktopMotionArtworkEngine.forTests({ started.countDown(); delay(60_000); canvas }).use { engine ->
            val answer = engine.find("Song", "Artist", "Album", 1000, id, true, false) { published.incrementAndGet() }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(answer.cancel(true))
            Thread.sleep(40)
            assertEquals(0, published.get())
            assertTrue(answer.isCancelled)
        }
    }

    @Test fun `only expected HTTPS video CDN URLs leave discovery`() {
        assertTrue(DesktopMotionArtworkEngine.videoUrl(canvas + "?token=x", false))
        assertTrue(DesktopMotionArtworkEngine.videoUrl(cover, true))
        for (unsafe in listOf("http://canvaz.scdn.co/a.mp4", "https://scdn.co.evil.invalid/a.mp4", "file:///a.mp4",
            "https://user@canvaz.scdn.co/a.mp4", "https://canvaz.scdn.co:8080/a.mp4", "https://canvaz.scdn.co/a.jpg")) {
            assertFalse(unsafe, DesktopMotionArtworkEngine.videoUrl(unsafe, false))
        }
        assertFalse(DesktopMotionArtworkEngine.videoUrl(canvas, true))
        assertFalse(DesktopMotionArtworkEngine.videoUrl(cover, false))
    }
}
