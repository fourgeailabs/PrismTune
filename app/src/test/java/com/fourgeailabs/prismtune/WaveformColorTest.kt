package com.fourgeailabs.prismtune

import androidx.compose.ui.graphics.Color
import com.fourgeailabs.prismtune.ui.progressColor
import com.fourgeailabs.prismtune.ui.waveformBarColor
import com.fourgeailabs.prismtune.ui.waveformBaseColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformColorTest {
    @Test fun baseIsWhiteOnDarkBlackOnLight() {
        assertEquals(Color.White, waveformBaseColor(false))
        assertEquals(Color.Black, waveformBaseColor(true))
    }

    @Test fun unplayedBarIsAlwaysBase() {
        // Bar at 90% through, song only 20% through: unplayed, so base.
        // Eric 2026-10-27 05:38: white on red "does look clean" — keep it.
        assertEquals(Color.White, waveformBarColor(0.9f, 0.2f, false, Color(0xFF1E1913)))
        assertEquals(Color.Black, waveformBarColor(0.9f, 0.2f, true, Color.White))
    }

    @Test fun playedBarIsHarmoniousTintImmediately() {
        // 1.5.5: played bars are the SAME hue family as the
        // background, lightened — never the complement.
        // Deep red background -> played is reddish (red is
        // still the dominant channel), brighter than the
        // background, and NOT white and NOT cyan/blue.
        val bg = Color(0xFF8B0000) // dark red, like Eric's photo
        val played = waveformBarColor(0.0f, 0.05f, false, bg)
        assertTrue("played on red must stay reddish, got $played", played.red > played.green && played.red > played.blue)
        assertTrue("played must be brighter than dark red bg, got $played", played.red > bg.red)
        assertTrue("played must not be white, got $played", played.red < 1f || played.green < 0.9f)
        assertEquals(played, waveformBarColor(0.5f, 0.9f, false, bg))
        // Teal stays teal, brown stays brown.
        val teal = progressColor(Color(0xFF004D40), false)
        assertTrue("played on teal must stay teal/green, got $teal", teal.green > teal.red)
        val brown = progressColor(Color(0xFF3E2723), false)
        assertTrue("played on brown must stay warm, got $brown", brown.red >= brown.blue)
    }

    @Test fun playedBarStaysVisibleOnGreyBackground() {
        // Charcoal has no hue: played is a mid-light grey —
        // clearly darker than the white unplayed bars, and
        // clearly lighter than the charcoal background.
        val bg = Color(0xFF212121)
        val played = progressColor(bg, false)
        val max = maxOf(played.red, played.green, played.blue)
        assertTrue("grey played must be mid-light, got $played", max in 0.55f..0.90f)
        assertTrue("grey played must be lighter than bg, got $played", max > 0.13f)
    }

    @Test fun playedBarIsDarkerShadeOnLightBackground() {
        // Light background: played is a darker shade of
        // the same hue, not black and not the light bg.
        val bg = Color(0xFFFFCDD2) // light red
        val played = progressColor(bg, true)
        assertTrue("played on light red must stay reddish, got $played", played.red > played.blue)
        assertTrue("played must be darker than light bg, got $played", played.red < bg.red)
        assertTrue("played must not be black, got $played", played.red > 0.15f)
    }
}
