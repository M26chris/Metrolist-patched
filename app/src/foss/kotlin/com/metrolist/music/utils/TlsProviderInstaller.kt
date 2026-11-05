/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context

/**
 * foss-flavor stub: no Google Play Services on this build, so there is nothing to
 * install here. TLS on this flavor is covered entirely by Conscrypt, installed separately
 * in App.installModernTlsProvider() - that path needs no Play Services APK on the device.
 */
object TlsProviderInstaller {
    fun installIfAvailable(context: Context) {
        // No-op: intentional. See kdoc above.
    }
}
