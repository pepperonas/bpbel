package io.celox.bpbel.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.log10

/** Immutable snapshot the UI renders. */
data class AudioUiState(
    val bpm: Double = 0.0,
    val confidence: Double = 0.0,
    /** Monotonic counter incremented on every detected beat. The UI
     *  keys its pulse animation off changes to this value. */
    val beatTick: Long = 0L,
    /** Full-band loudness in dBFS, clamped to [DB_FLOOR, 0]. */
    val dbfs: Double = DB_FLOOR,
    /** Band-passed RMS (kick band), 0..~1. Drives the input meter. */
    val energy: Double = 0.0,
    val listening: Boolean = false,
) {
    /** 0..1 loudness for meters: DB_FLOOR → 0, 0 dBFS → 1. */
    val loudnessFraction: Float
        get() = ((dbfs - DB_FLOOR) / -DB_FLOOR).coerceIn(0.0, 1.0).toFloat()

    companion object {
        const val DB_FLOOR = -60.0
    }
}

/**
 * Microphone capture + BPM/dB analysis on a dedicated thread.
 *
 * Audio path:
 *   AudioRecord (mono PCM-16 @ 44.1 kHz)
 *     → full-band RMS → dBFS                (loudness meter)
 *     → KickBandpass (30-100 Hz) → BpmAnalyzer.push/estimate  (tempo)
 *
 * Results are published on [state] (a [StateFlow]) at frame rate
 * (~43 frames/s with a 1024-sample frame), which Compose collects.
 */
class AudioEngine {

    private val _state = MutableStateFlow(AudioUiState())
    val state: StateFlow<AudioUiState> = _state.asStateFlow()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private val analyzer = BpmAnalyzer()

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "bpbel-audio", isDaemon = true) { captureLoop() }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        analyzer.reset()
        _state.value = AudioUiState() // back to idle
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Generous buffer (≥ 4 frames) to absorb scheduling jitter.
        val bufferBytes = maxOf(minBuf, FRAME * 2 * 4)

        val record = openRecord(bufferBytes)
        if (record == null) {
            running = false
            return
        }

        val bandpass = KickBandpass(SAMPLE_RATE)
        val pcm = ShortArray(FRAME)
        val floats = FloatArray(FRAME)
        var beatTick = _state.value.beatTick

        try {
            record.startRecording()
            _state.value = _state.value.copy(listening = true)

            while (running) {
                val read = record.read(pcm, 0, FRAME)
                if (read <= 0) continue

                // PCM-16 → float [-1, 1] and full-band RMS in one pass.
                var sumSq = 0.0
                for (i in 0 until read) {
                    val v = pcm[i] / 32768f
                    floats[i] = v
                    sumSq += v.toDouble() * v
                }
                val frame = if (read == FRAME) floats else floats.copyOf(read)

                val rms = Math.sqrt(sumSq / read)
                val dbfs = if (rms > 0) {
                    (20.0 * log10(rms)).coerceIn(AudioUiState.DB_FLOOR, 0.0)
                } else {
                    AudioUiState.DB_FLOOR
                }

                // Band-pass for the kick, then onset/tempo analysis.
                val now = SystemClock.elapsedRealtime().toDouble()
                analyzer.push(bandpass.process(frame), now)
                val est = analyzer.estimate(now)
                if (est.beatJustFired) beatTick++

                _state.value = AudioUiState(
                    bpm = est.bpm,
                    confidence = est.confidence,
                    beatTick = beatTick,
                    dbfs = dbfs,
                    energy = analyzer.currentEnergy(),
                    listening = true,
                )
            }
        } catch (_: Throwable) {
            // Swallow — stop() / teardown handles cleanup; UI shows idle.
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
        }
    }

    /** Open an initialized [AudioRecord], preferring the least-processed
     *  source so AGC / noise-suppression doesn't flatten the kick
     *  transients or skew the dB reading. Falls back through more
     *  common sources for devices that don't support UNPROCESSED. */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openRecord(bufferBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )
        for (src in sources) {
            val rec = try {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (_: Throwable) {
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec
            rec?.release()
        }
        return null
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAME = 1024
    }
}
