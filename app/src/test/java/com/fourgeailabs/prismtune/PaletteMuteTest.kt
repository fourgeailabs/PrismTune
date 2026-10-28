package com.fourgeailabs.prismtune

import androidx.compose.ui.graphics.toArgb
import com.fourgeailabs.prismtune.playback.shouldPauseOnMute
import com.fourgeailabs.prismtune.playback.shouldResumeOnUnmute
import com.fourgeailabs.prismtune.ui.dynamicAccentForTrack
import com.fourgeailabs.prismtune.ui.paletteByName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteMuteTest {
    @Test fun muteToggleResumesOnlyIfMutePausedIt() {
        assertTrue(shouldResumeOnUnmute(true, 5, 0))
        assertFalse(shouldResumeOnUnmute(false, 5, 0))
        assertFalse(shouldResumeOnUnmute(true, 5, 3))
        assertFalse(shouldResumeOnUnmute(true, 0, 0))
        assertTrue(shouldPauseOnMute(true, 0, 5))
    }

    @Test fun palettesAndDynamicAccentAreDeterministic() {
        assertEquals("Cream", paletteByName("Cream").name)
        assertEquals("Amber", paletteByName("Nope").name)
        val a = dynamicAccentForTrack("Song", "Artist")
        val b = dynamicAccentForTrack("Song", "Artist")
        assertEquals(a, b)
        // Not black, white, or grey: red/green/blue channels differ.
        val argb = a.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val bl = argb and 0xFF
        assertFalse(r == g && g == bl)
    }
}

class AutoSkipTest {
    @org.junit.Test fun autoSkipUpToFiveThenStops() {
        org.junit.Assert.assertTrue(com.fourgeailabs.prismtune.playback.shouldAutoSkip(0, true))
        org.junit.Assert.assertTrue(com.fourgeailabs.prismtune.playback.shouldAutoSkip(4, true))
        org.junit.Assert.assertFalse(com.fourgeailabs.prismtune.playback.shouldAutoSkip(5, true))
        org.junit.Assert.assertFalse(com.fourgeailabs.prismtune.playback.shouldAutoSkip(0, false))
    }
}
