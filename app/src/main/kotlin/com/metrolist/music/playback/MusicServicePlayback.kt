/**
 * Extension for MusicService to handle playback stream resolution.
 *
 * This file contains the playback logic that integrates with InnerTubeXPlayer
 * to resolve streams with intelligent client fallback.
 */
package com.metrolist.music.playback

import android.net.ConnectivityManager
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.InnerTubeXPlayer
import timber.log.Timber

/**
 * Resolve a playback stream for the given video, with automatic client fallback.
 *
 * Usage:
 * ```
 * val result = getPlaybackStream(
 *     videoId = "dQw4w9WgXcQ",
 *     audioQuality = AudioQuality.HIGH,
 *     connectivityManager = connectivityManager
 * )
 *
 * when {
 *     result.isSuccess -> {
 *         val playbackData = result.getOrThrow()
 *         // Use playbackData.streamUrl, playbackData.streamHeaders, etc.
 *     }
 *     result.isFailure -> {
 *         val error = result.exceptionOrNull()
 *         if (error?.message?.contains("403") == true) {
 *             // Likely hit a quota; log and try a different approach
 *         }
 *     }
 * }
 * ```
 *
 * The extractor tries clients in order: visionOS, ANDROID, WEB_CREATOR, TVHTML5, WEB_REMIX.
 * If a client fails (e.g., 403 on PoToken quota), it's excluded from the next attempt
 * for the same video for 5 minutes.
 */
suspend fun getPlaybackStream(
    videoId: String,
    playlistId: String? = null,
    audioQuality: AudioQuality,
    connectivityManager: ConnectivityManager,
    contentHints: ContentHints = ContentHints(),
    allowBoundedRange: Boolean = true,
): Result<InnerTubeXPlayer.PlaybackData> {
    val result = InnerTubeXPlayer.playerResponseForPlayback(
        videoId = videoId,
        playlistId = playlistId,
        audioQuality = audioQuality,
        connectivityManager = connectivityManager,
        contentHints = contentHints,
        allowBoundedRange = allowBoundedRange,
    )
    return result
}

/**
 * Mark a client as failed for a specific video, triggering fallback on next playback.
 *
 * This should be called when a playback attempt fails with 403 (Forbidden) or
 * similar quota errors. The failed client will be excluded from the next extraction
 * attempt for the same video, and will expire after 5 minutes.
 *
 * Example use case: WEB_REMIX hits the 1 MiB PoToken quota on a long track.
 * Call this with clientName="WEB_REMIX" so the next playback attempt tries
 * visionOS or ANDROID instead.
 */
fun markPlaybackClientFailed(
    videoId: String,
    clientName: String,
) {
    Timber.i("Marking client $clientName as failed for video $videoId")
    InnerTubeXPlayer.markStreamClientFailed(videoId, clientName)
}
