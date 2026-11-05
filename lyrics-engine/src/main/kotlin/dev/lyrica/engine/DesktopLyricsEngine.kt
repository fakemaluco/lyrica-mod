package dev.lyrica.engine

import com.google.gson.Gson
import com.tx24.spicyplayer.network.data.*
import com.tx24.spicyplayer.network.data.providers.*
import com.tx24.spicyplayer.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.tx24.spicyplayer.network.data.spotify.SpotifyTrackResolver
import com.tx24.spicyplayer.network.service.LyricsService
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.helpers.DefaultHandler
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Desktop wiring of the pinned Spicy Mobile JVM core; no Android services or account required. */
class DesktopLyricsEngine private constructor(
    private val cacheDirectory: File,
    private val providers: Set<RemoteLyricsProvider>,
    private val nativeNames: (suspend (LyricsLookupRequest) -> Pair<String, String>?)?,
    private val http: OkHttpClient?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val source = RemoteLyricsSource(providers.mapTo(linkedSetOf()) { ValidatedProvider(it) }, ProviderCooldownTracker(), nativeNames = nativeNames)
    private val gson = Gson()
    private val descriptors = providers.map { it.descriptor }.sortedBy { it.defaultPriority }

    constructor(cacheDirectory: File, apiKey: String) : this(cacheDirectory, runtime(apiKey))
    private constructor(cacheDirectory: File, runtime: Runtime) :
        this(cacheDirectory, runtime.providers, runtime.nativeNames, runtime.http)

    data class Source(val id: String, val name: String, val defaultEnabled: Boolean)
    data class Result @JvmOverloads constructor(
        val ttml: String? = null,
        val lrc: String? = null,
        val plain: String? = null,
        val provider: String = "",
        val quality: String = "NONE",
        val diagnostics: String = "",
        val cached: Boolean = false,
        val writers: List<String> = emptyList(),
        val maker: String = "",
        val makerAvatar: String = "",
        val uploader: String = "",
        val uploaderAvatar: String = "",
        val sourceId: String = "",
        val wordTimingAvailable: Boolean = false,
    ) {
        fun found() = !ttml.isNullOrBlank() || !lrc.isNullOrBlank() || !plain.isNullOrBlank()
    }
    private data class Stored(
        val expires: Long, val result: Result?, val settled: Boolean,
        val selectedId: String?, val payload: RemoteLyricsPayload?, val misses: List<String>,
    )

    fun sources(): List<Source> = descriptors.map { Source(it.id, it.displayName, true) }

    /** A future can be cancelled on a song change; cancellation also cancels in-flight HTTP calls. */
    fun find(
        title: String, artist: String, album: String, durationMs: Long,
        spotifyIds: List<String>, enabledIds: List<String>, onUpdate: Consumer<Result>,
    ): CompletableFuture<Result> = find(title, artist, album, durationMs, spotifyIds, enabledIds, emptyList(), onUpdate)

    fun find(
        title: String, artist: String, album: String, durationMs: Long,
        spotifyIds: List<String>, enabledIds: List<String>, sourceOrder: List<String>, onUpdate: Consumer<Result>,
    ): CompletableFuture<Result> = find(title, artist, album, durationMs, spotifyIds, enabledIds, sourceOrder, true, onUpdate)

    fun find(
        title: String, artist: String, album: String, durationMs: Long,
        spotifyIds: List<String>, enabledIds: List<String>, sourceOrder: List<String>, preferSpicy: Boolean, onUpdate: Consumer<Result>,
    ): CompletableFuture<Result> {
        val future = CompletableFuture<Result>()
        val job = scope.launch {
            var latest = Result(diagnostics = "Looking up lyrics")
            try {
                val enabled = enabledIds.toSet()
                if (enabled.isEmpty()) {
                    future.complete(Result(diagnostics = "All lyrics sources are switched off"))
                    return@launch
                }
                val request = LyricsLookupRequest(artist, title, album, (durationMs / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    spotifyIds.firstOrNull { it.matches(Regex("[A-Za-z0-9]{22}")) },
                    spotifyIds.filter { it.matches(Regex("[A-Za-z0-9]{22}")) }.distinct().drop(1).take(3))
                val order = if (preferSpicy && "spicy_lyrics" in enabled)
                    listOf(LyricsOrigins.SPICY_COMMUNITY) + sourceOrder.filter { it != LyricsOrigins.SPICY_COMMUNITY } else sourceOrder
                val policy = policy(enabled, order)
                val cache = cacheFile(request, enabled, policy.sourceOrder, preferSpicy)
                val stored = runCatching {
                    if (cache.length() > MAX_CACHE_ENTRY_BYTES) null else gson.fromJson(cache.readText(), Stored::class.java)
                }.getOrNull()
                val known = mutableMapOf<String, ProviderResult>()
                val ready = stored?.result
                if (stored != null && stored.expires > System.currentTimeMillis() && ready?.found() == true) {
                    latest = ready.copy(cached = true)
                    if (stored.settled) { future.complete(latest); return@launch }
                    // A fallback is immediately useful, but a timeout/cooldown above it is not a
                    // definitive miss. Re-ask those sources, as Spicy Mobile does for unsettled picks.
                    onUpdate.accept(latest)
                    stored.misses.orEmpty().filter { it in enabled }.forEach { known[it] = ProviderResult.Miss }
                    val id = stored.selectedId
                    val payload = stored.payload
                    if (id != null && id in enabled && payload != null &&
                        (payload.ttmlLyrics.isNullOrBlank() || safeTtml(payload.ttmlLyrics))) known[id] = ProviderResult.Hit(payload)
                }
                var lastResolution: RemoteLyricsResolution? = null
                val ranking = SourceRanking(descriptors, policy)
                fun preferred(progress: RemoteLyricsResolution): RemoteLyricsResolution {
                    if (!preferSpicy || "spicy_lyrics" !in enabled) return progress
                    val hit = known["spicy_lyrics"] as? ProviderResult.Hit ?: return progress
                    val quality = hit.payload.measuredQuality()
                    if (quality == RemoteLyricsQuality.NONE || ranking.filtered("spicy_lyrics", hit.payload, quality) != null) return progress
                    if (quality != RemoteLyricsQuality.WORD_SYNCED && "lrc_red" in enabled) {
                        val wordSync = (known["lrc_red"] as? ProviderResult.Hit)?.payload
                        val lrcRed = descriptors.firstOrNull { it.id == "lrc_red" }
                        if (lrcRed != null && wordSync?.measuredQuality() == RemoteLyricsQuality.WORD_SYNCED
                            && ranking.filtered(lrcRed.id, wordSync, RemoteLyricsQuality.WORD_SYNCED) == null) {
                            return RemoteLyricsResolution.Found(
                                RemoteLyricsSelection(lrcRed, wordSync, RemoteLyricsQuality.WORD_SYNCED), progress.attempts)
                        }
                    }
                    val descriptor = descriptors.firstOrNull { it.id == "spicy_lyrics" } ?: return progress
                    return RemoteLyricsResolution.Found(RemoteLyricsSelection(descriptor, hit.payload, quality), progress.attempts)
                }
                val resolution = withTimeoutOrNull(LOOKUP_DEADLINE_MS) {
                    source.resolveLyrics(request, policy, known = known, onUpdate = { progress ->
                        val selection = preferred(progress)
                        lastResolution = selection
                        latest = result(selection)
                        onUpdate.accept(latest)
                    }).let(::preferred)
                }
                val finalResolution = resolution ?: lastResolution
                val result = finalResolution?.let(::result)?.let {
                    if (resolution == null) it.copy(diagnostics = it.diagnostics + "\nLookup deadline reached; higher sources will be retried")
                    else it
                } ?: latest
                val finalResult = result.copy(wordTimingAvailable = known.any { (id, reply) ->
                    val payload = (reply as? ProviderResult.Hit)?.payload ?: return@any false
                    payload.measuredQuality() == RemoteLyricsQuality.WORD_SYNCED &&
                        ranking.filtered(id, payload, RemoteLyricsQuality.WORD_SYNCED) == null
                })
                if (finalResult.found()) runCatching {
                    cacheDirectory.mkdirs()
                    val temporary = File(cacheDirectory, cache.name + ".${java.util.UUID.randomUUID()}.tmp")
                    val found = finalResolution as? RemoteLyricsResolution.Found
                    val preferredSettled = preferSpicy && found?.selection?.source?.id == "spicy_lyrics" &&
                        ("lrc_red" !in enabled || found.selection.quality == RemoteLyricsQuality.WORD_SYNCED)
                    val settled = resolution != null && (preferredSettled || settled(request, resolution))
                    val serialized = gson.toJson(Stored(System.currentTimeMillis() + CACHE_TTL_MS, finalResult, settled,
                        found?.selection?.source?.id, found?.selection?.payload,
                        finalResolution?.attempts.orEmpty().filter { it.outcome == ProviderAttemptOutcome.MISS }.map { it.sourceId }))
                    if (serialized.toByteArray().size > MAX_CACHE_ENTRY_BYTES) return@runCatching
                    temporary.writeText(serialized)
                    try {
                        java.nio.file.Files.move(temporary.toPath(), cache.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                    pruneCache()
                }
                future.complete(finalResult)
            } catch (cancelled: CancellationException) {
                future.cancel(false)
            } catch (error: Exception) {
                future.complete(latest.copy(diagnostics = latest.diagnostics + "\nLookup failed: ${error.message}"))
            }
        }
        job.invokeOnCompletion { if (!future.isDone) future.cancel(false) }
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        return future
    }

    /** Only called by the desktop client after normal sources failed to supply word timing. */
    fun requestLrcRedSync(title: String, artist: String, album: String, durationMs: Long): CompletableFuture<Result> {
        val future = CompletableFuture<Result>()
        val provider = providers.filterIsInstance<LrcRedLyricsProvider>().firstOrNull()
            ?: return CompletableFuture.completedFuture(Result(diagnostics = "lrc.red model sync unavailable"))
        val request = LyricsLookupRequest(artist, title, album,
            (durationMs / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        val job = scope.launch {
            val output = withTimeoutOrNull(75_000L) {
                when (val reply = provider.requestSync(request)) {
                    is ModelSyncResult.Failed -> Result(diagnostics = reply.message)
                    is ModelSyncResult.Synced -> {
                        val payload = reply.payload.withoutFakeTiming().withWordTimingAsTtml()
                        if (payload.ttmlLyrics?.let(::safeTtml) != true || payload.measuredQuality() != RemoteLyricsQuality.WORD_SYNCED)
                            Result(diagnostics = "lrc.red returned no valid word timing")
                        else result(RemoteLyricsResolution.Found(
                            RemoteLyricsSelection(provider.descriptor, payload, RemoteLyricsQuality.WORD_SYNCED), emptyList()))
                    }
                }
            } ?: Result(diagnostics = "lrc.red model sync timed out")
            future.complete(output)
        }
        job.invokeOnCompletion { error -> if (error != null && !future.isDone) future.cancel(false) }
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        return future
    }

    internal fun policy(enabled: Set<String>, order: List<String> = emptyList()): RemoteLyricsPolicy {
        // Upstream policies are by origin (Apple Music etc.), while Lyrica's switches are by
        // network adapter. Spell out every origin/provider pair so an off provider stays off
        // even if several origins relay its catalogue. Blends remain off.
        val ranking = SourceRanking(descriptors, RemoteLyricsPolicy())
        return RemoteLyricsPolicy(sourceOrder = order, pairs = ranking.origins.flatMap { origin ->
            origin.providerIds.map { SourcePair(origin.id, it).key to (it in enabled) }
        }.toMap())
    }

    private fun settled(request: LyricsLookupRequest, resolution: RemoteLyricsResolution): Boolean {
        val pick = (resolution as? RemoteLyricsResolution.Found)?.selection ?: return false
        val unanswered = setOf(ProviderAttemptOutcome.UNAVAILABLE, ProviderAttemptOutcome.COOLING_DOWN,
            ProviderAttemptOutcome.QUEUED, ProviderAttemptOutcome.PENDING)
        val above = resolution.attempts.takeWhile { it.sourceId != pick.source.id }
        if (above.any { it.outcome in unanswered || it.outcome == ProviderAttemptOutcome.NEEDS_MATCH && request.durationSeconds <= 0 }) return false
        // Even a lower-ranked source may still improve line/plain lyrics to word sync.
        return pick.quality == RemoteLyricsQuality.WORD_SYNCED || resolution.attempts.none { attempt ->
            attempt.outcome in unanswered && descriptors.any { it.id == attempt.sourceId && LyricsCapability.WORD_SYNC in it.capabilities }
        }
    }

    private fun cacheFile(request: LyricsLookupRequest, enabled: Set<String>, order: List<String>, preferSpicy: Boolean): File {
        val identity = listOf("v7-lrc-red-word-priority", preferSpicy.toString(), request.title.trim().lowercase(), request.artist.trim().lowercase(),
            request.album.trim().lowercase(), request.durationSeconds.toString(), request.spotifyTrackId.orEmpty(),
            request.alternateSpotifyTrackIds.joinToString(","),
            enabled.sorted().joinToString(","), order.joinToString(",")).joinToString("\u001f")
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDirectory, "$hash.json")
    }

    /** Bound the cache by age, count and bytes; never touch imports or anything outside this directory. */
    @Synchronized private fun pruneCache() {
        val now = System.currentTimeMillis()
        val files = cacheDirectory.listFiles().orEmpty().filter { it.isFile }
        files.filter { it.name.endsWith(".tmp") && now - it.lastModified() > 3_600_000 }.forEach { it.delete() }
        var bytes = 0L
        var count = 0
        files.filter { it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }.forEach { file ->
            val expired = now - file.lastModified() >= CACHE_TTL_MS
            val keep = !expired && count < MAX_CACHE_ENTRIES && bytes + file.length() <= MAX_CACHE_BYTES
            if (keep) { count++; bytes += file.length() } else file.delete()
        }
    }

    private fun result(resolution: RemoteLyricsResolution): Result {
        val diagnostics = resolution.attempts.joinToString("\n") { attempt ->
            "${descriptors.firstOrNull { it.id == attempt.sourceId }?.displayName ?: attempt.sourceId}: ${attempt.outcome}" +
                (if (attempt.quality != RemoteLyricsQuality.NONE) " (${attempt.quality})" else "") +
                attempt.message?.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()
        }
        val selected = (resolution as? RemoteLyricsResolution.Found)?.selection ?: return Result(diagnostics = diagnostics)
        val payload = selected.payload
        val credit = payload.attribution
        val origin = LyricsOrigins.originOf(LyricsOrigins.BUILT_IN, selected.source.id, credit?.originName)?.credit
        val provider = if (selected.source.id == "spicy_lyrics") "Spicy Lyrics" else origin ?: credit?.originName ?: credit?.providerName ?: selected.source.displayName
        return Result(payload.ttmlLyrics, payload.syncedLyrics, payload.plainLyrics, provider,
            selected.quality.name, diagnostics + "\nSelected: ${selected.source.displayName} (${selected.quality.name})", writers = credit?.songwriters.orEmpty(),
            maker = credit?.maker?.username.orEmpty(), makerAvatar = credit?.maker?.avatarUrl.orEmpty(),
            uploader = credit?.uploader?.username.orEmpty(), uploaderAvatar = credit?.uploader?.avatarUrl.orEmpty(), sourceId = selected.source.id)
    }

    override fun close() {
        scope.cancel()
        http?.dispatcher?.cancelAll()
        http?.connectionPool?.evictAll()
    }

    private data class Runtime(val http: OkHttpClient, val providers: Set<RemoteLyricsProvider>,
        val nativeNames: suspend (LyricsLookupRequest) -> Pair<String, String>?)

    companion object {
        data class Origin(val id: String, val name: String)
        @JvmStatic fun origins(): List<Origin> = LyricsOrigins.BUILT_IN.map { Origin(it.id, it.displayName) }

        private const val LOOKUP_DEADLINE_MS = 30_000L
        private const val CACHE_TTL_MS = 3 * 86_400_000L
        private const val MAX_HTTP_BYTES = 8L * 1024 * 1024
        private const val MAX_CACHE_ENTRY_BYTES = 16L * 1024 * 1024
        private const val MAX_CACHE_BYTES = 128L * 1024 * 1024
        private const val MAX_CACHE_ENTRIES = 500

        /** Validate before ranking, so a rejected winner cannot hide a safe lower-ranked answer. */
        private class ValidatedProvider(private val delegate: RemoteLyricsProvider) : RemoteLyricsProvider {
            override val descriptor get() = delegate.descriptor
            override suspend fun warmUp() = delegate.warmUp()
            override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
                val result = delegate.fetch(request)
                if (result !is ProviderResult.Hit) return result
                val payload = result.payload
                val safe = payload.copy(
                    ttmlLyrics = payload.ttmlLyrics?.takeIf(::safeTtml),
                    syncedLyrics = payload.syncedLyrics?.takeIf { it.toByteArray().size <= 2 * 1024 * 1024 },
                    plainLyrics = payload.plainLyrics?.takeIf { it.toByteArray().size <= 2 * 1024 * 1024 },
                )
                return if (safe.isEmpty()) ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,
                    "Lyrics exceeded format limits or contained invalid XML") else ProviderResult.Hit(safe)
            }
        }

        private fun safeTtml(xml: String): Boolean = runCatching {
            if (xml.toByteArray().size > 4 * 1024 * 1024 || xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return false
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }.parse(xml.byteInputStream())
            true
        }.getOrDefault(false)

        internal fun forTests(directory: File, providers: Set<RemoteLyricsProvider>) = DesktopLyricsEngine(directory, providers, null, null)

        private fun runtime(apiKey: String): Runtime {
            val gson = Gson()
            val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request().newBuilder().header("User-Agent", "Lyrica/0.8 (desktop lyrics)").build())
                    val body = response.body ?: return@addInterceptor response
                    if (body.contentLength() > MAX_HTTP_BYTES) { response.close(); throw IOException("Lyrics response exceeds 8 MiB") }
                    val bounded = object : ResponseBody() {
                        private val input = object : ForwardingSource(body.source()) {
                            private var received = 0L
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                val count = super.read(sink, byteCount)
                                if (count > 0) received += count
                                if (received > MAX_HTTP_BYTES) throw IOException("Lyrics response exceeds 8 MiB")
                                return count
                            }
                        }.buffer()
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = input
                    }
                    response.newBuilder().body(bounded).build()
                }.build()
            val resolver = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson))
            val genius = GeniusSongPages(client, gson)
            val lrclib = Retrofit.Builder().baseUrl(LyricsService.BASE_URL).client(client)
                .addConverterFactory(GsonConverterFactory.create(gson)).build().create(LyricsService::class.java)
            val providers = linkedSetOf<RemoteLyricsProvider>(
                SpicyLyricsProvider(client, gson, resolver, apiKey), AmllLyricsProvider(client, gson),
                UnisonLyricsProvider(client, gson), SpicyUnisonLyricsProvider(client, gson, resolver),
                RmmRevivalLyricsProvider(client, gson), LrcRedLyricsProvider(client, gson),
                KugouLyricsProvider(client, gson), QqMusicLyricsProvider(client, gson), KuwoLyricsProvider(client),
                NetEaseLyricsProvider(client, gson), LyricsSource(lrclib), LrcMuxLyricsProvider(client, gson),
                GeniusLyricsProvider(genius), YouTubeTranscriptLyricsProvider(client, gson),
            )
            val native = NativeNames(client, gson)
            return Runtime(client, providers, native::of)
        }
    }
}
