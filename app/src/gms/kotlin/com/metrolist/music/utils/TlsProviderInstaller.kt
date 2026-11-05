/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.google.android.gms.security.ProviderInstaller
import timber.log.Timber

/**
 * GMS-flavor implementation: asks Play Services to refresh the process's TLS security
 * provider. This class only exists in the "gms" flavor source set (app/src/gms/...) - the
 * "foss" and "izzy" flavors have their own no-op version below, so neither of those builds
 * ever references a Play Services class. See App.installModernTlsProvider() for why this is
 * one of two layers, not the only fix (Conscrypt, installed separately, covers all flavors).
 */
object TlsProviderInstaller {
    fun installIfAvailable(context: Context) {
        try {
            ProviderInstaller.installIfNeeded(context)
        } catch (e: Exception) {
            // Play Services missing/outdated even on a "gms" build/device - Conscrypt
            // (installed separately in App.installModernTlsProvider) is the fallback.
            Timber.tag("Metrolist_Security").d(e, "ProviderInstaller unavailable")
        }
    }
}
