package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.playback.WaveformEnvelope
import com.fourgeailabs.prismtune.playback.fftMagnitudes
import com.fourgeailabs.prismtune.playback.pcm16Rms
import com.fourgeailabs.prismtune.playback.spectrumBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 live waveform: amplitude computation only, no audio device.
 * Silence must be flat, loud PCM high, quiet PCM low, and pausing
 * must decay bars to flat — never fake dancing bars.
 */
class WaveformTest {

    @Test
    fun silenceIn_flatBarsOut() {
        val env = WaveformEnvelope(barCount = 12)
        val levels = env.update(pcm16Rms(ShortArray(1024)))
        assertEquals(12, levels.size)
        assertTrue(levels.all { it == 0f })
    }

    @Test
    fun loudPcm_highBars() {
        val env = WaveformEnvelope(barCount = 12)
        val loud = ShortArray(1024) { 30000 }
        repeat(12) { env.update(pcm16Rms(loud)) }
        val levels = env.snapshot()
        assertTrue("loud bars should be high, got $levels", levels.all { it > 0.8f })
    }

    @Test
    fun quietPcm_lowBars() {
        val env = WaveformEnvelope(barCount = 12)
        val quiet = ShortArray(1024) { 200 }
        repeat(12) { env.update(pcm16Rms(quiet)) }
        val levels = env.snapshot()
        assertTrue("quiet bars should be low, got $levels", levels.all { it < 0.05f })
        assertTrue("quiet bars should be non-zero, got $levels", levels.any { it > 0f })
    }

    @Test
    fun loudThenSilence_decaysToFlat() {
        val env = WaveformEnvelope(barCount = 12)
        val loud = ShortArray(1024) { 30000 }
        repeat(12) { env.update(pcm16Rms(loud)) }
        repeat(60) { env.idleStep() }
        assertTrue(env.snapshot().all { it == 0f })
    }

    // 1.3.6: bass vs treble must actually separate in the
    // spectrum, not just be one loudness number.
    private fun sineBars(freqHz: Double, sampleRate: Int = 44100): List<Float> {
        val window = FloatArray(1024) { i ->
            (0.8 * kotlin.math.sin(2.0 * Math.PI * freqHz * i / sampleRate)).toFloat()
        }
        return spectrumBars(fftMagnitudes(window), sampleRate, 96)
    }

    @Test
    fun bassSine_spikesLeftNotRight() {
        val bars = sineBars(100.0)
        val bass = bars.take(32).average()
        val treble = bars.takeLast(32).average()
        assertTrue("100 Hz bass should spike left, bass=$bass treble=$treble", bass > treble * 3)
    }

    @Test
    fun trebleSine_spikesRightNotLeft() {
        val bars = sineBars(8000.0)
        val bass = bars.take(32).average()
        val treble = bars.takeLast(32).average()
        assertTrue("8 kHz treble should spike right, bass=$bass treble=$treble", treble > bass * 3)
    }

    @Test
    fun silenceSpectrum_flat() {
        val bars = spectrumBars(fftMagnitudes(FloatArray(1024)), 44100, 96)
        assertTrue(bars.all { it == 0f })
    }

    @Test
    fun rmsScalesWithAmplitude() {
        val quiet = pcm16Rms(ShortArray(1024) { 1000 })
        val loud = pcm16Rms(ShortArray(1024) { 20000 })
        assertTrue(loud > quiet * 10)
    }
}
