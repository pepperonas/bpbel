package io.celox.bpbel.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10

/** Immutable snapshot the UI renders. */
data class AudioUiState(
    val bpm: Double = 0.0,
    val confidence: Double = 0.0,
    /** Monotonic counter incremented on every detected beat. The UI
     *  keys its pulse animation off changes to this value. */
    val beatTick: Long = 0L,
    /** Full-band loudness in dBFS, clamped to [DB_FLOOR, 0]. Fast/raw —
     *  drives the reactive bar meter. */
    val dbfs: Double = DB_FLOOR,
    /** Heavily-smoothed dBFS for the numeric readout, so the displayed
     *  number is calm enough to read instead of flickering every frame. */
    val displayDbfs: Double = DB_FLOOR,
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
 *     → KickBandpass (30-100 Hz, exact inspector-rust graph) → BpmAnalyzer
 *
 * The smoothed dBFS also drives a **loudness gate**: onsets are only
 * registered while the room is genuinely loud (above [LOUD_DB]), so a
 * quiet room / mic hum never makes the BPM lock onto noise.
 *
 * Results are published on [state] (a [StateFlow]) at frame rate
 * (~43 frames/s with a 1024-sample frame), which Compose collects.
 */
class AudioEngine {

    private val _state = MutableStateFlow(AudioUiState())
    val state: StateFlow<AudioUiState> = _state.asStateFlow()

    /** One capture session. The worker owns all mutable analysis state;
     *  the main thread only flips [active] — so there is no shared state
     *  to race on and no need to block on [Thread.join]. */
    private class Session {
        /** True while this session is the engine's current one. Cleared by
         *  stop() (main thread) or by the worker itself on fatal error —
         *  whoever wins the CAS also publishes the idle state. */
        val active = AtomicBoolean(true)
    }

    // Main-thread only.
    private var session: Session? = null

    /** Serialises state publication against session hand-over, so a worker
     *  that is being stopped can never publish a stale "listening" frame
     *  *after* stop() has already published the idle state. Uncontended in
     *  the steady state — negligible at ~43 acquisitions/s. */
    private val publishLock = Any()

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (session?.active?.get() == true) return
        val s = Session()
        session = s
        thread(name = "bpbel-audio", isDaemon = true) { captureLoop(s) }
    }

    /** Signal the worker to wind down; never blocks. The worker releases
     *  the AudioRecord on its own thread. */
    fun stop() {
        synchronized(publishLock) {
            session?.active?.set(false)
            session = null
            _state.value = AudioUiState() // back to idle
        }
    }

    /** Publish [value] only while [s] is still the live session. */
    private fun publish(s: Session, value: AudioUiState) {
        synchronized(publishLock) {
            if (s.active.get()) _state.value = value
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureLoop(session: Session) {
        // Audio-capture priority so the 60 fps visualizer can't starve the
        // analysis thread into buffer overruns / missed onsets.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Generous buffer (≥ 4 frames) to absorb scheduling jitter.
        val bufferBytes = maxOf(minBuf, FRAME * 2 * 4)

        val record = openRecord(bufferBytes)
        if (record == null) {
            session.active.set(false)
            return
        }

        val analyzer = BpmAnalyzer()
        val bandpass = KickBandpass(SAMPLE_RATE)
        val pcm = ShortArray(FRAME)
        val floats = FloatArray(FRAME)
        var beatTick = _state.value.beatTick
        var smoothedDb = AudioUiState.DB_FLOOR

        try {
            record.startRecording()
            publish(session, _state.value.copy(listening = true))

            while (session.active.get()) {
                val read = record.read(pcm, 0, FRAME)
                if (read == 0) continue
                // Negative = unrecoverable error (e.g. ERROR_DEAD_OBJECT
                // when another app grabs the mic, or the privacy toggle
                // cuts it). Bail out instead of spinning at 100 % CPU;
                // the finally block publishes the idle state.
                if (read < 0) break

                // PCM-16 → float [-1, 1] and full-band RMS in one pass.
                var sumSq = 0.0
                for (i in 0 until read) {
                    val v = pcm[i] / 32768f
                    floats[i] = v
                    sumSq += v.toDouble() * v
                }

                val rms = Math.sqrt(sumSq / read)
                val dbfs = if (rms > 0) {
                    (20.0 * log10(rms)).coerceIn(AudioUiState.DB_FLOOR, 0.0)
                } else {
                    AudioUiState.DB_FLOOR
                }
                // One-pole smoothing (~0.4 s time constant at 43 fps) for
                // the readable numeric dB; the bar meter still uses `dbfs`.
                smoothedDb += (dbfs - smoothedDb) * 0.08

                // Band-pass for the kick (in place — `floats` is not read
                // again this frame), then onset/tempo analysis. The
                // loudness gate (smoothed dBFS above LOUD_DB) keeps the BPM
                // from locking onto room noise when no music is playing.
                val now = SystemClock.elapsedRealtime().toDouble()
                val loud = smoothedDb > LOUD_DB
                bandpass.processInPlace(floats, read)
                analyzer.push(floats, now, allow = loud, length = read)
                val est = analyzer.estimate(now)
                if (est.beatJustFired) beatTick++

                publish(
                    session,
                    AudioUiState(
                        bpm = est.bpm,
                        confidence = est.confidence,
                        beatTick = beatTick,
                        dbfs = dbfs,
                        displayDbfs = smoothedDb,
                        energy = analyzer.currentEnergy(),
                        listening = true,
                    ),
                )
            }
        } catch (_: Throwable) {
            // Swallow — the finally block below resets the UI to idle.
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
            // If the loop died on its own (mic lost, capture error) rather
            // than via stop(), win the deactivation and publish idle so the
            // UI never sits on a frozen "LIVE" state — and a later start()
            // isn't blocked by a session that looks alive.
            synchronized(publishLock) {
                if (session.active.compareAndSet(true, false)) {
                    _state.value = AudioUiState()
                }
            }
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

        /** Loudness gate for onset detection (dBFS). Onsets only register
         *  while the smoothed level is above this — i.e. the room is
         *  genuinely loud (music). Set ~8 dB above a typical quiet-room
         *  noise floor (≈ -60 dBFS on an UNPROCESSED phone mic) so quiet
         *  music still registers while silence / hum can't fake a BPM.
         *  Mirrors the disco-controller's `LOUD_DB`. */
        const val LOUD_DB = -52.0
    }
}
