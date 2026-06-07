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
 * Kick-isolating band-pass, tuned to get **one onset per beat** on a
 * phone mic — the key to a correct (non-doubled) tempo.
 *
 * The cardinal rule for avoiding octave-doubling: keep the **upper
 * corner below ~150 Hz**, the snare's body. If the snare (backbeat) or
 * hi-hats leak in, the detector fires ~2-4 onsets per beat, the median
 * inter-onset interval halves, and the reported BPM doubles. So we band
 * to roughly **50-120 Hz** — only the kick lives there.
 *
 * Phone MEMS mics roll off below ~100 Hz, so we don't sit at the kick
 * fundamental (~40-60 Hz) where the mic has thrown the energy away;
 * instead the **low-pass has a resonant Q≈2** peaking near 110-120 Hz,
 * lifting the part of the kick that survives the rolloff knee while the
 * skirt still rejects the snare. The energy-onset detector uses a
 * *relative* (moving-average ratio) threshold, so even an attenuated
 * kick still reads as a clear spike. (Q is kept ≤ ~3 so the filter
 * doesn't ring and smear transient timing → IOI precision.)
 *
 * Stateful — one instance per audio stream.
 */
class KickBandpass(sampleRate: Int) {
    private val hp = Biquad.highpass(sampleRate, 50.0, 1.0 / sqrt(2.0))
    private val lp = Biquad.lowpass(sampleRate, 120.0, 2.0)

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
