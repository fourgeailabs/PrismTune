package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexUrls
import com.fourgeailabs.prismtune.data.PinResult
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.5 fallback host handling: plex.tv first, clients.plex.tv only
 * on a DNS failure, and polling on the same host as creation.
 * Network calls themselves are not unit-tested here (no network in
 * unit tests); these tests cover the pure decision points.
 */
class FallbackTest {

    @Test
    fun dnsFailureDetectedFromUnknownHost() {
        assertTrue(PlexClient.isDnsFailure(UnknownHostException("plex.tv")))
    }

    @Test
    fun dnsFailureDetectedFromWrappedUnknownHost() {
        val wrapped = RuntimeException("call failed", UnknownHostException("plex.tv"))
        assertTrue(PlexClient.isDnsFailure(wrapped))
    }

    @Test
    fun dnsFailureDetectedFromResolverMessage() {
        assertTrue(
            PlexClient.isDnsFailure(
                RuntimeException("Unable to resolve host \"plex.tv\": No address associated with hostname"),
            ),
        )
    }

    @Test
    fun nonDnsFailuresDoNotTriggerFallback() {
        assertFalse(PlexClient.isDnsFailure(RuntimeException("timeout")))
        assertFalse(PlexClient.isDnsFailure(RuntimeException("HTTP 401")))
        assertFalse(PlexClient.isDnsFailure(IllegalStateException("empty code")))
    }

    @Test
    fun pinRequestDefaultsToPlexTvAndAcceptsClientsHost() {
        val primary = PlexClient.buildPinRequest("test-device-uuid")
        assertEquals("https://plex.tv/api/v2/pins", primary.url.toString())
        val fallback = PlexClient.buildPinRequest("test-device-uuid", PlexUrls.CLIENTS_PLEX_TV)
        assertEquals("https://clients.plex.tv/api/v2/pins", fallback.url.toString())
        assertEquals("POST", primary.method)
        assertEquals("POST", fallback.method)
    }

    @Test
    fun pinResultDefaultsToPlexTvAndCarriesFallbackHost() {
        assertEquals(PlexUrls.PLEX_TV, PinResult(1L, "ABCD").host)
        assertEquals(
            PlexUrls.CLIENTS_PLEX_TV,
            PinResult(1L, "ABCD", host = PlexUrls.CLIENTS_PLEX_TV).host,
        )
    }
}
