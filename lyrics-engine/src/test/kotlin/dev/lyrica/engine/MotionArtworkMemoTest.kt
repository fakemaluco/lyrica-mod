package dev.lyrica.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tx24.spicyplayer.network.canvas.SpotifyCanvasFinder
import com.tx24.spicyplayer.network.motion.MotionCoverFinder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MotionArtworkMemoTest {
    @Test fun `a catalogue match without video still tries web search and accepts signed ambient URLs`() = runBlocking {
        val seen = mutableListOf<String>()
        val finder = MotionCoverFinder(client { url ->
            seen += url
            when {
                url.contains("/search?") && url.contains("itunes") -> coverResponse(url)
                url.contains("music.apple.com/us/search") -> 200 to """<a href="https://music.apple.com/us/album/album/456">Album</a>"""
                url.contains("/lookup?") -> 200 to """{"results":[{"wrapperType":"collection","collectionViewUrl":"https://music.apple.com/us/album/album/456","collectionName":"Album","artistName":"Artist"}]}"""
                url.endsWith("/456") -> 200 to """<AMP-AMBIENT-VIDEO src='$hls?token=x&amp;v=1'></AMP-AMBIENT-VIDEO>"""
                else -> 200 to "<html>Static album edition</html>"
            }
        }, null)
        assertEquals("$hls?token=x&v=1", finder.find("Artist", "Album", "Song"))
        assertTrue(seen.any { it.contains("music.apple.com/us/search") })
    }

    @Test fun `another storefront can provide an animation absent from the first`() = runBlocking {
        val finder = MotionCoverFinder(client { url ->
            when {
                url.contains("country=br") && url.contains("itunes.apple.com/search") -> 200 to """{"results":[{"wrapperType":"collection","collectionViewUrl":"https://music.apple.com/br/album/album/456","collectionName":"Album","artistName":"Artist"}]}"""
                url.endsWith("/456") -> 200 to """<amp-ambient-video src="$hls"></amp-ambient-video>"""
                url.contains("itunes.apple.com/search") -> coverResponse(url)
                else -> 200 to "<html>No animation in this edition</html>"
            }
        }, null)
        assertEquals(hls, finder.find("Artist", "Album", "Song"))
    }

    @Test fun `legacy Canvas misses are not trusted after the lookup repair`() = runBlocking {
        val file = File(Files.createTempDirectory("canvas-legacy-miss").toFile(), "memo.json")
        file.writeText("""{"$id":{"url":"","at":${System.currentTimeMillis()}}}""")
        val finder = SpotifyCanvasFinder(client { 200 to """{"data":{"$id":{"url":"$mp4"}}}""" }, file)
        assertEquals(mp4, finder.find(id))
    }

    @Test fun `an embedded motion artwork URL may contain escaped slashes and query parameters`() {
        val html = """{"motionArtwork":{"video":"https:\/\/video-ssl.itunes.apple.com\/album\/loop.m3u8?x=1\u0026v=2"}}"""
        assertEquals("$hls?x=1&v=2", MotionCoverFinder.ambientVideo(html))
    }
    private val id = "4Dvkj6JhhA12EX05fT7y2e"
    private val mp4 = "https://canvaz.scdn.co/video/loop.mp4"
    private val hls = "https://video-ssl.itunes.apple.com/album/loop.m3u8"

    private fun client(answer: (String) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = answer(chain.request().url.toString())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toResponseBody()).build()
    }.build()

    private fun coverResponse(url: String): Pair<Int, String> = if (url.startsWith("https://itunes.apple.com/search")) {
        200 to """{"results":[{"wrapperType":"collection","collectionViewUrl":"https://music.apple.com/us/album/album/123","collectionName":"Album","artistName":"Artist"}]}"""
    } else 200 to """<amp-ambient-video src="$hls"></amp-ambient-video>"""

    @Test fun `successful Canvas URL expires rather than persisting a dead CDN URL forever`() = runBlocking {
        var clock = 1L
        var calls = 0
        val finder = SpotifyCanvasFinder(client { calls++; 200 to """{"data":{"$id":{"url":"$mp4"}}}""" }, null, { clock }, retryDelayMs = 0)
        assertEquals(mp4, finder.find(id))
        assertEquals(mp4, finder.find(id))
        assertEquals(1, calls)
        clock += 86_400_000
        assertEquals(mp4, finder.find(id))
        assertEquals(2, calls)
    }

    @Test fun `album cover URLs expire and lookup preserves album matching`() = runBlocking {
        var clock = 1L
        var calls = 0
        val finder = MotionCoverFinder(client { calls++; coverResponse(it) }, null, { clock })
        assertEquals(hls, finder.find("Artist", "Album", "Song"))
        assertEquals(hls, finder.find("Artist", "Album", "Song"))
        assertEquals(2, calls)
        clock += 86_400_000
        assertEquals(hls, finder.find("Artist", "Album", "Song"))
        assertEquals(4, calls)
    }

    @Test fun `failed album search is not cached as a real missing album`() = runBlocking {
        var offline = true
        val calls = AtomicInteger()
        val finder = MotionCoverFinder(client { url ->
            calls.incrementAndGet()
            if (offline) throw java.io.IOException("offline") else coverResponse(url)
        }, null)
        assertNull(finder.find("Artist", "Album", "Song"))
        offline = false
        assertEquals(hls, finder.find("Artist", "Album", "Song"))
        assertTrue(calls.get() >= 3)
    }

    @Test fun `both persisted lookup memos stay bounded after loading an old oversized catalogue`() = runBlocking {
        for (canvas in listOf(true, false)) {
            val file = File(Files.createTempDirectory("motion-memo-bounds").toFile(), "memo.json")
            val now = System.currentTimeMillis()
            val json = JsonObject()
            repeat(600) { i -> json.add("key-$i", JsonObject().apply { addProperty("url", ""); addProperty("at", now - i) }) }
            file.writeText(json.toString())
            if (canvas) SpotifyCanvasFinder(client { 200 to """{"data":{"$id":{"url":"$mp4"}}}""" }, file).find(id)
            else MotionCoverFinder(client(::coverResponse), file).find("Artist", "Album", "Song")
            assertTrue(JsonParser.parseString(file.readText()).asJsonObject.size() <= 512)
        }
    }
}
