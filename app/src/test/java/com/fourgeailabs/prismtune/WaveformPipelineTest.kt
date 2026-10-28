package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.playback.LiveWaveformProcessor
import com.fourgeailabs.prismtune.playback.WaveformEnvelope
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI

class WaveformPipelineTest {
    @Test fun spectrumIsNotAFlatBlock() {
        // Feed a real mixed tone (bass + mids + treble) through the
        // actual processor, as ExoPlayer would, and check the
        // resulting spectrum is NOT all bars equal — the solid
        // block shape from Eric's 2026-10-27 video.
        val proc = LiveWaveformProcessor(WaveformEnvelope())
        val fmt = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        proc.configure(fmt)
        // 4096 stereo frames: bass 60Hz + mids 800Hz + treble 8kHz
        val shorts = ShortArray(4096 * 2)
        for (i in 0 until 4096) {
            val v = 0.5 * sin(2 * PI * 60 * i / 48000) +
                    0.3 * sin(2 * PI * 800 * i / 48000) +
                    0.2 * sin(2 * PI * 8000 * i / 48000)
            val s = (v * 20000).toInt().toShort()
            shorts[i * 2] = s; shorts[i * 2 + 1] = s
        }
        val buf = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(shorts)
        repeat(4) { proc.queueInput(buf.duplicate()); proc.getOutput() }
        val levels = proc.envelope.snapshot()
        assertTrue("spectrum must have 96 bars", levels.size == 96)
        val max = levels.maxOrNull() ?: 0f
        val min = levels.minOrNull() ?: 0f
        // A real spectrum varies across frequency; a flat block
        // would have max-min near zero.
        assertTrue("spectrum must vary across bars, got max=$max min=$min", max - min > 0.2f)
        assertTrue("spectrum must not be all near-full", levels.count { it > 0.9f } < 48)
    }
}
