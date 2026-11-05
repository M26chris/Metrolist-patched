# Integration Guide: InnerTubeX Client Selection

## For MusicService Integration

### Step 1: Initialize InnerTubeXPlayer

In your `App.kt` or during app startup:

```kotlin
InnerTubeXPlayer.initialize(applicationContext)
```

### Step 2: Create PlaybackStreamResolver

In `MusicService.onCreate()`:

```kotlin
private val connectivityManager: ConnectivityManager by lazy {
    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}

private val streamResolver: PlaybackStreamResolver by lazy {
    PlaybackStreamResolver(this, connectivityManager)
}
```

### Step 3: Replace Stream Resolution

Find where you currently resolve streams (likely in a coroutine scope handling playback):

```kotlin
// OLD WAY
suspend fun loadPlaybackUrl(videoId: String): String {
    val response = YouTube.player(WEB_REMIX, videoId).getOrThrow()
    return response.streamUrl
}

// NEW WAY
suspend fun loadPlaybackUrl(videoId: String): Result<String> {
    val result = streamResolver.resolveStream(
        videoId = videoId,
        playlistId = currentPlaylist?.id,
        audioQuality = audioQuality  // from settings
    )
    return result.map { it.streamUrl }
}
```

### Step 4: Handle 403 Errors

Where you handle playback errors:

```kotlin
private fun handlePlaybackError(videoId: String, error: Exception) {
    Timber.e(error, "Playback error for $videoId")
    
    when {
        error.message?.contains("403") == true -> {
            // Quota error - mark client as failed and retry
            val clientName = lastPlaybackData?.streamClient ?: "UNKNOWN"
            streamResolver.markClientFailed(videoId, clientName)
            
            // Retry with different client
            playNext() // or retry current track
        }
        error.message?.contains("network") == true -> {
            showError("Network error")
        }
        else -> {
            showError("Playback failed")
        }
    }
}
```

## For Custom Stream Providers

If you have custom stream loading logic:

```kotlin
suspend fun getStreamForPlayback(mediaMetadata: MediaMetadata): Result<PlaybackData> {
    val videoId = mediaMetadata.id
    
    return streamResolver.resolveStream(
        videoId = videoId,
        playlistId = getCurrentPlaylistId(),
        audioQuality = userPreferences.audioQuality,
        contentHints = ContentHints(
            isUploaded = mediaMetadata.isUserUpload,
            isLiveContent = mediaMetadata.isLive
        )
    )
}
```

## Testing

### Unit Tests

Add to your test suite:

```kotlin
class PlaybackStreamResolverTest {
    private lateinit var resolver: PlaybackStreamResolver
    private val connectivityManager = mock<ConnectivityManager>()
    
    @Before
    fun setup() {
        resolver = PlaybackStreamResolver(context, connectivityManager)
    }
    
    @Test
    fun `resolver returns success for valid video`() = runBlocking {
        val result = resolver.resolveStream("dQw4w9WgXcQ")
        assertTrue(result.isSuccess)
    }
}
```

### Manual Testing

1. **Test short track** (~30s): Should use any client
   - Check Logcat: `Playback: client=...`

2. **Test long track** (~5min): 
   - On first play: Should use visionOS or ANDROID
   - Force WEB_REMIX to fail with: `InnerTubeXPlayer.markStreamClientFailed(videoId, "WEB_REMIX")`
   - On second play: Should skip WEB_REMIX and try ANDROID

3. **Test TTL expiration**:
   - Mark client as failed
   - Wait 5+ minutes
   - Client should be retried next playback

## Troubleshooting

### "InnerTubeX returned no playable stream"

- All clients failed (rare)
- Check network connectivity
- Verify YouTube Music access
- Check Timber logs for detailed error

### "Playback: excluding clients for {videoId}: ..."

- This is normal when a previous attempt failed
- The excluded client will be retried after 5 minutes
- If exclusion list grows too large, check logs for pattern

### No client logs appearing

- Verify `InnerTubeXPlayer.initialize()` was called
- Check Timber is configured for DEBUG level
- Verify `resolveStream()` was called (not bypassed)

## Performance Checklist

- [ ] Initialize InnerTubeXPlayer once in App.kt
- [ ] Reuse same PlaybackStreamResolver instance
- [ ] Call resolveStream only when needed (not eagerly)
- [ ] Handle cancellation properly (CancellationException)
- [ ] Monitor memory usage of failed client cache

## Next Steps

1. Integrate into MusicService playback pipeline
2. Test with real YouTube Music tracks
3. Monitor Timber logs for diagnostic data
4. Gradually enable for all users (if rolling out)
5. Track metrics: success rate per client, avg resolution time

