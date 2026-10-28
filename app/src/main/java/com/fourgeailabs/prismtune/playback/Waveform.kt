package com.fourgeailabs.prismtune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Live waveform — REAL audio only, no fake data.
 *
 * 1.1.0-1.3.2: bars were the recent RMS envelope of the
 * PCM actually being played. That is overall loudness only
 * — it cannot show bass vs mids vs treble, because one RMS
 * number per buffer has no frequency information at all.
 * 2026-10-26 23:32: "the wave form isn't working
 * right. It needs to reflect actual live treble base and
 * mids correctly."
 *
 * 1.3.6: bars are now a LIVE SPECTRUM, from a real FFT
 * over the PCM actually being played, in the AudioProcessor
 * tap. 1024-sample window, Hann window, radix-2 FFT, then
 * 96 bars log-spaced from 20 Hz to 16 kHz: left bars are
 * bass (20-250 Hz), middle bars are mids (250-4000 Hz),
 * right bars are treble (4000-16000 Hz). A bass kick
 * spikes the left, a hi-hat spikes the right, vocals sit
 * in the middle — exactly what the user's request asked for. Silence is
 * flat, pause decays flat, never invented dancing bars.
 * If no PCM reaches the processor, or the format is not
 * 16-bit PCM, the spectrum decays to flat, honestly.
 */

/** RMS of 16-bit PCM samples, normalised to 0..1. Pure, unit-testable. */
fun pcm16Rms(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) {
        val v = s / 32768.0
        sum += v * v
    }
    return sqrt(sum / samples.size).toFloat().coerceIn(0f, 1f)
}

/** Iterative radix-2 FFT magnitudes for a 1024-sample window. Pure. */
fun fftMagnitudes(window: FloatArray): FloatArray {
    val n = window.size
    val re = FloatArray(n)
    val im = FloatArray(n)
    // Hann window, to reduce spectral leakage.
    for (i in window.indices) {
        val w = (0.5f * (1f - cos(2f * PI.toFloat() * i / (n - 1)))).toFloat()
        re[i] = window[i] * w
    }
    // Bit-reversal permutation.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tmp = re[i]; re[i] = re[j]; re[j] = tmp
        }
    }
    // Cooley-Tukey butterflies.
    var len = 2
    while (len <= n) {
        val angle = (-2f * PI.toFloat() / len).toFloat()
        val wr = cos(angle); val wi = kotlin.math.sin(angle)
        var i = 0
        while (i < n) {
            var wrk = 1f; var wik = 0f
            for (k in 0 until len / 2) {
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wrk - im[i + k + len / 2] * wik
                val vi = re[i + k + len / 2] * wik + im[i + k + len / 2] * wrk
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                val nextWr = wrk * wr - wik * wi
                wik = wrk * wi + wik * wr
                wrk = nextWr
            }
            i += len
        }
        len *= 2
    }
    val out = FloatArray(n / 2)
    for (i in out.indices) {
        out[i] = sqrt(re[i] * re[i] + im[i] * im[i]) / n
    }
    return out
}

/**
 * Map FFT magnitudes to 96 log-spaced bars, 20 Hz to 16 kHz.
 * Bar 0 is bass, the middle is mids, the last bars are treble.
 * Each bar averages the FFT bins in its log range, with a
 * square-root + gain stage so quiet passages still read.
 * Silence gives all zeros. Pure, unit-testable.
 */
fun spectrumBars(magnitudes: FloatArray, sampleRate: Int, barCount: Int = 96): List<Float> {
    if (magnitudes.isEmpty() || sampleRate <= 0) return List(barCount) { 0f }
    val nyquist = sampleRate / 2f
    val maxFreq = minOf(16000f, nyquist)
    val minFreq = 20f
    val logMin = ln(minFreq); val logMax = ln(maxFreq)
    val binHz = sampleRate.toFloat() / (magnitudes.size * 2)
    return List(barCount) { bar ->
        val f0 = kotlin.math.exp(logMin + (logMax - logMin) * bar / barCount)
        val f1 = kotlin.math.exp(logMin + (logMax - logMin) * (bar + 1) / barCount)
        val b0 = (f0 / binHz).toInt().coerceIn(0, magnitudes.size - 1)
        val b1 = (f1 / binHz).toInt().coerceIn(b0 + 1, magnitudes.size)
        var sum = 0f; var count = 0
        for (b in b0 until b1) { sum += magnitudes[b]; count++ }
        val avg = if (count > 0) sum / count else 0f
        // sqrt + 8x gain, clamped — a sine at full scale lands
        // near full height, a quiet passage still reads.
        (sqrt(avg) * 8f).coerceIn(0f, 1f)
    }
}

