package io.celox.bpbel

import io.celox.bpbel.audio.BpmAnalyzer
import io.celox.bpbel.audio.BpmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported 1:1 from `inspector-rust/core/frontend/src/lib/bpm.test.ts`
 * to prove the Kotlin port matches the reference implementation.
 */
class BpmAnalyzerTest {

    /** Constant-value chunk whose RMS equals `rms`. */
    private fun chunk(rms: Float, n: Int = 128) = FloatArray(n) { rms }

    private data class Sim(val analyzer: BpmAnalyzer, val finalEstimate: Double, val confidence: Double)

    /** Pump `seconds` of `bpm` beats: quiet baseline + loud beats. */
    private fun simulateBeats(
        bpm: Double,
        seconds: Double,
        startMs: Double = 0.0,
        baseline: Float = 0.02f,
        beatEnergy: Float = 0.5f,
    ): Sim {
        val a = BpmAnalyzer()
        val beatIntervalMs = 60000.0 / bpm
        val chunkIntervalMs = 10.0
        var nextBeatAt = startMs
        var t = startMs
        while (t < startMs + seconds * 1000) {
            val isBeat = t >= nextBeatAt && t < nextBeatAt + chunkIntervalMs
            if (isBeat) nextBeatAt += beatIntervalMs
            a.push(chunk(if (isBeat) beatEnergy else baseline), t)
            t += chunkIntervalMs
        }
        val est = a.estimate(startMs + seconds * 1000)
        return Sim(a, est.bpm, est.confidence)
    }

    @Test
    fun zeroBeforeAnyAudio() {
        val est = BpmAnalyzer().estimate(0.0)
        assertEquals(0.0, est.bpm, 0.0)
        assertEquals(0.0, est.confidence, 0.0)
        assertTrue(!est.beatJustFired)
    }

    @Test
    fun zeroWithOnlyACoupleOfOnsets() {
        val s = simulateBeats(120.0, 1.2)
        assertEquals(0.0, s.finalEstimate, 0.0)
    }

    @Test
    fun locksOnto120() {
        val s = simulateBeats(120.0, 10.0)
        assertTrue("bpm=${s.finalEstimate}", s.finalEstimate > 115 && s.finalEstimate < 125)
        assertTrue("conf=${s.confidence}", s.confidence > 0.7)
    }

    @Test
    fun locksOnto90() {
        val s = simulateBeats(90.0, 10.0)
        assertTrue("bpm=${s.finalEstimate}", s.finalEstimate > 85 && s.finalEstimate < 95)
    }

    @Test
    fun locksOnto175() {
        val s = simulateBeats(175.0, 10.0)
        assertTrue("bpm=${s.finalEstimate}", s.finalEstimate > 170 && s.finalEstimate < 180)
    }

    @Test
    fun octaveCorrectsHalfTime() {
        // 50 BPM < BPM_MIN → doubles to ~100.
        val s = simulateBeats(50.0, 10.0)
        assertTrue("bpm=${s.finalEstimate}", s.finalEstimate > 95 && s.finalEstimate < 105)
    }

    @Test
    fun octaveCorrectsDoubleTime() {
        // 240 BPM > BPM_MAX → halves to ~120.
        val s = simulateBeats(240.0, 10.0)
        assertTrue("bpm=${s.finalEstimate}", s.finalEstimate > 115 && s.finalEstimate < 125)
    }

    @Test
    fun lowConfidenceForIrregularOnsets() {
        val a = BpmAnalyzer()
        var t = 0.0
        while (t < 500) { a.push(chunk(0.02f), t); t += 10 }
        doubleArrayOf(600.0, 850.0, 1300.0, 1450.0, 1900.0, 2050.0, 2800.0, 3100.0).forEach {
            a.push(chunk(0.5f), it)
            a.push(chunk(0.02f), it + 10)
        }
        assertTrue(a.estimate(3500.0).confidence < 0.7)
    }

    @Test
    fun beatJustFiredOncePerBeat() {
        val a = BpmAnalyzer()
        var t = 0.0
        while (t < 3000) { a.push(chunk(0.02f), t); t += 10 }
        val beats = setOf(3000.0, 3500.0, 4000.0, 4500.0, 5000.0)
        var fires = 0
        t = 3000.0
        while (t <= 5000) {
            a.push(chunk(if (t in beats) 0.5f else 0.02f), t)
            if (a.estimate(t).beatJustFired) fires++
            t += 10
        }
        assertEquals(beats.size, fires)
    }

    @Test
    fun respectsRefractoryPeriod() {
        val a = BpmAnalyzer()
        var t = 0.0
        while (t < 3000) { a.push(chunk(0.02f), t); t += 10 }
        a.push(chunk(0.6f), 3050.0)
        a.push(chunk(0.6f), 3150.0)
        var fires = 0
        if (a.estimate(3050.0).beatJustFired) fires++
        if (a.estimate(3150.0).beatJustFired) fires++
        assertTrue(fires <= 1)
    }

