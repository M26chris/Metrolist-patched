# InnerTubeX Client Selection System

## Overview

This document describes how the app handles YouTube stream resolution with intelligent client fallback. It solves the critical 1 MiB PoToken quota limitation that was causing 403 errors on long tracks.

## The Problem

YouTube enforces a **1 MiB byte quota** on PoToken-authenticated clients (WEB_REMIX, TVHTML5). When playback exceeds this limit:
- Request fails with **403 Forbidden**
- Entire track becomes unplayable
- No automatic fallback occurred
- User had to restart playback manually

## The Solution

Inspired by [Metrolist's approach](https://github.com/MetrolistGroup/Metrolist), we implement:

### 1. Per-Video Failure Tracking

```kotlin
// Track which clients have failed for this video
val failedClients = InnerTubeXPlayer.failedStreamClients(videoId)
// Returns: {"WEB_REMIX"}  (empty set if none failed)
```

- **Per-video, not per-token**: Failures are tracked individually for each video
- **5-minute TTL**: After 5 minutes, failed clients can be retried
- **Automatic expiration**: Old failures are cleaned up automatically

### 2. Intelligent Client Prioritization

Clients are tried in this order:

1. **visionOS** - No throttle gate, highest success rate
2. **ANDROID** - Native client, usually uncapped
3. **WEB_CREATOR** - Creator web client, reliable
4. **TVHTML5** - TV client, fallback
5. **WEB_REMIX** - Primary but capped to ~1 MiB

**Excluded clients are skipped**, so failures automatically fall through to the next option.

### 3. Failure Marking on 403

```kotlin
when {
    result.isSuccess -> playStream(result.getOrThrow())
    result.isFailure -> {
        val error = result.exceptionOrNull()
        if (error?.message?.contains("403") == true) {
            // Mark this client as failed for this video
            markPlaybackClientFailed(videoId, playbackData.streamClient)
            // Next playback attempt will try a different client
        }
    }
}
```

## Architecture

### Core Components

**InnerTubeXPlayer** (`app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt`)
- Stream extraction engine
- Manages InnerTubeX extractor with client exclusion
- Handles cipher service and token provider
- Full diagnostics logging

**PlaybackStreamResolver** (`app/src/main/kotlin/com/metrolist/music/playback/PlaybackStreamResolver.kt`)
- Integration layer for MusicService
- Wraps InnerTubeXPlayer with clean API
- Handles error propagation

**MusicServicePlayback** (`app/src/main/kotlin/com/metrolist/music/playback/MusicServicePlayback.kt`)
- Helper functions for common use cases
- Documentation and examples

### Data Flow

```
MusicService.loadStream(videoId)
    ↓
PlaybackStreamResolver.resolveStream(videoId)
    ↓
InnerTubeXPlayer.playerResponseForPlayback(videoId)
    ↓
1. Get failed clients: failedStreamClients(videoId) → {"WEB_REMIX"}
2. Create exclusion set: excludedClients = {"WEB_REMIX"}
3. Call extractor.extract(videoId, excludedClients=...)
4. InnerTubeX tries: VISIONOS → fails → try ANDROID → succeeds!
5. Return PlaybackData with streamClient="ANDROID"
    ↓
MusicService.playStream(playbackData)
```

## Usage in MusicService

### Basic Playback

```kotlin
val resolver = PlaybackStreamResolver(context, connectivityManager)

val result = resolver.resolveStream(
    videoId = "dQw4w9WgXcQ",
    playlistId = null,
    audioQuality = AudioQuality.HIGH
)

when {
    result.isSuccess -> {
        val playbackData = result.getOrThrow()
        Timber.i("Stream resolved with client: ${playbackData.streamClient}")
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(playbackData.streamUrl)
                .setMediaMetadata(...)
                .build()
        )
    }
    result.isFailure -> {
        val error = result.exceptionOrNull()
        Timber.e(error, "Failed to resolve stream")
        showError("Playback unavailable")
    }
}
```

### Handling 403 Quota Errors

```kotlin
// When playback fails with 403:
if (error.message?.contains("403") == true) {
    // Mark the client as failed for this video
    resolver.markClientFailed(videoId, playbackData.streamClient)
    
    // Retry playback - will use a different client next time
    val retryResult = resolver.resolveStream(videoId)
    // ...
}
```

## Diagnostics and Logging

### Timber Logs

When debugging playback issues, check Logcat for these tags:

```
# Stream extraction logs
I/InnerTubeXPlayer: Playback: client=VISIONOS, url_range=..., expires_in=300
I/InnerTubeXPlayer: Playback: excluding clients for dQw4w9WgXcQ: {WEB_REMIX}

# Client failure tracking
W/InnerTubeXPlayer: Marked client as failed: videoId=dQw4w9WgXcQ, client=WEB_REMIX
W/InnerTubeXPlayer: Playback: extraction failed: reason=HTTP_ERROR

# Fallback behavior
D/InnerTubeXPlayer: Failed clients cache expired for dQw4w9WgXcQ
```

### Test Coverage

`InnerTubeXPlayerTest.kt` validates:
- Per-video failure accumulation
- TTL expiration (5 minutes)
- Fallback config behavior

Run with:
```bash
./gradlew testDebugUnitTest
```

## Migration from Old Approach

If you had manual client rotation or ad-hoc retry logic:

### Before
```kotlin
// Manual client selection
val client = if (lastClientFailed) WEB_CREATOR else WEB_REMIX
val stream = YouTube.player(client, videoId).getOrThrow()
```

### After
```kotlin
// Automatic, intelligent selection
val playbackData = resolver.resolveStream(videoId).getOrThrow()
// InnerTubeXPlayer handles client selection and fallback
```

## Performance Impact

- **Minimal overhead**: Client exclusion is a single set lookup
- **Memory efficient**: Per-video cache is a ConcurrentHashMap with bounded size
- **No cold starts**: Extractor is cached between playbacks

## Future Improvements

1. **Cross-device sync**: Sync failed clients across devices via cloud
2. **Client preference**: Let users choose preferred clients
3. **Analytics**: Track which clients work best per region
4. **Adaptive rotation**: Rotate through clients periodically even if successful

## References

- [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt) - Original implementation
- [InnerTubeX GitHub](https://github.com/MetrolistGroup/innertubex) - Core extraction library
- YouTube Music API documentation (internal)

## Questions?

See the test file for more examples, or check Timber logs during playback for diagnostics.
