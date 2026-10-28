package com.fourgeailabs.prismtune

import androidx.compose.ui.graphics.Color
import com.fourgeailabs.prismtune.ui.PrismPalette
import com.fourgeailabs.prismtune.ui.decodePalette
import com.fourgeailabs.prismtune.ui.encodePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeptPaletteTest {
    private val sample = PrismPalette(
        "Kept", Color(0xFF123456), Color(0xFF0F2A44),
        Color(0xFF14100C), Color(0xFF1E1913), Color(0xFFF5EFE6), Color(0xFFD8CFC0), false,
    )

    @Test fun encodeDecodeRoundTripPreservesEveryField() {
        val decoded = decodePalette(encodePalette(sample))
        assertEquals(sample, decoded)
    }

    @Test fun decodeRejectsGarbageInsteadOfCrashing() {
        assertNull(decodePalette(null))
        assertNull(decodePalette(""))
        assertNull(decodePalette("not-a-palette"))
        assertNull(decodePalette("Kept|red|blue"))
        assertNull(decodePalette("Kept|zzzzzzzz|00000000|00000000|00000000|00000000|00000000|false"))
        assertNull(decodePalette("Kept|FF123456|FF0F2A44|FF14100C|FF1E1913|FFF5EFE6|FFD8CFC0|maybe"))
    }

    @Test fun decodeKeepsLightFlag() {
        val light = sample.copy(name = "Kept", isLight = true)
        assertEquals(true, decodePalette(encodePalette(light))?.isLight)
    }
}
