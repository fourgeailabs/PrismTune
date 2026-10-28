package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.AllConnectionsFailed
import com.fourgeailabs.prismtune.data.ConnectionPicker
import com.fourgeailabs.prismtune.data.PlexConnection
import com.fourgeailabs.prismtune.data.PlexException
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.decodeOfflineTracks
import com.fourgeailabs.prismtune.data.encodeOfflineTracks
import com.fourgeailabs.prismtune.data.tryServerConnections
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 1.1.1 cellular fallback: a dead local connection must not fail
 * the whole library load. Pure decision points + the injected
 * probe (no real network in unit tests).
 */
class ConnectionFallbackTest {

    private val local = PlexConnection(uri = "http://local.example:32400", local = true, protocol = "http")
    private val remote = PlexConnection(uri = "https://remote.example:32400", local = false, protocol = "https")
    private val relay = PlexConnection(uri = "http://relay.example:32400", local = false, protocol = "http")

    @Test
    fun orderedIsRemoteFirstLocalSecondRelayThirdEvenWhenLocalWorkedLast() {
        // Eric's correction: remote is PRIMARY for a car, even on
        // home Wi-Fi; a recently-working local connection does NOT
        // promote local above remote.
        assertEquals(
            listOf(remote, local, relay),
            ConnectionPicker.ordered(listOf(local, remote, relay), preferredUri = local.uri),
        )
        assertEquals(
            listOf(remote, local, relay),
            ConnectionPicker.ordered(listOf(relay, remote, local)),
        )
        assertEquals(remote, ConnectionPicker.pick(listOf(relay, local, remote)))
    }

    @Test
    fun orderedDeduplicatesByUri() {
        assertEquals(2, ConnectionPicker.ordered(listOf(local, local, remote)).size)
    }

    @Test
    fun kindsNeverCarryUris() {
        assertEquals("local", ConnectionPicker.kindOf(local))
        assertEquals("remote", ConnectionPicker.kindOf(remote))
        assertEquals("relay", ConnectionPicker.kindOf(relay))
    }

    @Test
    fun remoteTimeoutFallsBackToLocalThenRelay() = runBlocking {
        val attempted = mutableListOf<String>()
        val (result, winningBase, tried) = tryServerConnections(
            listOf(local, remote, relay), preferredUri = null,
        ) { base ->
            attempted.add(base)
            if (base == remote.uri) throw SocketTimeoutException("connect timed out")
            if (base == local.uri) throw SocketTimeoutException("connect timed out")
            "sections-ok"
        }
        assertEquals("sections-ok", result)
        assertEquals(relay.uri, winningBase)
        assertEquals(listOf("remote", "local", "relay"), tried)
        assertEquals(listOf(remote.uri, local.uri, relay.uri), attempted)
    }

    @Test
    fun remoteFirstSucceedsWithoutTryingLocal() = runBlocking {
        val attempted = mutableListOf<String>()
        val (result, winningBase, tried) = tryServerConnections(
            listOf(local, remote), preferredUri = null,
        ) { base ->
            attempted.add(base)
            "sections-ok"
        }
        assertEquals("sections-ok", result)
        assertEquals(remote.uri, winningBase)
        assertEquals(listOf("remote"), tried)
        assertEquals(listOf(remote.uri), attempted)
    }

    @Test
    fun preferredRemoteIsTriedFirstAndLocalIsNotRetriedFirst() = runBlocking {
        val attempted = mutableListOf<String>()
        val (_, winningBase, _) = tryServerConnections(
            listOf(local, remote), preferredUri = remote.uri,
        ) { base ->
            attempted.add(base)
            "ok"
        }
        assertEquals(remote.uri, winningBase)
        assertEquals(listOf(remote.uri), attempted)
    }

    @Test
    fun allConnectionsFailNamesKindsNotUris() = runBlocking {
        try {
            tryServerConnections(listOf(local, remote), preferredUri = null) { _ ->
                throw SocketTimeoutException("connect timed out")
            }
            fail("expected AllConnectionsFailed")
        } catch (e: AllConnectionsFailed) {
            assertEquals(listOf("remote", "local"), e.triedKinds)
            assertTrue(!e.message.orEmpty().contains("local.example"))
            assertTrue(!e.message.orEmpty().contains("remote.example"))
        }
    }

    @Test
    fun unauthorizedIsNotTreatedAsConnectionFailure() = runBlocking {
        try {
            tryServerConnections(listOf(local, remote), preferredUri = null) { _ ->
                throw PlexException("GET failed: 401 ", 401)
            }
            fail("expected PlexException 401")
        } catch (e: PlexException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun offlineTracksEncodeDecodeRoundTripWithPipes() {
        val tracks = listOf(
            PlexTrack(key = "123", title = "A | B", artistTitle = "Example Artist", albumTitle = "Sample Album", durationMs = 225000),
            PlexTrack(key = "456", title = "Plain", artistTitle = "", albumTitle = "", durationMs = 0),
        )
        val decoded = decodeOfflineTracks(encodeOfflineTracks(tracks))
        assertEquals(tracks, decoded)
        assertEquals(emptyList<PlexTrack>(), decodeOfflineTracks(null))
        assertEquals(emptyList<PlexTrack>(), decodeOfflineTracks("not-a-valid-line"))
    }
}
