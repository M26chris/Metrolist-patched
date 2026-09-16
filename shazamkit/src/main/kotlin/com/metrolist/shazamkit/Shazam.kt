package com.metrolist.shazamkit

import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.ShazamRequestJson
import com.metrolist.shazamkit.models.ShazamResponseJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Shazam music recognition with built-in rate limiting and queue management.
 */
object Shazam {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val MAX_CONCURRENT_REQUESTS = 2
    private const val MIN_REQUEST_INTERVAL_MS = 1000L
    private const val MAX_RETRIES = 3
    private const val INITIAL_RETRY_DELAY_MS = 2000L
    private const val CACHE_DURATION_MS = 300_000L
    private const val MAX_QUEUE_SIZE = 50

    private val activeRequests = AtomicInteger(0)
    private val lastRequestTimeMs = AtomicLong(0L)
    private val enqueueMutex = Mutex()
    private val rateLimitMutex = Mutex()
    private val requestQueue = ConcurrentLinkedQueue<PendingRequest>()
    private val resultCache = ConcurrentHashMap<String, CachedResult>()
    private val nextRequestId = AtomicInteger(0)

    @Volatile
    private var isProcessingQueue = false

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
            expectSuccess = false
            engine {
                requestTimeout = 30_000
            }
        }
    }

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
        "Dalvik/1.6.0 (Linux; U; Android 4.4.2; SM-T210 Build/KOT49H)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)",
    )

    private val timezones = listOf(
        "Europe/Paris", "Europe/London", "America/New_York",
        "America/Los_Angeles", "Asia/Tokyo", "Asia/Dubai",
    )

    /**
     * Recognize music from an audio signature.
     *
     * @param signature Audio signature in Shazam DejaVu URI format
     * @param sampleDurationMs Sample duration in milliseconds
     * @return Result containing recognition result or error
     */
    suspend fun recognize(signature: String, sampleDurationMs: Long): Result<RecognitionResult> {
        if (signature.isBlank()) {
            return Result.failure(Exception("Empty signature"))
        }
        if (sampleDurationMs < 500L) {
            return Result.failure(Exception("No match found"))
        }

        val cacheKey = generateCacheKey(signature)
        getCachedResult(cacheKey)?.let {
            return Result.success(it)
        }

        return enqueueRequest(signature, sampleDurationMs)
    }

    fun getPendingRequestsCount(): Int = requestQueue.size

    fun getActiveRequestsCount(): Int = activeRequests.get()

    fun clearCache() {
        resultCache.clear()
    }

    fun cancelPendingRequests() {
        while (true) {
            val pending = requestQueue.poll() ?: break
            pending.completeWith(Result.failure(Exception("Request cancelled")))
        }
    }

    fun cleanup() {
        cancelPendingRequests()
        clearCache()
        runCatching { client.close() }
    }

    private suspend fun enqueueRequest(
        signature: String,
        sampleDurationMs: Long,
    ): Result<RecognitionResult> {
        val request = enqueueMutex.withLock {
            if (requestQueue.size >= MAX_QUEUE_SIZE) {
                return Result.failure(Exception("Request queue is full. Please wait."))
            }

            val pending = PendingRequest(
                id = nextRequestId.getAndIncrement().toLong(),
                signature = signature,
                sampleDurationMs = sampleDurationMs,
            )
            requestQueue.offer(pending)

            if (!isProcessingQueue) {
                isProcessingQueue = true
                scope.launch { processQueue() }
            }
            pending
        }

        // Await outside the mutex so concurrent callers can enqueue freely
        return request.awaitResult()
    }

    private suspend fun processQueue() {
        try {
            while (true) {
                val request = requestQueue.poll() ?: break

                while (activeRequests.get() >= MAX_CONCURRENT_REQUESTS) {
                    delay(50)
                }

                activeRequests.incrementAndGet()
                scope.launch {
                    try {
                        val result = executeRequest(request.signature, request.sampleDurationMs)
                        request.completeWith(result)
                    } catch (e: Exception) {
                        request.completeWith(Result.failure(e))
                    } finally {
                        activeRequests.decrementAndGet()
                    }
                }

                enforceRateLimit()
            }
        } finally {
            // If items arrived while we were draining, restart processing
            enqueueMutex.withLock {
                if (requestQueue.isNotEmpty()) {
                    scope.launch { processQueue() }
                } else {
                    isProcessingQueue = false
                }
            }
        }
    }

    private suspend fun executeRequest(
        signature: String,
        sampleDurationMs: Long,
    ): Result<RecognitionResult> {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                enforceRateLimit()

                val result = performRecognition(signature, sampleDurationMs)

                val cacheKey = generateCacheKey(signature)
                cacheResult(cacheKey, result)

                return Result.success(result)
            } catch (e: Exception) {
                lastException = e
                val msg = e.message.orEmpty()
                val retryable = msg.contains("429") ||
                    msg.contains("Too many requests", ignoreCase = true) ||
                    msg.contains("temporarily unavailable", ignoreCase = true)

                if (retryable && attempt < MAX_RETRIES - 1) {
                    delay(calculateBackoffDelay(attempt))
                    continue
                }

                // Non-retryable (including no-match) — surface immediately
                if (!retryable) {
                    return Result.failure(e)
                }
            }
        }

        return Result.failure(lastException ?: Exception("Recognition failed after $MAX_RETRIES attempts"))
    }

    private suspend fun performRecognition(
        signature: String,
        sampleDurationMs: Long,
    ): RecognitionResult {
        // SongRec / official clients use millisecond timestamps
        val timestampMs = System.currentTimeMillis()
        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString()

        val request = ShazamRequestJson(
            geolocation = ShazamRequestJson.Geolocation(
                altitude = Random.nextDouble() * 400 + 100,
                latitude = Random.nextDouble() * 180 - 90,
                longitude = Random.nextDouble() * 360 - 180,
            ),
            signature = ShazamRequestJson.Signature(
                samplems = sampleDurationMs,
                timestamp = timestampMs,
                uri = signature,
            ),
            timestamp = timestampMs,
            timezone = timezones.random(),
        )

        val response = client.post(
            "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2"
        ) {
            parameter("sync", "true")
            parameter("webv3", "true")
            parameter("sampling", "true")
            parameter("connected", "")
            parameter("shazamapiversion", "v3")
            parameter("sharehub", "true")
            parameter("video", "v3")
            header("User-Agent", userAgents.random())
            header("Content-Language", "en_US")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            when (val statusCode = response.status.value) {
                429 -> throw Exception("Too many requests")
                404 -> throw Exception("No match found")
                in 500..599 -> throw Exception("Shazam service temporarily unavailable")
                else -> throw Exception("Recognition failed (error $statusCode)")
            }
        }

        val shazamResponse = response.body<ShazamResponseJson>()
        return shazamResponse.toRecognitionResult()
            ?: throw Exception("No match found")
    }

    private suspend fun enforceRateLimit() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val last = lastRequestTimeMs.get()
            val elapsed = now - last
            if (last > 0L && elapsed < MIN_REQUEST_INTERVAL_MS) {
                delay(MIN_REQUEST_INTERVAL_MS - elapsed)
            }
            lastRequestTimeMs.set(System.currentTimeMillis())
        }
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        return INITIAL_RETRY_DELAY_MS * (1L shl attempt)
    }

    private fun generateCacheKey(signature: String): String {
        // Prefer a stable hash over String.hashCode() collisions
        var h = 1125899906842597L
        for (c in signature) {
            h = 31 * h + c.code
        }
        return h.toString()
    }

    private fun getCachedResult(key: String): RecognitionResult? {
        val cached = resultCache[key] ?: return null
        if (System.currentTimeMillis() - cached.timestamp > CACHE_DURATION_MS) {
            resultCache.remove(key)
            return null
        }
        return cached.result
    }

    private fun cacheResult(key: String, result: RecognitionResult) {
        resultCache[key] = CachedResult(
            timestamp = System.currentTimeMillis(),
            result = result,
        )
        cleanupCache()
    }

    private fun cleanupCache() {
        if (resultCache.size < 100) return
        val now = System.currentTimeMillis()
        resultCache.entries.removeIf { now - it.value.timestamp > CACHE_DURATION_MS }
    }

    private fun ShazamResponseJson.toRecognitionResult(): RecognitionResult? {
        val track = this.track ?: return null

        val songSection = track.sections?.find { it?.type == "SONG" }
        val metadata = songSection?.metadata
        val album = metadata?.find { it?.title == "Album" }?.text
        val label = metadata?.find { it?.title == "Label" }?.text
        val releaseDate = metadata?.find { it?.title == "Released" }?.text

        val lyricsSection = track.sections?.find { it?.type == "LYRICS" }
        val lyrics = lyricsSection?.text

        val appleAction = track.hub?.options?.firstOrNull {
            it?.providername?.contains("apple", ignoreCase = true) == true
        }?.actions?.firstOrNull()

        val spotifyProvider = track.hub?.providers?.find {
            it?.caption?.contains("spotify", ignoreCase = true) == true
        }

        val youtubeAction = track.hub?.options?.find {
            it?.type?.contains("video", ignoreCase = true) == true
        }?.actions?.firstOrNull()

        val youtubeVideoId = youtubeAction?.uri?.let { uri ->
            uri.substringAfterLast("v=", "").takeIf { it.isNotEmpty() }
                ?: uri.substringAfterLast("/", "").takeIf { it.isNotEmpty() && it.length == 11 }
        }

        return RecognitionResult(
            trackId = track.key ?: tagid ?: "",
            title = track.title ?: "",
            artist = track.subtitle ?: "",
            album = album,
            coverArtUrl = track.images?.coverart,
            coverArtHqUrl = track.images?.coverarthq,
            genre = track.genres?.primary,
            releaseDate = releaseDate,
            label = label,
            lyrics = lyrics,
            shazamUrl = track.url,
            appleMusicUrl = appleAction?.uri,
            spotifyUrl = spotifyProvider?.actions?.firstOrNull()?.uri,
            isrc = track.isrc,
            youtubeVideoId = youtubeVideoId,
        )
    }

    private class PendingRequest(
        val id: Long,
        val signature: String,
        val sampleDurationMs: Long,
    ) {
        private val deferred = CompletableDeferred<Result<RecognitionResult>>()

        suspend fun awaitResult(): Result<RecognitionResult> = deferred.await()

        fun completeWith(result: Result<RecognitionResult>) {
            deferred.complete(result)
        }
    }

    private data class CachedResult(
        val timestamp: Long,
        val result: RecognitionResult,
    )
}
