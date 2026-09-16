package com.metrolist.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.MediaItem
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Handles stream resolution for playback with intelligent client fallback.
 *
 * This is the integration point between MusicService and InnerTubeXPlayer.
 * It manages the retry logic when a client fails, marking failures and triggering
 * fallback to the next available client.
 */
class PlaybackStreamResolver(
    private val context: Context,
    private val connectivityManager: ConnectivityManager,
) {
    private companion object {
        const val TAG = "PlaybackStreamResolver"
    }

    /**
     * Resolve a playback stream for the given video.
     *
     * This method handles the full resolution pipeline:
     * 1. Calls InnerTubeXPlayer to extract the stream
     * 2. If successful, returns the playback data
     * 3. If the extraction fails with a network error, returns the error
     * 4. If the extraction fails with StreamResolveException, logs diagnostics
     *
     * The caller should check the result and decide whether to retry.
     * If a 403 (quota) error occurs, the failed client is automatically marked
     * and the next playback attempt will use a different client.
     */
    suspend fun resolveStream(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        contentHints: ContentHints = ContentHints(),
        allowBoundedRange: Boolean = true,
    ): Result<InnerTubeXPlayer.PlaybackData> =
        try {
            InnerTubeXPlayer.playerResponseForPlayback(
                videoId = videoId,
                playlistId = playlistId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                contentHints = contentHints,
                allowBoundedRange = allowBoundedRange,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Failed to resolve stream for $videoId")
            Result.failure(error)
        }

    /**
     * Mark a client as failed and trigger fallback.
     *
     * This should be called when playback fails with a quota error (403).
     * The client will be excluded from the next extraction attempt.
     */
    fun markClientFailed(videoId: String, clientName: String) {
        markPlaybackClientFailed(videoId, clientName)
    }
}
