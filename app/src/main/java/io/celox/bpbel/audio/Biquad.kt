package io.celox.bpbel.audio

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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
 * Kick-drum band-pass — an **exact** reproduction of the audio graph in
 * the `inspector-rust` reference detector (`BpmDetector.tsx`):
 *
 *   highpass 30 Hz (Q 0.7) → lowpass 100 Hz (Q 1.5)
 *
 * The 30 Hz high-pass cuts room rumble / BT-speaker thump; the 100 Hz
 * low-pass isolates the kick body and drops vocals / snare attack out of
 * the energy signal (the reference narrowed this from 150 → 100 Hz
 * precisely because high-end bleed "was making the BPM jump"). Keeping
 * the upper corner at 100 Hz is what yields ~one onset per beat — and
 * therefore the correct, non-doubled tempo.
 *
 * **Web Audio Q semantics:** for `lowpass`/`highpass`, the Web Audio
 * `BiquadFilterNode.Q` is interpreted **in dB**, so the linear Q used in
 * the RBJ cookbook formula is `10^(Q_dB / 20)`. We convert here so the
 * filter response is bit-for-bit equivalent to the browser's — the same
 * `0.7`/`1.5` the reference passes to Web Audio.
 *
 * Stateful — one instance per audio stream.
 */
class KickBandpass(sampleRate: Int) {
    private val hp = Biquad.highpass(sampleRate, 30.0, qFromDb(0.7))
    private val lp = Biquad.lowpass(sampleRate, 100.0, qFromDb(1.5))

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

/** Web Audio interprets the `Q` of lowpass/highpass filters in dB; the
 *  linear quality factor used in the RBJ formula is `10^(Q_dB / 20)`. */
private fun qFromDb(qDb: Double): Double = 10.0.pow(qDb / 20.0)
