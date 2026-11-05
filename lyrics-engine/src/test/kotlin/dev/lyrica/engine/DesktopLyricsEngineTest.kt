package dev.lyrica.engine

import com.tx24.spicyplayer.network.data.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class DesktopLyricsEngineTest {
    @Test fun `Spicy wins over a faster word fallback and the preference is isolated in the cache`() {
        val spicy = Fake("spicy_lyrics") {
            delay(450)
            ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = "[00:01.00]Spicy line\n[00:03.00]Next",
                attribution = LyricsAttribution(providerName = "Spicy Lyrics", originName = "Apple Music")))
        }
        val fallback = Fake("kugou") { ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = """<tt><body><div>
            <p begin="1s" end="3s"><span begin="1s" end="2s">Fast </span><span begin="2s" end="3s">fallback</span></p>
            </div></body></tt>""")) }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-spicy-priority").toFile(), linkedSetOf(spicy, fallback)).use { engine ->
            val enabled = listOf("spicy_lyrics", "kugou")
            val order = listOf("kugou", "apple_music", "spicy_community")
            val preferred = engine.find("Song", "Artist", "", 180_000, emptyList(), enabled, order, true) {}.get(5, TimeUnit.SECONDS)
            assertEquals("spicy_lyrics", preferred.sourceId)
            assertEquals("Spicy Lyrics", preferred.provider)
            assertEquals("LINE_SYNCED", preferred.quality)
            assertTrue(engine.find("Song", "Artist", "", 180_000, emptyList(), enabled, order, true) {}.get(5, TimeUnit.SECONDS).cached)
            val quality = engine.find("Song", "Artist", "", 180_000, emptyList(), enabled, order, false) {}.get(5, TimeUnit.SECONDS)
            assertEquals("kugou", quality.sourceId)
            assertEquals("WORD_SYNCED", quality.quality)
            assertFalse(quality.cached)
        }
    }

    @Test fun `LRC red word timing wins over Spicy lines or plain text while Spicy word timing keeps priority`() {
        val word = RemoteLyricsPayload(ttmlLyrics = """<tt><body><div>
            <p begin="1s" end="3s"><span begin="1s" end="2s">Hello </span><span begin="2s" end="3s">world</span></p>
            </div></body></tt>""")
        for ((spicyPayload, expected) in listOf(
            RemoteLyricsPayload(syncedLyrics = "[00:01.00]Hello world") to "lrc_red",
            RemoteLyricsPayload(plainLyrics = "Hello world") to "lrc_red",
            word to "spicy_lyrics",
        )) {
            val spicy = Fake("spicy_lyrics") { ProviderResult.Hit(spicyPayload) }
            val lrcRed = Fake("lrc_red") { delay(100); ProviderResult.Hit(word) }
            DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-lrc-red-priority").toFile(), linkedSetOf(spicy, lrcRed)).use { engine ->
                val enabled = listOf("spicy_lyrics", "lrc_red")
                val result = engine.find("Song", "Artist", "", 180_000, emptyList(), enabled) {}.get(5, TimeUnit.SECONDS)
                assertEquals(expected, result.sourceId)
                assertEquals("WORD_SYNCED", result.quality)
            }
        }
    }

    @Test fun `unavailable or disabled Spicy never hides a usable fallback`() {
        val spicy = Fake("spicy_lyrics") { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, "temporary") }
        val fallback = Fake("lrclib") { hit("Fallback") }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-spicy-fallback").toFile(), linkedSetOf(spicy, fallback)).use { engine ->
            assertEquals("lrclib", engine.lookup(listOf("spicy_lyrics", "lrclib")).sourceId)
            assertEquals("lrclib", engine.lookup(listOf("lrclib")).sourceId)
            assertEquals(1, spicy.calls.get())
        }
    }
    @Test fun `validated alternate IDs reach providers and isolate settled cache entries`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<LyricsLookupRequest>()
        val provider = object : RemoteLyricsProvider {
            override val descriptor = LyricsSourceDescriptor("spicy_lyrics", "Spicy", 10, setOf(LyricsCapability.LINE_SYNC))
            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                seen += request
                return hit("Found")
            }
        }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-id-copies").toFile(), setOf(provider)).use { engine ->
            val primary = "A".repeat(22); val alternate = "B".repeat(22)
            engine.find("Song", "Artist", "Album", 180_000, listOf(primary), listOf("spicy_lyrics")) {}.get(5, TimeUnit.SECONDS)
            val fresh = engine.find("Song", "Artist", "Album", 180_000, listOf("invalid", primary, alternate, alternate), listOf("spicy_lyrics")) {}.get(5, TimeUnit.SECONDS)
            assertFalse(fresh.cached)
            assertEquals(primary, seen.last().spotifyTrackId)
            assertEquals(listOf(alternate), seen.last().alternateSpotifyTrackIds)
            assertTrue(engine.find("Song", "Artist", "Album", 180_000, listOf(primary, alternate), listOf("spicy_lyrics")) {}.get(5, TimeUnit.SECONDS).cached)
        }
    }

    private class Fake(val id: String, val answer: suspend () -> ProviderResult) : RemoteLyricsProvider {
        val calls = AtomicInteger()
        override val descriptor = LyricsSourceDescriptor(id, id, 10, setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT))
        override suspend fun fetch(request: LyricsLookupRequest): ProviderResult { calls.incrementAndGet(); return answer() }
    }
    private fun hit(text: String) = ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = "[00:01.00]$text\n[00:03.00]Second line"))
    private fun DesktopLyricsEngine.lookup(ids: List<String>) = find("Song", "Artist", "Album", 180_000, emptyList(), ids) {}.get(5, TimeUnit.SECONDS)

    @Test fun `malformed preferred lyrics cannot stop a usable fallback`() {
        val broken = Fake("broken") { ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = "<broken>")) }
        val good = Fake("good") { hit("Good") }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-engine").toFile(), linkedSetOf(broken, good)).use { engine ->
            val result = engine.lookup(listOf("broken", "good"))
            assertEquals("good", result.provider)
            assertEquals("LINE_SYNCED", result.quality)
            assertTrue(result.diagnostics.contains("UNAVAILABLE"))
        }
    }

    @Test fun `unsafe remote ttml is discarded before provider ranking`() {
        val unsafe = Fake("unsafe") { ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics =
            "<!DOCTYPE tt [<!ENTITY leak SYSTEM 'file:///does-not-exist'>]><tt><body><p begin='1s' end='3s'>&leak;</p></body></tt>")) }
        val good = Fake("good") { hit("Safe source") }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-engine").toFile(), linkedSetOf(unsafe, good)).use { engine ->
            assertEquals("good", engine.lookup(listOf("unsafe", "good")).provider)
        }
    }

    @Test fun `successful cache writes prune old entries and cap entry count`() {
        val directory = Files.createTempDirectory("lyrica-cache-limit").toFile()
        val expired = java.io.File(directory, "expired.json").apply {
            writeText("{}")
            setLastModified(System.currentTimeMillis() - 4 * 86_400_000L)
        }
        repeat(502) { java.io.File(directory, "old-$it.json").writeText("{}") }
        val unrelated = java.io.File(directory, "keep.txt").apply { writeText("unrelated") }
        val good = Fake("good") { hit("Safe source") }
        DesktopLyricsEngine.forTests(directory, setOf(good)).use { engine -> engine.lookup(listOf("good")) }
        assertFalse(expired.exists())
        assertEquals(500, directory.listFiles()!!.count { it.name.endsWith(".json") })
        assertTrue(unrelated.exists())
    }

    @Test fun `cache policy never reuses a disabled source`() {
        val first = Fake("first") { hit("First") }
        val second = Fake("second") { hit("Second") }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-engine").toFile(), linkedSetOf(first, second)).use { engine ->
            assertEquals("first", engine.lookup(listOf("first")).provider)
            assertTrue(engine.lookup(listOf("first")).cached)
            assertEquals(1, first.calls.get())
            assertEquals("second", engine.lookup(listOf("second")).provider)
            assertEquals(1, first.calls.get())
            assertEquals(1, second.calls.get())
        }
    }

    @Test fun `disabled relay is never asked through another origin`() {
        val relay = Fake("lrcmux") { hit("Relay") }
        val genius = Fake("genius") { ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = "A plain lyric")) }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-engine").toFile(), linkedSetOf(relay, genius)).use { engine ->
            val result = engine.lookup(listOf("genius"))
            assertEquals("Genius", result.provider)
            assertEquals(0, relay.calls.get())
            assertEquals("PLAIN", result.quality)
        }
    }

    @Test fun `better timing replaces fast line sync without racing priority`() {
        val line = Fake("line") { hit("Fast") }
        val word = Fake("word") {
            delay(50)
            ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s"><span begin="1s" end="2s">Hello </span><span begin="2s" end="3s">world</span></p></div></body></tt>"""))
        }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-engine").toFile(), linkedSetOf(line, word)).use { engine ->
            val progress = mutableListOf<String>()
            val result = engine.find("Song", "Artist", "", 180_000, emptyList(), listOf("line", "word")) { progress += it.quality }.get(5, TimeUnit.SECONDS)
            assertEquals("WORD_SYNCED", result.quality)
            assertEquals("word", result.provider)
            assertTrue(progress.contains("LINE_SYNCED"))
        }
    }

    @Test fun `close cancels a pending lookup instead of completing stale lyrics`() {
        val started = CountDownLatch(1)
        val slow = Fake("slow") { started.countDown(); delay(60_000); hit("Late") }
        val engine = DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-engine").toFile(), setOf(slow))
        val future = engine.find("Song", "Artist", "", 180_000, emptyList(), listOf("slow")) {}
        assertTrue(started.await(3, TimeUnit.SECONDS))
        engine.close()
        repeat(100) { if (future.isCancelled) return; Thread.sleep(10) }
        assertTrue(future.isCancelled)
    }

    @Test fun `transient higher source is retried while cached fallback stays immediately available`() {
        var recover = false
        val preferred = Fake("preferred") {
            if (recover) hit("Preferred") else ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, "temporary")
        }
        val fallback = Fake("fallback") { hit("Fallback") }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-priority-cache").toFile(), linkedSetOf(preferred, fallback)).use { engine ->
            val order = listOf("preferred", "fallback")
            val first = engine.find("Song", "Artist", "", 180_000, emptyList(), order, order) {}.get(5, TimeUnit.SECONDS)
            assertEquals("fallback", first.provider)
            recover = true
            val cachedUpdates = mutableListOf<DesktopLyricsEngine.Result>()
            val next = engine.find("Song", "Artist", "", 180_000, emptyList(), order, order) { cachedUpdates += it }.get(5, TimeUnit.SECONDS)
            assertEquals("preferred", next.provider)
            assertTrue(cachedUpdates.any { it.cached && it.provider == "fallback" })
            assertEquals(2, preferred.calls.get())
            assertEquals(1, fallback.calls.get())
        }
    }

    @Test fun `equal timing follows the chosen source order and cache identity includes that order`() {
        val first = Fake("first") { delay(70); hit("First") }
        val second = Fake("second") { hit("Second") }
        DesktopLyricsEngine.forTests(Files.createTempDirectory("lyrica-priority-order").toFile(), linkedSetOf(first, second)).use { engine ->
            val enabled = listOf("first", "second")
            val a = engine.find("Song", "Artist", "", 180_000, emptyList(), enabled, listOf("first", "second")) {}.get(5, TimeUnit.SECONDS)
            val b = engine.find("Song", "Artist", "", 180_000, emptyList(), enabled, listOf("second", "first")) {}.get(5, TimeUnit.SECONDS)
            assertEquals("first", a.provider)
            assertEquals("second", b.provider)
            assertFalse(b.cached)
        }
    }
}
