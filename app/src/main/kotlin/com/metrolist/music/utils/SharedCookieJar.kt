/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Minimal in-memory, process-lifetime CookieJar shared by the standalone OkHttpClients that
 * fetch player.js / iframe_api / cipher configs (PlayerJsFetcher, PlayerConfigStore,
 * YTPlayerUtils' validation client).
 *
 * Those clients were built with OkHttpClient.Builder().build() and no cookieJar, i.e.
 * CookieJar.NO_COOKIES - every Set-Cookie header on a redirect response was silently
 * discarded. youtube.com occasionally responds to a plain, cookie-less GET (e.g. to
 * /iframe_api) with a redirect that sets a cookie the *next* hop expects; without a jar to
 * carry that cookie forward, the client keeps re-issuing the same cookie-less request and
 * gets redirected again, until OkHttp's own redirect cap trips with
 * "java.net.ProtocolException: Too many follow-up requests: 21". Persisting cookies across
 * requests on these clients breaks that loop.
 *
 * Deliberately NOT persisted to disk: this only needs to survive the redirect chain within
 * (and shortly across) a single fetch, not across app restarts, and these clients are
 * anonymous/cookie-less by design otherwise.
 */
object SharedCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val host = url.host
            val existing = store.getOrPut(host) { mutableListOf() }
            for (cookie in cookies) {
                existing.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) {
                    existing.add(cookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val host = url.host
            val cookies = store[host] ?: return emptyList()
            cookies.removeAll { it.expiresAt <= now }
            return cookies.filter { it.matches(url) }
        }
    }
}
