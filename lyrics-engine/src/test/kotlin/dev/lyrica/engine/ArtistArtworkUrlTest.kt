package dev.lyrica.engine

import org.junit.Test
import org.junit.Assert.*

class ArtistArtworkUrlTest {
    @Test fun onlyTheArtistImageCdnIsAccepted() {
        assertTrue(DesktopArtistArtworkEngine.validPhoto("https://i.scdn.co/image/header-photo"))
        for (url in listOf("http://i.scdn.co/image/header", "https://i.scdn.co.evil.test/image/a", "https://user@i.scdn.co/image/a", "https://i.scdn.co:8080/image/a", "https://i.scdn.co/other/a"))
            assertFalse(url, DesktopArtistArtworkEngine.validPhoto(url))
    }
}
