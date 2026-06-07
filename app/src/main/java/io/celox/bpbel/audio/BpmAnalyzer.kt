package io.celox.bpbel.audio

import kotlin.math.sqrt

/**
 * Real-time BPM detection from a stream of audio chunks.
 *
 * Kotlin port of the battle-tested analyzer from the `inspector-rust`
 * project (`core/frontend/src/lib/bpm.ts`). Behaviour and tuning
 * constants are kept identical so the original test-suite ports 1:1.
 *
 * ## Algorithm
 *
 * Energy-based onset detection + inter-onset-interval (IOI) clustering.
 * Classic approach (Patin 2003 / Levitin ch. 6; used by Mixxx,
 * RealtimeBPMAnalyzer.js, Spotify's web player). Battle-tested for
 * 4/4 popular music — typical accuracy 85-95 % on tracks with a clear
 * kick drum.
 *
 *  1. Caller feeds chunks of **band-pass-filtered** audio (kick band
 *     ~30-100 Hz). The filtering belongs in the audio graph; this
 *     class assumes pre-filtered samples.
 *  2. Per chunk, compute RMS energy.
 *  3. Maintain a sliding-window moving average of energy.
 *  4. Onset = chunk-energy exceeds moving-average × threshold AND
 *     enough time has passed since the last onset (refractory period).
 *  5. Record onset timestamps in a sliding window.
 *  6. Inter-onset intervals → median → BPM = 60000 / median_ms.
 *  7. Octave-correction: fold into [60, 200] BPM by doubling / halving.
 *  8. Octave-snap: if the new raw estimate sits within ±8 BPM of
 *     half / double the currently-displayed value, snap it back.
 *  9. Displayed value is the mean over a 4-second sliding window of
 *     raw estimates.
 *
 * Confidence = `1 - (stddev / median)`, clamped to [0, 1].
 */

/** One BPM reading. */
data class BpmEstimate(
    /** Smoothed BPM, or 0 if not enough data yet. */
    val bpm: Double,
    /** 0..1 — based on IOI consistency in the current window. */
    val confidence: Double,
    /** True only for the single [BpmAnalyzer.estimate] call that
     *  immediately follows a [BpmAnalyzer.push] which produced a beat.
     *  Drives the visual pulse animation. */
    val beatJustFired: Boolean,
)

/** Tuning knobs. Public for tests + documentation; not user-facing. */
object BpmConfig {
    /** Moving-average baseline window for energy. */
    const val AVG_WINDOW_MS = 3000.0

    /** Chunk energy must exceed `avg × threshold` to count as an onset. */
    const val ONSET_THRESHOLD = 1.4

    /** Minimum gap between successive onsets (max 200 BPM). */
    const val ONSET_REFRACTORY_MS = 300.0

    /** Sliding window of onsets used to compute BPM. */
    const val IOI_WINDOW_MS = 6000.0

    /** Trusted BPM range. Outside → octave-corrected. */
    const val BPM_MIN = 60.0
    const val BPM_MAX = 200.0

    /** Minimum onsets in window before we trust the median. */
    const val MIN_ONSETS_FOR_ESTIMATE = 4

    /** After this long without enough onsets, reset display BPM to 0. */
    const val STALE_RESET_MS = 4000.0

    /** Octave-snap tolerance: snap raw to the locked octave when within
     *  this many BPM of half / double the display value. */
    const val OCTAVE_SNAP_TOLERANCE_BPM = 8.0

    /** Rolling window over which raw estimates are mean-averaged for
     *  the displayed value. */
    const val DISPLAY_AVG_WINDOW_MS = 4000.0
}

private data class EnergyChunk(val time: Double, val energy: Double)
private data class RawBpm(val time: Double, val bpm: Double)

class BpmAnalyzer {
    private val onsets = ArrayDeque<Double>()
    private val energyHistory = ArrayDeque<EnergyChunk>()
    private var lastOnset = Double.NEGATIVE_INFINITY
    private val rawBpmHistory = ArrayDeque<RawBpm>()
    private var displayBpm = 0.0
    private var justFiredBeat = false
    private var lastValidEstimateAt = Double.NEGATIVE_INFINITY

