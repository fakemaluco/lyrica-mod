package dev.lyrica.engine

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in diagnostic; ordinary builds/tests never depend on network availability. */
class LiveSourcesSmokeTest {
    @Test fun `live lrc red and lrclib smoke`() {
        assumeTrue(System.getenv("LYRICA_SOURCE_SMOKE") == "1")
        val key = System.getenv("SPICY_LYRICS_PUBLISHABLE_KEY").orEmpty()
        if (key.isNotBlank()) DesktopLyricsEngine(Files.createTempDirectory("lyrica-spicy-smoke").toFile(), key).use { engine ->
            val result = engine.find("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213_000,
                listOf("4PTG3Z6ehGkBFwjybzWkR8"), engine.sources().map { it.id }, emptyList(), true) {}.get(40, TimeUnit.SECONDS)
            println("LIVE_SPICY found=${result.found()} source=${result.sourceId} provider=${result.provider} quality=${result.quality}")
            println(result.diagnostics)
            org.junit.Assert.assertEquals("spicy_lyrics", result.sourceId)
        }
        DesktopLyricsEngine(Files.createTempDirectory("lyrica-source-smoke").toFile(), "").use { engine ->
            for (id in listOf("lrc_red", "lrclib")) {
                val result = engine.find("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213_000,
                    emptyList(), listOf(id)) {}.get(40, TimeUnit.SECONDS)
                println("LIVE_SOURCE $id: found=${result.found()} provider=${result.provider} quality=${result.quality}")
                println(result.diagnostics)
            }
            val result = engine.find("Underground Sound", "Joey Valence & Brae", "", 0,
                emptyList(), engine.sources().map { it.id }, emptyList()) {}.get(40, TimeUnit.SECONDS)
            println("LIVE_SOURCE Underground Sound: found=${result.found()} provider=${result.provider} quality=${result.quality}")
            println(result.diagnostics)
        }
    }
}
