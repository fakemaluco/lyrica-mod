package com.tx24.spicyplayer.network.data.providers

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.spotify.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SpicyAlternateLookupTest {
    private val upload = """{"Status":200,"Body":{"Type":"Syllable","UploadAttribution":{"Uploader":{"Username":"Fixture"}},"Content":[{"Lead":{"Syllables":[{"Text":"One ","StartTime":1,"EndTime":2},{"Text":"two","StartTime":2,"EndTime":3}]}}]}}"""
    private val request = LyricsLookupRequest("Artist", "Song", "Album", 180, "A".repeat(22))
    private fun candidate(id: String) = SpotifyTrackCandidate(id, "Song", listOf("Artist"), "Album", 180_000)
    private fun resolver(ids: List<String>) = SpotifyTrackResolver(object : SpotifyCatalogSearch {
        override suspend fun search(track: LocalTrackMetadata) = ids.map(::candidate)
    })
    private fun response(chain: Interceptor.Chain, text: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()

    @Test fun `direct release miss searches same recording copies once without repeating primary`() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val primary = request.spotifyTrackId!!; val alternate = "B".repeat(22)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val id = chain.request().url.pathSegments.last(); asked += id
            response(chain, if (id == primary) """{"Status":404}""" else upload)
        }.build()
        try {
            val result = SpicyLyricsProvider(client, Gson(), resolver(listOf(primary, alternate)), "fixture").fetch(request)
            assertTrue(result is ProviderResult.Hit)
            assertEquals(listOf(primary, alternate), asked)
            assertEquals(RemoteLyricsQuality.WORD_SYNCED, (result as ProviderResult.Hit).payload.measuredQuality())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun `ready alternate community upload never waits for a blocked primary`() = runBlocking {
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val primary = request.spotifyTrackId!!; val alternate = "B".repeat(22)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.pathSegments.last() == primary) {
                blocked.countDown(); release.await(4, TimeUnit.SECONDS)
            } else check(blocked.await(2, TimeUnit.SECONDS))
            response(chain, upload)
        }.build()
        try {
            val provider = SpicyLyricsProvider(client, Gson(), resolver(emptyList()), "fixture")
            val result = withTimeout(1500) { provider.fetch(request.copy(alternateSpotifyTrackIds = listOf(alternate))) }
            assertTrue(result is ProviderResult.Hit)
            assertEquals(1L, release.count)
        } finally { release.countDown(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
