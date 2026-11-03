package dev.lyrica.engine

import com.tx24.spicyplayer.translation.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class DesktopTranslationEngineTest {
    @Test fun `line positions survive blanks and cached repeats need no provider`() {
        val calls = AtomicInteger()
        val translator = object : Translator {
            override val provider = TranslationProvider.Google
            override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
                calls.incrementAndGet()
                return TranslatorResponse(request.lines.map { TranslationEntry("Tradução: $it", language = "en") }, "en")
            }
        }
        DesktopTranslationEngine(Files.createTempDirectory("translation").toFile()) { translator }.use { engine ->
            val lines = listOf("First line", "", "Second line")
            fun translate() = engine.translate(lines, "pt", "Google", "", "", "").get(5, TimeUnit.SECONDS)
            val first = translate()
            assertEquals(listOf("Tradução: First line", null, "Tradução: Second line"), first)
            assertEquals(first, translate())
            assertEquals(1, calls.get())
        }
    }

    @Test fun `cancelling a future cancels its coroutine and request`() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val translator = object : Translator {
            override val provider = TranslationProvider.Google
            override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
                started.countDown()
                try { awaitCancellation() } finally { stopped.countDown() }
            }
        }
        DesktopTranslationEngine(Files.createTempDirectory("translation-cancel").toFile()) { translator }.use { engine ->
            val request = engine.translate(listOf("A line"), "pt", "Google", "", "", "")
            assertTrue(started.await(3, TimeUnit.SECONDS))
            request.cancel(true)
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
        }
    }
}
