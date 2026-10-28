package com.fourgeailabs.prismtune

import androidx.compose.ui.graphics.toArgb
import com.fourgeailabs.prismtune.data.dominantColorFromPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    @Test fun dominantColorIsFromArtworkNotHash() {
        // Mostly red pixels, a few grey — dominant must be red, not grey.
        val pixels = IntArray(100) { 0xFFFF0000.toInt() } + IntArray(10) { 0xFF808080.toInt() }
        val color = dominantColorFromPixels(pixels)
        assertNotNull(color)
        val argb = color!!.toArgb()
        assertTrue(((argb shr 16) and 0xFF) > 200)
        assertTrue(((argb shr 8) and 0xFF) < 50)
    }

    @Test fun emptyPixelsGivesNull() {
        assertEquals(null, dominantColorFromPixels(intArrayOf()))
    }
}
