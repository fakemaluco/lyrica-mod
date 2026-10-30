package dev.lyrica.engine

import com.tx24.spicyplayer.network.data.spotify.LocalTrackMetadata
import com.tx24.spicyplayer.network.data.spotify.SharedSpotify
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolution
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*

/** Optional artist headers from the same cached track-extras lookup used by Spicy Mobile.
 * No lookup happens until Artist Image is selected. Missing photos leave the cover visible. */
class DesktopArtistArtworkEngine(cacheDirectory: File) : AutoCloseable {
    private val extras = SharedSpotify.extras(cacheDirectory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun find(title: String, artist: String, album: String, durationMs: Long, spotifyId: String?): CompletableFuture<String?> {
        val future = CompletableFuture<String?>()
        val job = scope.launch {
            try {
                val url = withTimeoutOrNull(20_000) {
                    val track = LocalTrackMetadata(title.take(512), artist.take(512), album.take(512), durationMs.coerceAtLeast(0))
                    val id = spotifyId?.takeIf(::validId)
                        ?: (SharedSpotify.resolver.resolve(track) as? SpotifyTrackResolution.Matched)?.track?.candidate?.id
                        ?: return@withTimeoutOrNull null
                    val artistId = extras.details(id)?.artistIds?.firstOrNull(::validId) ?: return@withTimeoutOrNull null
                    extras.artistHeaderUrl(artistId, id)?.takeIf(::validPhoto)
                }
                future.complete(url)
            } catch (cancelled: CancellationException) { future.cancel(false) }
              catch (_: Exception) { future.complete(null) }
        }
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        return future
    }
    override fun close() { scope.cancel() }
    companion object {
        private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9]{22}"))
        internal fun validPhoto(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 8192 && uri.scheme == "https" && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.host == "i.scdn.co" && uri.path.startsWith("/image/")
        }.getOrDefault(false)
    }
}
