/**
 * Music Recognition Feature
 *
 * This feature is based on the original MusicRecognizer project by Aleksey Saenko.
 * Original project: https://github.com/aleksey-saenko/MusicRecognizer
 *
 * Fingerprinting algorithm ported from SongRec (https://github.com/marin-m/SongRec).
 * Special thanks to Aleksey Saenko and marin-m for the music recognition implementation.
 */

package com.metrolist.music.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.metrolist.shazamkit.Shazam
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Service for recognizing music using audio fingerprinting.
 * Records audio from the microphone, generates a Shazam-compatible fingerprint,
 * and sends it to the Shazam API for recognition.
 *
 * Uses progressive recognition: tries matching as soon as enough distinctive peaks
 * are collected (typically ~3s), falling back to longer samples up to 12s.
 * Recording continues uninterrupted while API attempts run in parallel.
 */
object MusicRecognitionService {

    /** Preferred capture rate — matches the fingerprint pipeline and avoids resampling. */
    private const val PREFERRED_SAMPLE_RATE = VibraSignature.REQUIRED_SAMPLE_RATE
    private const val FALLBACK_SAMPLE_RATE = 44_100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /** Maximum recording length before giving up. */
    private const val MAX_RECORDING_MS = 12_000L

    /**
     * Progressive recognition checkpoints (ms of audio).
     * SongRec typically succeeds around 3s; we retry with more context on no-match.
     */
    private val RECOGNITION_CHECKPOINTS_MS = longArrayOf(3_000L, 6_000L, 9_000L, 12_000L)

    /** Minimum peaks before a checkpoint is worth sending to the API. */
    private const val MIN_PEAKS_FOR_ATTEMPT = 12

    /** RMS below this (relative to full-scale int16) is treated as silence. */
    private const val SILENCE_RMS_THRESHOLD = 80.0

    private val recognizeMutex = Mutex()
    private val cancelled = AtomicBoolean(false)

    private val _recognitionStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val recognitionStatus: StateFlow<RecognitionStatus> = _recognitionStatus.asStateFlow()

    /**
     * Set to true by the widget service after it has already persisted the result to the
     * database, so that [RecognitionScreen] skips the duplicate insert.
     * Reset to false by [reset].
     */
    @Volatile
    var resultSavedExternally: Boolean = false

    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start the music recognition process.
     * Records audio, generates fingerprint, and queries Shazam API.
     * Overlapping calls are ignored so a double-tap cannot start a second 12s capture.
     */
    @SuppressLint("MissingPermission")
    suspend fun recognize(context: Context): RecognitionStatus {
        if (!recognizeMutex.tryLock()) {
            return _recognitionStatus.value
        }
        try {
            return withContext(Dispatchers.IO) {
                cancelled.set(false)

                if (!hasRecordPermission(context)) {
                    val status = RecognitionStatus.Error("Microphone permission not granted")
                    _recognitionStatus.value = status
                    return@withContext status
                }

                _recognitionStatus.value = RecognitionStatus.Listening

                try {
                    val capture = recordAndRecognizeProgressively()
                    if (cancelled.get()) {
                        _recognitionStatus.value = RecognitionStatus.Ready
                        return@withContext RecognitionStatus.Ready
                    }
                    _recognitionStatus.value = capture
                    capture
                } catch (e: CancellationException) {
                    _recognitionStatus.value = RecognitionStatus.Ready
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Music recognition failed")
                    val status = RecognitionStatus.Error(e.message ?: "Recognition failed")
                    _recognitionStatus.value = status
                    status
                }
            }
        } finally {
            recognizeMutex.unlock()
        }
    }

    /**
     * Records audio continuously while periodically attempting recognition at checkpoints.
     * Returns as soon as a match is found, or after the final checkpoint completes.
     */
    @SuppressLint("MissingPermission")
    private suspend fun recordAndRecognizeProgressively(): RecognitionStatus {
        val sampleRate = selectSampleRate()
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return RecognitionStatus.Error("Device does not support audio recording at ${sampleRate}Hz")
        }

        val bufferSize = maxOf(minBufferSize, sampleRate / 4) // ~250ms chunks
        val audioSource = preferredAudioSource()

