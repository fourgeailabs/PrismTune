package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.DiagnosticStep
import com.fourgeailabs.prismtune.data.PlexException
import com.fourgeailabs.prismtune.data.formatDiagnostic
import com.fourgeailabs.prismtune.viewmodel.pinCreationErrorMessage
import com.fourgeailabs.prismtune.viewmodel.signInErrorMessage
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.5: a DNS failure must never read as a Plex code rejection.
 * The 2026-10-25 report was "Could not reach Plex: Unable to resolve
 * host \"plex.tv\": No address associated with hostname" shown raw.
 */
class SignInErrorTest {

    // 1.0.5 correction: device Wi-Fi/VPN/Private DNS were proven not
    // to be the cause (Plex Amp and car Chrome reach plex.tv fine),
    // so the message points at the Test connection diagnostic instead
    // of repeating that troubleshooting.
    private val networkMessage =
        "No connection to Plex — PrismTune could not find plex.tv. " +
            "Tap Test connection below to see which step fails, " +
            "then tap Get link code again."

    @Test
    fun unknownHostMapsToNetworkMessage() {
        assertEquals(
            networkMessage,
            signInErrorMessage(UnknownHostException("plex.tv")),
        )
    }

    @Test
    fun wrappedUnknownHostMapsToNetworkMessage() {
        // OkHttp may wrap the resolver's UnknownHostException.
        val wrapped = RuntimeException("call failed", UnknownHostException("plex.tv"))
        assertEquals(networkMessage, signInErrorMessage(wrapped))
    }

    @Test
    fun resolveHostMessageTextMapsToNetworkMessage() {
        val e = RuntimeException(
            "Unable to resolve host \"plex.tv\": No address associated with hostname",
        )
        assertEquals(networkMessage, signInErrorMessage(e))
    }

    @Test
    fun networkMessageNeverMentionsRejectionOrCode() {
        val message = signInErrorMessage(UnknownHostException("plex.tv"))
        assertFalse(message.contains("rejected", ignoreCase = true))
        assertFalse(message.contains("code was", ignoreCase = true))
    }

    @Test
    fun fourOhOneKeepsRejectionMessage() {
        assertEquals(
            "Plex rejected that sign-in, please sign in again",
            signInErrorMessage(PlexException("POST failed: 401 {}", 401)),
        )
    }

    @Test
    fun diagnosticFormatsEachStepInPlainWords() {
        val lines = formatDiagnostic(
            listOf(
                DiagnosticStep("DNS lookup", true, "this device found plex.tv"),
                DiagnosticStep("Reach plex.tv", false, "could not reach plex.tv over the network"),
                DiagnosticStep("Plex sign-in request", false, "Plex sign-in request failed"),
            ),
        )
        assertEquals(3, lines.size)
        assertEquals("✓ DNS lookup: this device found plex.tv", lines[0])
        assertEquals("✗ Reach plex.tv: could not reach plex.tv over the network", lines[1])
        assertEquals("✗ Plex sign-in request: Plex sign-in request failed", lines[2])
    }

    // 1.0.6: PIN creation must never collapse to the generic
    // token-rejection text, even on a 401 — that conflation hid
    // the 2026-10-25 phone failure (code/QR never appeared).
    @Test
    fun pinCreationFourOhOneDoesNotCollapseToTokenRejection() {
        val message = pinCreationErrorMessage(
            PlexException("POST https://plex.tv/api/v2/pins failed: 401 {\"errors\":[{\"code\":1001}]}", 401),
        )
        assertTrue(message.contains("plex.tv"))
        assertTrue(message.contains("401"))
        assertTrue(message.contains("No code was created"))
        assertFalse(message == "Plex rejected that sign-in, please sign in again")
        assertFalse(message.contains("Plex rejected that sign-in"))
    }

    @Test
    fun pinCreationDnsStillMapsToNetworkMessage() {
        assertEquals(
            networkMessage,
            pinCreationErrorMessage(UnknownHostException("plex.tv")),
        )
    }

    @Test
    fun pinCreationCombinedFailureIncludesBothHosts() {
        val combined = com.fourgeailabs.prismtune.data.PlexClient.combinePinFailures(
            PlexException("POST https://plex.tv/api/v2/pins failed: 401 {\"errors\":[{\"code\":1001}]}", 401),
            PlexException("POST https://clients.plex.tv/api/v2/pins failed: 403 forbidden", 403),
        )
        assertEquals(401, combined.statusCode)
        val message = pinCreationErrorMessage(combined)
        assertTrue(message.contains("plex.tv and clients.plex.tv"))
        assertTrue(message.contains("401"))
        assertTrue(message.contains("403"))
        assertTrue(message.contains("No code was created"))
        assertFalse(message.contains("Plex rejected that sign-in"))
    }

    @Test
    fun pinCreationBodyIsTruncatedToTwoHundredChars() {
        val longBody = "x".repeat(500)
        val message = pinCreationErrorMessage(
            PlexException("POST https://plex.tv/api/v2/pins failed: 401 $longBody", 401),
        )
        assertFalse(message.contains(longBody))
        assertTrue(message.contains("x".repeat(200)))
    }

    @Test
    fun otherErrorsKeepGenericMessage() {
        assertEquals(
            "Could not reach Plex: boom",
            signInErrorMessage(RuntimeException("boom")),
        )
    }
}