/**
 * Smoothed spectrum envelope. [updateSpectrum] is one real
 * FFT frame from the PCM actually being played. Attack is
 * instant, decay gradual. [idleStep] decays to flat.
 * The old RMS [update] is kept for tests / fallback.
 */
class WaveformEnvelope(val barCount: Int = 96, private val decay: Float = 0.82f) {
    private val levels = FloatArray(barCount)
    private var smoothed = 0f

    fun update(rms: Float): List<Float> {
        val gained = (rms * 5f).coerceIn(0f, 1f)
        smoothed = if (gained >= smoothed) gained else smoothed * decay
        for (i in levels.indices) levels[i] = smoothed
        return snapshot()
    }

    fun updateSpectrum(bars: List<Float>): List<Float> {
        for (i in levels.indices) {
            val incoming = bars.getOrNull(i) ?: 0f
            levels[i] = if (incoming >= levels[i]) incoming else levels[i] * decay
            if (levels[i] < 0.01f) levels[i] = 0f
        }
        return snapshot()
    }

    fun idleStep(): List<Float> {
        smoothed *= decay
        if (smoothed < 0.01f) smoothed = 0f
        for (i in levels.indices) {
            levels[i] *= decay
            if (levels[i] < 0.01f) levels[i] = 0f
        }
        return snapshot()
    }

    fun snapshot(): List<Float> = levels.toList()

    fun reset() {
        levels.fill(0f)
        smoothed = 0f
    }
}

/**
 * Media3 [AudioProcessor] tapped into the player pipeline. It passes
 * audio through byte-identical and only *observes* it: it reads a
 * duplicate of each input buffer (the original buffer's position is
 * untouched, so downstream playback is unaffected) and feeds a
 * 1024-sample FFT frame into the spectrum envelope. Stereo is
 * downmixed to mono by averaging L/R. Non-16-bit-PCM formats are
 * left inactive, so the processor never blocks or alters playback
 * it cannot read — the UI then shows a flat idle line, honestly.
 */
class LiveWaveformProcessor(val envelope: WaveformEnvelope = WaveformEnvelope()) : AudioProcessor {
    private var active = false
    private var bigEndian = false
    private var sampleRate = 44100
    private var channelCount = 2
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false
    // 1.5.0 VIDEO FIX: rolling 1024-sample window. The old
    // code took mono.take(1024) — the FIRST 1024 samples
    // of every buffer — so if ExoPlayer delivered larger
    // buffers, most of the audio was ignored and every FFT
    // frame was the same leading edge. Now each buffer
    // appends to a rolling window and the FFT reads the
    // LATEST 1024 samples, so the spectrum actually moves
    // with the music instead of sitting at one shape.
    private val rolling = ArrayDeque<Float>()

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN
        ) {
            active = true
            bigEndian = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
            inputAudioFormat
        } else {
            active = false
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) {
            outputBuffer = inputBuffer
            return
        }
        val copy = inputBuffer.duplicate()
        copy.order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(copy.remaining() / 2)
        copy.asShortBuffer().get(shorts)
        // Downmix stereo to mono, take a 1024-sample window.
        val mono = FloatArray(shorts.size / channelCount) { i ->
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += shorts.getOrNull(i * channelCount + c)?.toFloat() ?: 0f
            }
            (sum / channelCount) / 32768f
        }
        for (v in mono) {
            rolling.addLast(v)
            if (rolling.size > 1024) rolling.removeFirst()
        }
        if (rolling.size >= 1024) {
            val window = rolling.toFloatArray()
            val magnitudes = fftMagnitudes(window)
            envelope.updateSpectrum(spectrumBars(magnitudes, sampleRate, envelope.barCount))
        } else if (mono.isNotEmpty()) {
            // 1.5.0: NEVER call update(rms) here — it sets
            // EVERY bar to the same smoothed RMS value, which
            // is exactly the solid-block shape in the user's
            // video. With too little audio to FFT, decay the
            // existing spectrum instead of flattening it.
            envelope.idleStep()
        }
        outputBuffer = inputBuffer
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = ended && outputBuffer == AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        ended = false
        rolling.clear()
    }

    override fun reset() {
        flush()
        active = false
        envelope.reset()
    }
}
