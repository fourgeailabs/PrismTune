package com.fourgeailabs.prismtune

import androidx.compose.ui.graphics.Color
import com.fourgeailabs.prismtune.ui.PrismAccentVisible
import com.fourgeailabs.prismtune.ui.PrismPaletteState
import com.fourgeailabs.prismtune.ui.paletteByName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleAccentTest {
    @Test fun darkAccentOnDarkBecomesWhite() {
        // Curtis blue accent on a dark background must not stay dark blue.
        val darkBlue = Color(0xFF1A2A6B)
        PrismPaletteState.current = paletteByName("Amber").copy(accent = darkBlue)
        assertEquals(Color.White, PrismAccentVisible)
        PrismPaletteState.current = paletteByName("Amber")
    }

    @Test fun brightAccentOnDarkStays() {
        PrismPaletteState.current = paletteByName("Amber") // amber, luminance high
        assertEquals(Color(0xFFFFB020), PrismAccentVisible)
    }

    @Test fun offIconIsLightOnDark() {
        PrismPaletteState.current = paletteByName("Amber")
        val off = com.fourgeailabs.prismtune.ui.PrismOffIcon
        val lum = 0.2126f * off.red + 0.7152f * off.green + 0.0722f * off.blue
        assertTrue("off icon must be light on dark, lum=$lum", lum > 0.7f)
    }
}
