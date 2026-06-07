package io.celox.bpbel

import io.celox.bpbel.audio.BpmAnalyzer
import io.celox.bpbel.audio.KickBandpass
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * End-to-end test of the real audio chain — `KickBandpass` → `BpmAnalyzer`
 * — on a **synthetic signal shaped like what a phone mic actually
 * captures**: a kick whose energy sits at 90 Hz (inside the mic's
 * responsive range), plus a loud high-frequency tone (snare/vocal-ish)
 * that the band-pass must reject.
 *
 * This is the regression guard for the bug where the old 30-100 Hz band
 * detected nothing on phones (mic rolls off below ~120 Hz): with the
 * 60-200 Hz band the beat is recovered and the tempo locks.
 */
class AudioChainTest {

    private val sampleRate = 44_100
    private val frame = 1024
    private val frameMs = frame * 1000.0 / sampleRate

    /** Build `seconds` of audio: a decaying 90 Hz kick on every beat,
     *  plus a continuous 2 kHz tone the band-pass should remove. */
    private fun synth(bpm: Double, seconds: Double, kickHz: Double): FloatArray {
        val total = (seconds * sampleRate).toInt()
        val out = FloatArray(total)
        val beatSamples = (60.0 / bpm * sampleRate).toInt()
        for (i in 0 until total) {
            val t = i.toDouble() / sampleRate
            // High-frequency distractor (must be filtered out).
            var s = 0.35 * sin(2 * PI * 2000.0 * t)
            // Kick: decaying sinusoid at the start of each beat.
            val posInBeat = i % beatSamples
            val decay = exp(-posInBeat.toDouble() / sampleRate / 0.06) // ~60 ms tail
            s += 0.9 * decay * sin(2 * PI * kickHz * (posInBeat.toDouble() / sampleRate))
            out[i] = s.toFloat()
        }
        return out
    }

    private fun detect(signal: FloatArray): Double {
        val bandpass = KickBandpass(sampleRate)
        val analyzer = BpmAnalyzer()
        var idx = 0
        var frameIdx = 0
        while (idx + frame <= signal.size) {
            val chunk = signal.copyOfRange(idx, idx + frame)
            val tMs = frameIdx * frameMs
            analyzer.push(bandpass.process(chunk), tMs)
            analyzer.estimate(tMs)
            idx += frame
            frameIdx++
        }
        return analyzer.estimate(frameIdx * frameMs).bpm
    }

    @Test
    fun locksOnto120FromA90HzKickThroughTheBandpass() {
        val bpm = detect(synth(bpm = 120.0, seconds = 12.0, kickHz = 90.0))
        assertTrue("expected ~120 BPM, got $bpm", bpm in 112.0..128.0)
    }

    @Test
    fun locksOnto128FromA75HzKick() {
        // House/techno: 128 BPM, kick fundamental energy near 75 Hz.
        val bpm = detect(synth(bpm = 128.0, seconds = 12.0, kickHz = 75.0))
        assertTrue("expected ~128 BPM, got $bpm", bpm in 120.0..136.0)
    }
}