    @Test
    fun resetClearsState() {
        val s = simulateBeats(120.0, 8.0)
        assertTrue(s.analyzer.estimate(8000.0).bpm > 0)
        s.analyzer.reset()
        assertEquals(0.0, s.analyzer.estimate(8000.0).bpm, 0.0)
        assertEquals(0.0, s.analyzer.currentEnergy(), 0.0)
    }

    @Test
    fun currentEnergyReflectsLastChunk() {
        val a = BpmAnalyzer()
        a.push(chunk(0.3f), 0.0)
        assertEquals(0.3, a.currentEnergy(), 1e-4)
        a.push(chunk(0.05f), 100.0)
        assertEquals(0.05, a.currentEnergy(), 1e-4)
    }

    @Test
    fun keepsLastBpmThroughBriefDrought() {
        val s = simulateBeats(120.0, 10.0)
        assertTrue(s.analyzer.estimate(10_000.0).bpm > 115)
        var t = 10_010.0
        while (t <= 13_000) { s.analyzer.push(chunk(0.02f), t); t += 10 }
        val still = s.analyzer.estimate(13_000.0).bpm
        assertTrue("bpm=$still", still in 115.0..125.0)
    }

    @Test
    fun decaysToZeroAfterSustainedSilence() {
        val s = simulateBeats(120.0, 10.0)
        assertTrue(s.analyzer.estimate(10_000.0).bpm > 115)
        var t = 10_010.0
        while (t <= 16_000) { s.analyzer.push(chunk(0.02f), t); t += 10 }
        assertEquals(0.0, s.analyzer.estimate(16_000.0).bpm, 0.0)
    }

    @Test
    fun baselineGateSuppressesStartupBurst() {
        val a = BpmAnalyzer()
        var t = 0.0
        while (t < 1000) { a.push(chunk(0.005f), t); t += 10 }
        while (t < 2500) { a.push(chunk(0.5f), t); t += 10 }
        val early = a.estimate(2500.0)
        assertEquals(0.0, early.bpm, 0.0)
        assertEquals(0.0, early.confidence, 0.0)
    }

    @Test
    fun octaveSnapKeeps120() {
        val s = simulateBeats(120.0, 10.0)
        assertTrue(s.analyzer.estimate(10_000.0).bpm in 115.0..125.0)
        var t = 10_500.0
        while (t <= 12_500) {
            s.analyzer.push(chunk(0.02f), t - 50)
            s.analyzer.push(chunk(0.6f), t)
            t += 250
        }
        val after = s.analyzer.estimate(12_500.0).bpm
        assertTrue("bpm=$after", after in 110.0..140.0)
    }

    @Test
    fun superFluxGateDoesNotRetriggerOnASustainedNote() {
        // A single long, *sustained* loud note (energy stays elevated for
        // well over a second). Without the SuperFlux rising-edge gate the
        // detector would re-fire every refractory period (~3 ghost onsets/s)
        // and invent a bogus tempo. The gate must let only the ONE attack
        // through, since the sustain never beats its own recent peak.
        val a = BpmAnalyzer()
        var t = 0.0
        while (t < 3000) { a.push(chunk(0.02f), t); t += 10 }   // calibrate baseline
        var fires = 0
        while (t < 4800) {                                        // 1.8 s held note
            a.push(chunk(0.5f), t)
            if (a.estimate(t).beatJustFired) fires++
            t += 10
        }
        assertEquals(1, fires)
    }

    @Test
    fun loudnessGateBlocksOnsetsWhenClosed() {
        // With the loudness gate shut (allow=false) even loud, perfectly
        // periodic energy must produce no onsets — this is what stops a
        // quiet room / mic hum from faking a BPM.
        val a = BpmAnalyzer()
        var t = 0.0
        while (t < 3000) { a.push(chunk(0.02f), t, allow = true); t += 10 }
        var fires = 0
        val beats = setOf(3000.0, 3500.0, 4000.0, 4500.0, 5000.0)
        t = 3000.0
        while (t <= 5000) {
            a.push(chunk(if (t in beats) 0.5f else 0.02f), t, allow = false)
            if (a.estimate(t).beatJustFired) fires++
            t += 10
        }
        assertEquals(0, fires)
        assertEquals(0.0, a.estimate(5000.0).bpm, 0.0)
    }

    @Test
    fun configRangeIsSensible() {
        assertEquals(60.0, BpmConfig.BPM_MIN, 0.0)
        assertEquals(200.0, BpmConfig.BPM_MAX, 0.0)
        assertEquals(200.0, 60000.0 / BpmConfig.ONSET_REFRACTORY_MS, 0.0)
    }
}
