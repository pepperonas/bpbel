package io.celox.bpbel.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transposed-Direct-Form-II biquad, coefficients per the Audio EQ
 * Cookbook (Robert Bristow-Johnson). Mirrors the Web Audio
 * `BiquadFilterNode` used by the original BPM detector so the onset
 * energy seen by [BpmAnalyzer] matches the reference implementation.
 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    /** Process one sample. */
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        fun highpass(sampleRate: Int, freq: Double, q: Double): Biquad {
            val w0 = 2 * Math.PI * freq / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val b0 = (1 + cosW0) / 2 / a0
            val b1 = -(1 + cosW0) / a0
            val b2 = (1 + cosW0) / 2 / a0
            val a1 = (-2 * cosW0) / a0
            val a2 = (1 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }

        fun lowpass(sampleRate: Int, freq: Double, q: Double): Biquad {
            val w0 = 2 * Math.PI * freq / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val b0 = (1 - cosW0) / 2 / a0
            val b1 = (1 - cosW0) / a0
            val b2 = (1 - cosW0) / 2 / a0
            val a1 = (-2 * cosW0) / a0
            val a2 = (1 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }
    }
}

/**
 * Beat band-pass tuned for **phone microphones**.
 *
 * The reference (laptop/Web-Audio) detector used 30-100 Hz to isolate
 * the kick fundamental. On Android that band is useless: MEMS phone
 * mics roll off hard below ~100-150 Hz, so almost no energy survives in
 * 30-100 Hz → no onsets → BPM never locks (while full-band loudness
 * still works fine — exactly the "dB moves, BPM stuck" symptom).
 *
 * We therefore use **60-200 Hz**: the kick's upper harmonics + bass
 * fundamentals, which sit inside the phone mic's responsive range and
 * still carry the periodic beat, while rejecting most vocal/snare/
 * cymbal energy that would muddy the tempo. Butterworth-flat Q so the
 * whole band contributes energy. Stateful — one instance per stream.
 */
class KickBandpass(sampleRate: Int) {
    private val hp = Biquad.highpass(sampleRate, 60.0, 1.0 / sqrt(2.0))
    private val lp = Biquad.lowpass(sampleRate, 200.0, 1.0 / sqrt(2.0))

    /** Filter a block in place into a fresh array. */
    fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            out[i] = lp.process(hp.process(input[i].toDouble())).toFloat()
        }
        return out
    }

    fun reset() {
        hp.reset()
        lp.reset()
    }
}
