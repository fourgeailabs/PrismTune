package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.ConnectionPicker
import com.fourgeailabs.prismtune.data.EquinoxUsableArea
import com.fourgeailabs.prismtune.data.PlexConnection
import com.fourgeailabs.prismtune.data.PlexUrls
import com.fourgeailabs.prismtune.data.SafeAreaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexLogicTest {

    @Test
    fun safeAreaDefaultIsEightyPercent() {
        // Photo-calibrated 2026-10-25: temp at ~89% across, clock at ~83%,
        // so 80% leaves a margin before the gauge zone.
        assertEquals(0.80f, SafeAreaConfig.DEFAULT_FRACTION, 0.001f)
    }

    @Test
    fun safeAreaClampsTo55To85() {
        assertEquals(0.55f, SafeAreaConfig.clamp(0.10f), 0.001f)
        assertEquals(0.85f, SafeAreaConfig.clamp(0.99f), 0.001f)
        assertEquals(0.80f, SafeAreaConfig.clamp(0.80f), 0.001f)
    }

    @Test
    fun topRightZoneIsNeverInteractive() {
        // The Equinox EV temperature gauge lives top-right: x near 1.0 must
        // never be inside the interactive zone, at any allowed fraction.
        assertFalse(SafeAreaConfig.isInteractiveX(0.95f, 0.85f))
        assertFalse(SafeAreaConfig.isInteractiveX(0.89f, 0.85f))
        assertTrue(SafeAreaConfig.isInteractiveX(0.50f, 0.80f))
    }

    @Test
    fun gaugeQuadrantIsDetected() {
        // Temp point from the photo: ~89% across, ~5% down from the top.
        assertTrue(SafeAreaConfig.isGaugeZone(0.89f, 0.05f))
        assertTrue(SafeAreaConfig.isGaugeZone(0.83f, 0.05f))
        assertFalse(SafeAreaConfig.isGaugeZone(0.50f, 0.05f))
        assertFalse(SafeAreaConfig.isGaugeZone(0.89f, 0.50f))
        assertFalse(SafeAreaConfig.isInteractivePoint(0.89f, 0.05f, 0.85f))
    }

    @Test
    fun connectionPickerPrefersLocal() {
        val remote = PlexConnection("https://remote.example:32400", local = false, protocol = "https")
        val local = PlexConnection("http://192.168.1.10:32400", local = true)
        assertEquals(local, ConnectionPicker.pick(listOf(remote, local)))
        assertEquals(remote, ConnectionPicker.pick(listOf(remote)))
        assertNull(ConnectionPicker.pick(emptyList()))
    }

    @Test
    fun streamUrlIncludesPartIdAndToken() {
        val url = PlexUrls.streamUrl("http://192.168.1.10:32400", 12345L, "tok123")
        assertTrue(url.contains("/library/parts/12345/file"))
        assertTrue(url.contains("X-Plex-Token=tok123"))
    }

    @Test
    fun linkUrlEncodesCode() {
        assertEquals("https://plex.tv/link?code=ABCD", PlexUrls.linkUrlForCode("ABCD"))
    }

    @Test
    fun equinoxUsableAreaMathsMatchMeasuredFigures() {
        // Final tape-measured figures: 13.125 x 4.875 in.
        assertEquals(2.692, EquinoxUsableArea.aspectRatio, 0.001)
        assertEquals(14.00, EquinoxUsableArea.diagonalIn, 0.01)
        assertEquals(10.50, EquinoxUsableArea.interactiveWidthIn(), 0.01)
        assertEquals(2.625, EquinoxUsableArea.reserveWidthIn(), 0.01)
        assertEquals(11.68, EquinoxUsableArea.tempXIn, 0.01)
        assertEquals(1.18, EquinoxUsableArea.tempBufferIn(), 0.01)
        assertEquals(10.89, EquinoxUsableArea.clockXIn, 0.01)
    }

    @Test
    fun thumbUrlIsNullWhenNoThumb() {
        assertNull(PlexUrls.thumbUrl("http://x:32400", null, "tok"))
        assertNull(PlexUrls.thumbUrl("http://x:32400", "", "tok"))
    }
}