    /** Feed one chunk of (band-pass-filtered) audio samples.
     *  `samples` are float in [-1, 1]; `nowMs` is monotonically
     *  increasing milliseconds. */
    fun push(samples: FloatArray, nowMs: Double) {
        justFiredBeat = false

        val energy = rms(samples)
        energyHistory.addLast(EnergyChunk(nowMs, energy))
        while (energyHistory.isNotEmpty() &&
            nowMs - energyHistory.first().time > BpmConfig.AVG_WINDOW_MS
        ) {
            energyHistory.removeFirst()
        }

        // Baseline-calibration gate: wait until the energy buffer
        // actually spans the full AVG_WINDOW_MS so the threshold is
        // calibrated against a real music-level baseline before any
        // onset is allowed through. Avoids the false-onset burst on
        // startup that otherwise reads "too fast" for ~15-20 s.
        if (energyHistory.isEmpty() ||
            nowMs - energyHistory.first().time < BpmConfig.AVG_WINDOW_MS
        ) {
            return
        }

        val avg = energyHistory.sumOf { it.energy } / energyHistory.size

        val triggered = energy > avg * BpmConfig.ONSET_THRESHOLD &&
            nowMs - lastOnset >= BpmConfig.ONSET_REFRACTORY_MS

        if (triggered) {
            lastOnset = nowMs
            onsets.addLast(nowMs)
            justFiredBeat = true
            while (onsets.isNotEmpty() &&
                nowMs - onsets.first() > BpmConfig.IOI_WINDOW_MS
            ) {
                onsets.removeFirst()
            }
        }
    }

    /** Current BPM estimate. Call after each [push] or at UI framerate. */
    fun estimate(nowMs: Double): BpmEstimate {
        while (onsets.isNotEmpty() &&
            nowMs - onsets.first() > BpmConfig.IOI_WINDOW_MS
        ) {
            onsets.removeFirst()
        }

        val beatJustFired = justFiredBeat
        justFiredBeat = false // consume the edge

        if (onsets.size < BpmConfig.MIN_ONSETS_FOR_ESTIMATE) {
            // Keep showing the last value through brief onset droughts,
            // but reset to 0 after sustained silence — a stale number
            // would be a lie once the music has clearly stopped.
            if (displayBpm > 0 &&
                nowMs - lastValidEstimateAt > BpmConfig.STALE_RESET_MS
            ) {
                displayBpm = 0.0
                rawBpmHistory.clear()
            }
            return BpmEstimate(displayBpm, 0.0, beatJustFired)
        }

        // Build IOIs.
        val onsetList = onsets.toList()
        val intervals = DoubleArray(onsetList.size - 1) { i ->
            onsetList[i + 1] - onsetList[i]
        }

        val sorted = intervals.sortedArray()
        val median = sorted[sorted.size / 2]
        if (median <= 0) {
            return BpmEstimate(displayBpm, 0.0, beatJustFired)
        }

        // Octave correction.
        var rawBpm = 60000.0 / median
        while (rawBpm < BpmConfig.BPM_MIN) rawBpm *= 2
        while (rawBpm > BpmConfig.BPM_MAX) rawBpm /= 2

        // Octave snap — keep us in the locked octave when a few ghost
        // onsets push the median IOI across a half/double boundary.
        if (displayBpm > 0) {
            val tol = BpmConfig.OCTAVE_SNAP_TOLERANCE_BPM
            if (kotlin.math.abs(rawBpm * 2 - displayBpm) < tol) {
                rawBpm *= 2
            } else if (kotlin.math.abs(rawBpm / 2 - displayBpm) < tol) {
                rawBpm /= 2
            }
        }

        // Display mean over a sliding window. Dedupe consecutive
        // identical estimates so the UI rAF loop doesn't oversample the
        // same value between onsets.
        val last = rawBpmHistory.lastOrNull()
        if (last == null || kotlin.math.abs(last.bpm - rawBpm) > 0.01) {
            rawBpmHistory.addLast(RawBpm(nowMs, rawBpm))
        }
        while (rawBpmHistory.isNotEmpty() &&
            nowMs - rawBpmHistory.first().time > BpmConfig.DISPLAY_AVG_WINDOW_MS
        ) {
            rawBpmHistory.removeFirst()
        }
        if (rawBpmHistory.isNotEmpty()) {
            displayBpm = rawBpmHistory.sumOf { it.bpm } / rawBpmHistory.size
        }
        lastValidEstimateAt = nowMs

        // Confidence: tight IOI distribution → high confidence.
        val variance = intervals.sumOf { (it - median) * (it - median) } / intervals.size
        val stddev = sqrt(variance)
        val confidence = (1 - stddev / median).coerceIn(0.0, 1.0)

        return BpmEstimate(displayBpm, confidence, beatJustFired)
    }

    /** Drop all state — useful when restarting the audio source. */
    fun reset() {
        onsets.clear()
        energyHistory.clear()
        lastOnset = Double.NEGATIVE_INFINITY
        rawBpmHistory.clear()
        displayBpm = 0.0
        justFiredBeat = false
        lastValidEstimateAt = Double.NEGATIVE_INFINITY
    }

    /** Current chunk RMS energy. Exposed for the UI input-meter. */
    fun currentEnergy(): Double = energyHistory.lastOrNull()?.energy ?: 0.0
}

private fun rms(samples: FloatArray): Double {
    var sum = 0.0
    for (s in samples) sum += s.toDouble() * s.toDouble()
    return sqrt(sum / samples.size)
}