        var audioRecord = createAudioRecord(audioSource, sampleRate, bufferSize)
        if (audioRecord == null && audioSource != MediaRecorder.AudioSource.MIC) {
            audioRecord = createAudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, bufferSize)
        }
        if (audioRecord == null) {
            return RecognitionStatus.Error("Microphone failed to initialize")
        }

        val pcmLock = Any()
        val pcmBuffer = ByteArrayOutputStream(sampleRate * 2 * 13)
        val latestResult = AtomicReference<RecognitionStatus?>(null)
        val matched = AtomicBoolean(false)

        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                return RecognitionStatus.Error("Failed to start recording")
            }

            // Some devices accept UNPROCESSED but capture digital silence.
            // Probe a short window and fall back to MIC if needed.
            audioRecord = warmupOrFallbackToMic(audioRecord, sampleRate, bufferSize)
                ?: return RecognitionStatus.Error("Failed to start recording")

            coroutineScope {
                // Continuous capture — never blocked by network
                val recordJob = launch(Dispatchers.IO) {
                    val readBuf = ByteArray(bufferSize)
                    var consecutiveErrors = 0
                    val startTime = System.currentTimeMillis()

                    while (isActive && !cancelled.get() && !matched.get()) {
                        if (System.currentTimeMillis() - startTime >= MAX_RECORDING_MS + 500L) break

                        val bytesRead = audioRecord.read(readBuf, 0, readBuf.size)
                        when {
                            bytesRead > 0 -> {
                                consecutiveErrors = 0
                                synchronized(pcmLock) {
                                    pcmBuffer.write(readBuf, 0, bytesRead)
                                }
                            }
                            bytesRead == AudioRecord.ERROR_INVALID_OPERATION ||
                                bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                                consecutiveErrors++
                                if (consecutiveErrors > 10) {
                                    latestResult.compareAndSet(
                                        null,
                                        RecognitionStatus.Error("Microphone read error"),
                                    )
                                    break
                                }
                            }
                        }
                    }
                }

                // Progressive recognition scheduler
                val recognizeJob = launch(Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()
                    for ((index, checkpointMs) in RECOGNITION_CHECKPOINTS_MS.withIndex()) {
                        // Wait until checkpoint time or cancellation/match
                        while (isActive && !cancelled.get() && !matched.get()) {
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed >= checkpointMs) break
                            delay(50)
                        }
                        if (!isActive || cancelled.get() || matched.get()) break

                        val snapshot = synchronized(pcmLock) { pcmBuffer.toByteArray() }
                        val capturedMs = snapshot.size * 1000L / (sampleRate * 2)
                        if (capturedMs < checkpointMs - 500L && index < RECOGNITION_CHECKPOINTS_MS.lastIndex) {
                            // Not enough audio yet (startup lag) — skip early, final will catch up
                            continue
                        }
                        if (snapshot.size < sampleRate) continue // < ~0.5s

                        if (checkpointMs <= 3_000L && isMostlySilent(snapshot)) {
                            Timber.d("Recognition checkpoint ${checkpointMs}ms skipped: silence")
                            continue
                        }

                        // Show processing on first real attempt; keep Listening if still recording
                        if (_recognitionStatus.value is RecognitionStatus.Listening ||
                            _recognitionStatus.value is RecognitionStatus.Processing
                        ) {
                            _recognitionStatus.value = RecognitionStatus.Processing
                        }

                        val attempt = attemptRecognize(snapshot, sampleRate)
                        latestResult.set(attempt)

                        when (attempt) {
                            is RecognitionStatus.Success -> {
                                matched.set(true)
                                break
                            }
                            is RecognitionStatus.NoMatch -> {
                                if (index < RECOGNITION_CHECKPOINTS_MS.lastIndex && !cancelled.get()) {
                                    _recognitionStatus.value = RecognitionStatus.Listening
                                }
                            }
                            is RecognitionStatus.Error -> {
                                // Retry on intermediate network errors; surface on final checkpoint
                                if (index >= RECOGNITION_CHECKPOINTS_MS.lastIndex) break
                                Timber.w("Recognition attempt failed at ${checkpointMs}ms: ${attempt.message}")
                                if (!cancelled.get()) {
                                    _recognitionStatus.value = RecognitionStatus.Listening
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                // Stop recording once matched or recognition finished
                recognizeJob.join()
                matched.set(true) // ensure record loop exits
                recordJob.cancelAndJoin()
            }
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            runCatching { audioRecord.release() }
        }

        if (cancelled.get()) return RecognitionStatus.Ready

        val captured = synchronized(pcmLock) { pcmBuffer.toByteArray() }
        if (captured.size >= sampleRate && isMostlySilent(captured) &&
            latestResult.get() !is RecognitionStatus.Success
        ) {
            return RecognitionStatus.Error("No audio detected. Check the microphone and try again.")
        }

        return when (val result = latestResult.get()) {
            is RecognitionStatus.Success,
            is RecognitionStatus.NoMatch,
            is RecognitionStatus.Error -> result
            else -> RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
        }
    }

    /**
     * Reads ~250ms. If the preferred source produced silence, reopen with [MediaRecorder.AudioSource.MIC].
     */
    @SuppressLint("MissingPermission")
    private fun warmupOrFallbackToMic(
        current: AudioRecord,
        sampleRate: Int,
        bufferSize: Int,
    ): AudioRecord? {
        val probeBytes = maxOf(sampleRate / 2, 2) // ~250ms of 16-bit mono
        val probe = ByteArray(probeBytes)
        var filled = 0
        val deadline = System.currentTimeMillis() + 400L
        while (filled < probe.size && System.currentTimeMillis() < deadline) {
            val n = current.read(probe, filled, probe.size - filled)
            if (n > 0) filled += n else break
        }
        if (filled >= 4 && !isMostlySilent(probe.copyOf(filled))) {
            return current
        }
        if (current.audioSource == MediaRecorder.AudioSource.MIC) {
            return current
        }

        Timber.w("Preferred mic source produced silence; falling back to MIC")
        runCatching {
            if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop()
        }
        current.release()

        val fallback = createAudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, bufferSize)
            ?: return null
        fallback.startRecording()
        if (fallback.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            fallback.release()
            return null
        }
        return fallback
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(audioSource: Int, sampleRate: Int, bufferSize: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                audioSource,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioRecord init failed for source=$audioSource rate=$sampleRate")
            null
        }
    }

    /**
     * Convert captured PCM to 16 kHz mono LE, fingerprint, and query Shazam.
     */
    private suspend fun attemptRecognize(rawPcm: ByteArray, captureSampleRate: Int): RecognitionStatus {
        val pcm16k = ensure16kHzMonoLe(rawPcm, captureSampleRate)
            ?: return RecognitionStatus.Error("Failed to prepare audio for fingerprinting")

        if (pcm16k.size < VibraSignature.REQUIRED_SAMPLE_RATE) {
            return RecognitionStatus.NoMatch("Audio sample too short")
        }

        val generated = try {
            ShazamSignatureGenerator.generate(pcm16k)
        } catch (e: Exception) {
            return RecognitionStatus.Error("Failed to generate fingerprint: ${e.message}")
        }

        if (generated.peakCount < MIN_PEAKS_FOR_ATTEMPT) {
            Timber.d("Skipping API call: only ${generated.peakCount} peaks")
            return RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
        }

        val sampleDurationMs = generated.sampleCount * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE
        val result = Shazam.recognize(generated.uri, sampleDurationMs)

        return result.fold(
            onSuccess = { RecognitionStatus.Success(it) },
            onFailure = { error ->
                val message = error.message ?: "Unknown error"
                if (message.contains("No match", ignoreCase = true)) {
                    RecognitionStatus.NoMatch("No matches found. Try again with clearer audio.")
                } else {
                    RecognitionStatus.Error(message)
                }
            },
        )
    }

    /**
     * Ensures mono 16-bit little-endian PCM at [VibraSignature.REQUIRED_SAMPLE_RATE].
     */
    private suspend fun ensure16kHzMonoLe(rawPcm: ByteArray, captureSampleRate: Int): ByteArray? {
        if (rawPcm.isEmpty() || rawPcm.size % 2 != 0) return null

        // AudioRecord delivers native-endian PCM; fingerprint expects little-endian.
        val lePcm = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            rawPcm
        } else {
            swapEndian16(rawPcm)
        }

        if (captureSampleRate == VibraSignature.REQUIRED_SAMPLE_RATE) {
            return lePcm
        }

        val decoded = DecodedAudio(
            data = lePcm,
            channelCount = 1,
            sampleRate = captureSampleRate,
            pcmEncoding = AUDIO_FORMAT,
        )
        return AudioResampler.resample(decoded, VibraSignature.REQUIRED_SAMPLE_RATE)
            .getOrElse {
                Timber.e(it, "Resample failed")
                return null
            }
            .data
            .takeIf { it.isNotEmpty() && it.size % 2 == 0 }
    }

    private fun swapEndian16(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        while (i + 1 < data.size) {
            out[i] = data[i + 1]
            out[i + 1] = data[i]
            i += 2
        }
        return out
    }

    private fun isMostlySilent(pcm: ByteArray): Boolean {
        if (pcm.size < 4) return true
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asShortBuffer()
        var sumSq = 0.0
        val n = bb.remaining()
        val step = maxOf(1, n / 4000)
        var count = 0
        var i = 0
        while (i < n) {
            val s = bb.get(i).toDouble()
            sumSq += s * s
            count++
            i += step
        }
        if (count == 0) return true
        val rms = sqrt(sumSq / count)
        return rms < SILENCE_RMS_THRESHOLD
    }

    private fun selectSampleRate(): Int {
        val preferred = AudioRecord.getMinBufferSize(
            PREFERRED_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (preferred != AudioRecord.ERROR && preferred != AudioRecord.ERROR_BAD_VALUE) {
            return PREFERRED_SAMPLE_RATE
        }
        return FALLBACK_SAMPLE_RATE
    }

    /**
     * Prefer unprocessed audio so DSP (AGC, noise suppression) does not distort the fingerprint.
     * Falls back to MIC on older devices / when UNPROCESSED is unavailable.
     */
    private fun preferredAudioSource(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    /**
     * Reset status and request cancellation of any in-flight recognition.
     */
    fun reset() {
        cancelled.set(true)
        _recognitionStatus.value = RecognitionStatus.Ready
        resultSavedExternally = false
    }
}
