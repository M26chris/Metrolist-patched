/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostic-only network interceptor for the OkHttpClient that actually streams audio
 * bytes (MusicService's createCacheDataSource client).
 *
 * We've spent a long time inferring why PoToken-based (WEB_REMIX/TVHTML5) stream URLs get a
 * real 403 partway through playback, from timing alone - "one-shot-per-URL" vs "short
 * absolute token expiry" are both consistent with the timestamps we've seen, and we can't
 * tell them apart without seeing what the CDN actually says on the failing request. This
 * interceptor logs, for every request:
 *   - which numbered request this is against this exact URL (1st, 2nd, ...) - directly
 *     tests the "rejected on a second request to the same URL" theory
 *   - milliseconds since the first request we saw for this URL - directly tests the
 *     "short absolute expiry from mint/first-use" theory
 *   - the Range header being requested
 *   - on any non-2xx response: every response header (previously invisible - our
 *     exception logs only ever showed the response code) and a short peek at the body,
 *     which OkHttp explicitly supports without consuming the stream the caller then reads
 *
 * Tag everything under "Metrolist_StreamDiag" so it's easy to grep out of a full log dump.
 * This is intentionally temporary instrumentation - once we have enough real evidence to
 * settle which failure mode we're actually hitting, this should come back out.
 */
object StreamDiagnosticsInterceptor : Interceptor {
    private const val TAG = "Metrolist_StreamDiag"

    // Keyed by a short, stable hash of the URL (not the full URL - these are long signed
    // URLs and dumping them whole in logs is both noisy and needlessly exposes the token).
    private val firstSeenAtMillis = ConcurrentHashMap<String, Long>()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()

    private fun keyFor(url: String): String {
        // Stable short id: host path + itag/range-independent prefix is enough to identify
        // "the same signed URL" without needing the whole thing. Signed googlevideo URLs
        // differ across re-fetches (new signature/n-param/pot each time), which is exactly
        // what we want - a genuinely new resolution gets a genuinely new key.
        return url.substringBefore("&range=").substringBefore("?").let { base ->
            "${base.takeLast(60)}#${url.hashCode()}"
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val key = keyFor(url)

        val firstSeen = firstSeenAtMillis.getOrPut(key) { System.currentTimeMillis() }
        val requestNumber = requestCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        val elapsedMs = System.currentTimeMillis() - firstSeen
        val rangeHeader = request.header("Range") ?: "(none)"

        Timber.tag(TAG).d(
            "Request #$requestNumber for url-key=$key | elapsed since 1st request: ${elapsedMs}ms | Range=$rangeHeader"
        )

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Timber.tag(TAG).e(
                e,
                "Request #$requestNumber for url-key=$key threw before a response was received (elapsed=${elapsedMs}ms)"
            )
            throw e
        }

        if (!response.isSuccessful) {
            val headersDump = response.headers.joinToString("\n") { (name, value) -> "  $name: $value" }
            // peekBody does not consume the real body - the caller (ExoPlayer) still gets
            // an intact, unread body stream after this returns.
            val bodyPreview = try {
                response.peekBody(2048).string()
            } catch (e: Exception) {
                "(failed to peek body: ${e.message})"
            }

            Timber.tag(TAG).w(
                """
                |Request #$requestNumber for url-key=$key FAILED
                |  code: ${response.code}
                |  elapsed since 1st request to this URL: ${elapsedMs}ms
                |  Range requested: $rangeHeader
                |  response headers:
                |$headersDump
                |  response body (first 2KB):
                |$bodyPreview
                """.trimMargin()
            )
        } else {
            Timber.tag(TAG).d(
                "Request #$requestNumber for url-key=$key succeeded (code=${response.code}, elapsed=${elapsedMs}ms)"
            )
        }

        return response
    }
}
