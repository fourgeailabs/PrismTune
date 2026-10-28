package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.ConnectionPicker
import com.fourgeailabs.prismtune.data.EquinoxUsableArea
import com.fourgeailabs.prismtune.data.PlexConnection
import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexUrls
import com.fourgeailabs.prismtune.data.SafeAreaConfig
import com.fourgeailabs.prismtune.data.SignInBrowser
import okio.Buffer
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
    fun connectionPickerPrefersRemote() {
        // 1.1.1, the user's correction: remote is PRIMARY for a car,
        // local is SECONDARY — the old local-first expectation is superseded.
        val remote = PlexConnection("https://remote.example:32400", local = false, protocol = "https")
        val local = PlexConnection("http://192.168.1.10:32400", local = true)
        assertEquals(remote, ConnectionPicker.pick(listOf(remote, local)))
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
    fun qrLinkIsPlainPlexLink() {
        // 1.0.3: weak 4-character PIN again. The QR encodes plain
        // plex.tv/link, and the code shown is the code to type there.
        assertEquals("https://plex.tv/link", PlexUrls.LINK_URL)
    }

    @Test
    fun pinRequestHasEmptyBodyAndNoStrongParameter() {
        // The exact request createPin() sends: POST to the pins
        // endpoint, empty body, no strong parameter of any kind.
        // This is the pre-1.0.1 path proven to return a 4-char code.
        val request = PlexClient.buildPinRequest("test-device-uuid")
        assertEquals("https://plex.tv/api/v2/pins", request.url.toString())
        assertEquals("POST", request.method)
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        assertEquals("", buffer.readUtf8())
        assertEquals(0L, request.body?.contentLength())
        assertEquals("prismtune-test-device-uuid", request.header("X-Plex-Client-Identifier"))
    }

    @Test
    fun carBrowserTargetIsPlainPlexLink() {
        // The in-car browser opens exactly the same page as the phone
        // path — plain plex.tv/link, no code or token in the URL —
        // preferring Chrome only if it resolves on the car.
        assertEquals("https://plex.tv/link", SignInBrowser.targetUrl())
        assertEquals("com.android.chrome", SignInBrowser.CHROME_PACKAGE)
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
