package dev.lyrica.engine

import com.tx24.spicyplayer.translation.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/** Java-facing desktop wiring of Mobile's batching, alignment, rate limits and disk cache. */
class DesktopTranslationEngine @JvmOverloads constructor(directory: File, private val translatorFactory: ((TranslationProvider) -> Translator)? = null) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
    private val cache = DiskTranslationCache(directory)
    private val engine = TranslationEngine(SongLanguageDetector { null }, cache)

    fun translate(lines: List<String>, target: String, providerName: String, key: String,
                  address: String, model: String): CompletableFuture<List<String?>> {
        val future = CompletableFuture<List<String?>>()
        val job = scope.launch {
            try {
                val provider = TranslationProvider.stored(providerName)
                val translator = translatorFactory?.invoke(provider) ?: when (provider) {
                    TranslationProvider.Google -> GoogleTranslator(http)
                    TranslationProvider.DeepL -> {
                        require(DeepLKey.valid(key)) { "Invalid DeepL key" }
                        DeepLTranslator(http, key)
                    }
                    TranslationProvider.Gemini -> GeminiTranslator(http, key, model)
                    TranslationProvider.OpenAi -> OpenAiTranslator(http, address, key, model)
                    else -> error("Unsupported desktop provider")
                }
                val document = TranslationDocument("", "lyrica", lines.mapIndexed { i, text ->
                    OriginalLine(i, LyricAddress(i), text, !isTranslationContent(text))
                })
                val prefs = TranslationPreferences(targetLanguage = target, provider = provider,
                    humanTranslations = false, aiVariant = "$address $model")
                when (val outcome = engine.translate(document, prefs, null, translator)) {
                    is TranslationOutcome.Translated -> future.complete(outcome.result.texts)
                    is TranslationOutcome.Skipped -> future.complete(List(lines.size) { null })
                }
            } catch (cancelled: CancellationException) { future.cancel(false) }
            catch (error: Exception) { future.completeExceptionally(error) }
        }
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        job.invokeOnCompletion { if (!future.isDone) future.cancel(false) }
        return future
    }

    override fun close() {
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
