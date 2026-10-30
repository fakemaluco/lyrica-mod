package dev.lyrica.engine

import com.google.gson.Gson
import com.tx24.spicyplayer.network.canvas.SpotifyCanvasFinder
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolution
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import com.tx24.spicyplayer.network.motion.MotionCoverFinder
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * Desktop wiring for Spicy Mobile's Canvas and Apple Music animated-cover lookups.
 * Decoration stays independent from lyric availability and requires no account.
 * Videos are discovered here; this class never downloads or decodes their contents.
 */
class DesktopMotionArtworkEngine private constructor(
    private val canvasLookup: suspend (String, Boolean) -> String?,
    private val coverLookup: suspend (String, String, String, Boolean) -> String?,
    private val spotifyLookup: suspend (LocalTrackMetadata) -> List<String>,
    private val http: OkHttpClient?,
    private val deadlineMs: Long,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    constructor(cacheDirectory: File) : this(runtime(cacheDirectory))
    private constructor(runtime: Runtime) : this(runtime.canvas, runtime.cover, runtime.spotify, runtime.http, 25_000)

    data class Result(
        val canvasUrl: String? = null,
        val animatedCoverUrl: String? = null,
        val spotifyId: String? = null,
        val diagnostics: String = "",
    )

    /** Both lookups progress separately; cancelling the returned future cancels their HTTP calls. */
    fun find(
        title: String, artist: String, album: String, durationMs: Long, spotifyId: String?,
        canvasEnabled: Boolean, animatedCoverEnabled: Boolean, onUpdate: Consumer<Result>,
    ): CompletableFuture<Result> = find(title, artist, album, durationMs, spotifyId, canvasEnabled, animatedCoverEnabled, false, onUpdate)

    fun find(
        title: String, artist: String, album: String, durationMs: Long, spotifyId: String?,
        canvasEnabled: Boolean, animatedCoverEnabled: Boolean, forceRefresh: Boolean, onUpdate: Consumer<Result>,
    ): CompletableFuture<Result> {
        val future = CompletableFuture<Result>()
        val job = scope.launch {
            val gate = Any()
            var result = Result(spotifyId = spotifyId?.takeIf(::isTrackId))
            var canvasStatus = if (canvasEnabled) "Canvas: searching" else "Canvas: off"
            var coverStatus = if (animatedCoverEnabled) "Animated cover: searching" else "Animated cover: off"
            fun publish(change: (Result) -> Result) = synchronized(gate) {
                ensureActive()
                result = change(result).copy(diagnostics = "$canvasStatus\n$coverStatus")
                onUpdate.accept(result)
            }
            try {
                val completed = withTimeoutOrNull(deadlineMs) {
                    coroutineScope {
                        if (canvasEnabled) launch {
                            var chosen = result.spotifyId
                            var url = chosen?.let { runCatchingCancellation { canvasLookup(it, forceRefresh) } }?.takeIf { videoUrl(it, false) }
                            if (url == null && title.isNotBlank() && artist.isNotBlank()) {
                                val ids = runCatchingCancellation {
                                    spotifyLookup(LocalTrackMetadata(title.take(512), artist.take(512), album.take(512), durationMs.coerceAtLeast(0)))
                                }.orEmpty().filter(::isTrackId).distinct().filter { it != chosen }.take(4)
                                for (batch in ids.chunked(2)) {
                                    val answers = coroutineScope { batch.map { id -> async {
                                        id to runCatchingCancellation { canvasLookup(id, forceRefresh) }?.takeIf { videoUrl(it, false) }
                                    } }.awaitAll() }
                                    val found = answers.firstOrNull { it.second != null }
                                    if (found != null) { chosen = found.first; url = found.second; break }
                                }
                            }
                            synchronized(gate) {
                                canvasStatus = when {
                                    url != null -> "Canvas: available"
                                    chosen == null -> "Canvas: no confident Spotify match"
                                    else -> "Canvas: unavailable for matched editions"
                                }
                                publish { it.copy(canvasUrl = url, spotifyId = chosen) }
                            }
                        }
                        if (animatedCoverEnabled) launch {
                            val url = if (artist.isNotBlank() && (album.isNotBlank() || title.isNotBlank())) {
                                runCatchingCancellation { coverLookup(artist.take(512), album.take(512), title.take(512), forceRefresh) }
                                    ?.takeIf { videoUrl(it, true) }
                            } else null
                            synchronized(gate) {
                                coverStatus = if (url != null) "Animated cover: available" else "Animated cover: unavailable for this album"
                                publish { it.copy(animatedCoverUrl = url) }
                            }
                        }
                    }
                    true
                }
                ensureActive()
                synchronized(gate) {
                    result = result.copy(diagnostics = "$canvasStatus\n$coverStatus" + if (completed == null) "\nArtwork lookup deadline reached" else "")
                    future.complete(result)
                }
            } catch (cancelled: CancellationException) {
                future.cancel(false)
            } catch (error: Exception) {
                future.complete(result.copy(diagnostics = "Artwork lookup unavailable: ${error.javaClass.simpleName}"))
            }
        }
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        job.invokeOnCompletion { if (!future.isDone) future.cancel(false) }
        return future
    }

    override fun close() {
        scope.cancel()
        http?.dispatcher?.cancelAll()
        http?.connectionPool?.evictAll()
        http?.dispatcher?.executorService?.shutdown()
    }

    private data class Runtime(
        val canvas: suspend (String, Boolean) -> String?,
        val cover: suspend (String, String, String, Boolean) -> String?,
        val spotify: suspend (LocalTrackMetadata) -> List<String>,
        val http: OkHttpClient,
    )

    companion object {
        private fun isTrackId(id: String) = id.matches(Regex("[A-Za-z0-9]{22}"))

        /** Accept only the HTTPS CDN media types produced by the two upstream finders. */
        internal fun videoUrl(url: String, animatedCover: Boolean): Boolean = runCatching {
            if (url.length > 8192) return false
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return false
            val extension = if (animatedCover) ".m3u8" else ".mp4"
            uri.scheme.equals("https", true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.path.lowercase().endsWith(extension) &&
                (if (animatedCover) listOf("mzstatic.com", "apple.com") else listOf("scdn.co", "spotifycdn.com", "spotifycdn.net"))
                    .any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)

        private suspend fun <T> runCatchingCancellation(action: suspend () -> T): T? = try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        internal fun forTests(
            canvas: suspend (String) -> String? = { null },
            cover: suspend (String, String, String) -> String? = { _, _, _ -> null },
            spotify: suspend (LocalTrackMetadata) -> String? = { null },
            deadlineMs: Long = 1_000,
            alternatives: (suspend (LocalTrackMetadata) -> List<String>)? = null,
        ) = DesktopMotionArtworkEngine({ id, _ -> canvas(id) }, { a, b, t, _ -> cover(a, b, t) }, alternatives ?: { track -> listOfNotNull(spotify(track)) }, null, deadlineMs)

        private fun runtime(directory: File): Runtime {
            val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val response = chain.proceed(if (request.header("User-Agent") != null) request else request.newBuilder().header("User-Agent", "Lyrica/0.8 (desktop motion artwork)").build())
                    val body = response.body
                    val limit = 8L * 1024 * 1024
                    if (body.contentLength() > limit) { response.close(); throw IOException("Artwork metadata exceeds 8 MiB") }
                    val bounded = object : ResponseBody() {
                        private val input = object : ForwardingSource(body.source()) {
                            private var received = 0L
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                val read = super.read(sink, byteCount)
                                if (read > 0) received += read
                                if (received > limit) throw IOException("Artwork metadata exceeds 8 MiB")
                                return read
                            }
                        }.buffer()
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = input
                    }
                    response.newBuilder().body(bounded).build()
                }.build()
            val canvas = SpotifyCanvasFinder(client, File(directory, "canvas.json"))
            val cover = MotionCoverFinder(client, File(directory, "animated-covers.json"))
            val resolver = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, Gson()))
            return Runtime({ id, refresh -> if (refresh) canvas.forget(id); canvas.find(id) },
                { artist, album, title, refresh -> if (refresh) cover.forget(artist, album, title); cover.find(artist, album, title) },
                { track -> (resolver.resolve(track) as? SpotifyTrackResolution.Matched)?.let {
                    (listOf(it.track) + it.alternates).map { match -> match.candidate.id }.distinct()
                }.orEmpty() }, client)
        }
    }
}
