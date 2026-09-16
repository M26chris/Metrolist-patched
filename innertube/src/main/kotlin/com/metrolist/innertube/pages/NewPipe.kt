package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(
    proxy: Proxy?,
    proxyAuth: String?,
) : Downloader() {
    private fun normalizeResponseBody(
        url: String,
        body: String?,
    ): String? {
        if (!url.contains("returnyoutubedislikeapi.com", ignoreCase = true)) {
            return body
        }

        val trimmed = body?.trimStart().orEmpty()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return body
        }

        return "{\"likes\":0,\"dislikes\":0,\"viewCount\":0}"
    }

    private val client: OkHttpClient = run {
        var builder = OkHttpClient
            .Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                proxyAuth?.let { auth ->
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }

        // Android 7.0 (API 24–25) does not trust the ISRG Root X1 certificate used by
        // YouTube. Without this bypass, all NewPipe network calls fail with
        // CertPathValidatorException, producing a NullPointerException downstream when
        // the Downloader result is expected but never arrives.
        if (android.os.Build.VERSION.SDK_INT <= 25) {
            try {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                builder = builder
                    .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                    .hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {}
        }

        builder.build()
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()

            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val latestUrl = response.request.url.toString()
        val responseBodyToReturn = normalizeResponseBody(latestUrl, response.body.string())
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBodyToReturn,
            responseBodyToReturn?.toByteArray(),
            latestUrl,
        )
    }

    override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
        TODO("Placeholder")
    }
}

object NewPipeExtractor {
    init {
        // NewPipe.init(...) must run once before any of this object's functions touch
        // YoutubeJavaScriptPlayerManager/StreamInfo, or NewPipe's Downloader singleton
        // stays null and every call NPEs with "Attempt to invoke virtual method
        // Downloader.get(...) on a null object reference". This used to live in a
        // separate NewPipeUtils object that nothing ever referenced (dead code sitting
        // on the actual initialization side effect) - moved here, directly in the
        // object that's actually used.
        NewPipe.init(
            NewPipeDownloaderImpl(
                YouTube.proxy,
                YouTube.proxyAuth,
            ),
        )
    }

    fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        return try {
            val streamInfo =
                StreamInfo.getInfo(
                    NewPipe.getService(0),
                    "https://www.youtube.com/watch?v=$videoId",
                )
            val streamsList = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            streamsList.mapNotNull {
                (it.itagItem?.id ?: return@mapNotNull null) to it.content
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String? {
        return try {
            val url = format.url ?: format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("Could not parse cipher signature")
                val signatureParam = params["sp"]
                    ?: throw ParsingException("Could not parse cipher signature parameter")
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: throw ParsingException("Could not parse cipher url")
                url.parameters[signatureParam] =
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                        videoId,
                        obfuscatedSignature
                    )
                url.toString()
            } ?: throw ParsingException("Could not find format url")

            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url,
            )
        } catch (e: Exception) {
            null
        }
    }
}
