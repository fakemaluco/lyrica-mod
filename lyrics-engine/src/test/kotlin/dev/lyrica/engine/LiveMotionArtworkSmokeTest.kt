package dev.lyrica.engine

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in network diagnostic; ordinary builds never depend on a public music catalogue. */
class LiveMotionArtworkSmokeTest {
    @Test fun `real Canvas and album animation discovery`() {
        assumeTrue(System.getenv("LYRICA_MOTION_SMOKE") == "1")
        DesktopMotionArtworkEngine(Files.createTempDirectory("lyrica-motion-smoke").toFile()).use { engine ->
            val first = engine.find("As It Was", "Harry Styles", "Harry's House", 167_000,
                "4Dvkj6JhhA12EX05fT7y2e", true, true) {}.get(30, TimeUnit.SECONDS)
            println("LIVE_MOTION canvas=${first.canvasUrl != null}; cover=${first.animatedCoverUrl != null}\n${first.diagnostics}")
            assertNotNull("Known Spotify Canvas did not resolve", first.canvasUrl)
            var cover = first.animatedCoverUrl
            if (cover == null) {
                val second = engine.find("The Emptiness Machine", "Linkin Park", "From Zero", 190_000,
                    null, false, true) {}.get(30, TimeUnit.SECONDS)
                println("LIVE_MOTION cover fallback=${second.animatedCoverUrl != null}\n${second.diagnostics}")
                cover = second.animatedCoverUrl
            }
            assertNotNull("Known animated album did not resolve", cover)
            val output = java.io.File(System.getProperty("lyrica.motionSmokeOutput", "../build/motion-live-urls.json"))
            output.parentFile?.mkdirs()
            output.writeText(com.google.gson.Gson().toJson(mapOf("canvas" to first.canvasUrl, "animatedCover" to cover)))
            println("LIVE_MOTION confirmed hosts: Canvas=${java.net.URI(first.canvasUrl!!).host}; cover=${java.net.URI(cover!!).host}")
        }
    }
}
