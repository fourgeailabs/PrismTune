package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.appendDiagnostic
import com.fourgeailabs.prismtune.data.artistJumpIndex
import com.fourgeailabs.prismtune.data.artistJumpPage
import com.fourgeailabs.prismtune.data.buildOfflineTopPicks
import com.fourgeailabs.prismtune.data.decodeSavedQueue
import com.fourgeailabs.prismtune.data.diagnosticEntryFor
import com.fourgeailabs.prismtune.data.diagnosticsCopyText
import com.fourgeailabs.prismtune.data.encodeSavedQueue
import com.fourgeailabs.prismtune.data.sanitizeDiagnosticText
import com.fourgeailabs.prismtune.data.topAlbumKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 top-3 + offline tests: A-Z jump, resume queue round-trip,
 * diagnostics sanitising, and Offline Top Picks building.
 * No real token, password, or server identifier anywhere here.
 */
class TopPicksTest {

    private fun track(key: String, views: Int = 0, rating: Float? = null) =
        PlexTrack(key = key, title = "Song $key", viewCount = views, userRating = rating)

    // --- A-Z jump ---
    @Test
    fun azJump_findsFirstArtistForLetter() {
        val titles = listOf("ABBA", "Adele", "The Beatles", "Coldplay", "Zedd")
        assertEquals(2, artistJumpIndex(titles, 'B')) // "The Beatles" sorts under B
        assertEquals(4, artistJumpIndex(titles, 'Z'))
        assertEquals(0, artistJumpIndex(titles, 'a')) // case-insensitive
    }

    @Test
    fun azJump_noMatchIsMinusOne() {
        assertEquals(-1, artistJumpIndex(listOf("ABBA", "Coldplay"), 'Q'))
        assertEquals(-1, artistJumpPage(-1))
    }

    @Test
    fun azJump_pageFromIndexSixPerPage() {
        assertEquals(0, artistJumpPage(0))
        assertEquals(0, artistJumpPage(5))
        assertEquals(1, artistJumpPage(6))
        // A 63-page library: a late-alphabet artist lands deep, in one jump.
        assertEquals(62, artistJumpPage(372))
    }

    @Test
    fun azJump_largeArtistList() {
        val titles = (0 until 5000).map { "Artist $it" } + listOf("ZZ Top")
        assertEquals(5000, artistJumpIndex(titles, 'Z'))
    }

    // --- Resume queue round-trip ---
    @Test
    fun resumeQueue_encodeDecodeRoundTrip() {
        val tracks = listOf(
            PlexTrack(key = "11", title = "One", artistTitle = "A", durationMs = 180_000, partId = 5),
            PlexTrack(key = "22", title = "Two", artistTitle = "B", durationMs = 200_000, partId = -1),
        )
        val decoded = decodeSavedQueue(encodeSavedQueue(tracks, 1, 61_500))!!
        assertEquals(2, decoded.tracks.size)
        assertEquals("11", decoded.tracks[0].key)
        assertEquals(5L, decoded.tracks[0].partId)
        assertEquals(1, decoded.index)
        assertEquals(61_500L, decoded.positionMs)
    }

    @Test
    fun resumeQueue_corruptInputDegradesToNull() {
        assertNull(decodeSavedQueue("not json at all {{{"))
        assertNull(decodeSavedQueue(null))
        assertNull(decodeSavedQueue(""))
    }

    // --- Diagnostics: no secrets ---
    @Test
    fun diagnostics_sanitisesTokensAndQuery() {
        val dirty = "GET https://plex.tv/api/v2/resources?X-Plex-Token=secret123&foo=bar failed: 401 authToken=abc password=hunter2"
        val clean = sanitizeDiagnosticText(dirty)
        assertFalse(clean.contains("secret123"))
        assertFalse(clean.contains("abc"))
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("X-Plex-Token=secret123"))
    }

    @Test
    fun diagnostics_copyTextHasNoCredentials() {
        val entry = diagnosticEntryFor(
            "server list", "https://plex.tv/api/v2/resources?X-Plex-Token=zzz",
            com.fourgeailabs.prismtune.data.PlexException("GET failed: 401 {\"errors\":[{\"code\":1001}]}", 401),
        )
        val text = diagnosticsCopyText(listOf(entry))
        assertFalse(text.contains("zzz"))
        assertTrue(text.contains("1001"))
        assertTrue(text.contains("server list"))
        assertEquals("/api/v2/resources", entry.endpointPath)
    }

    @Test
    fun diagnostics_ringBufferKeepsLast20() {
        var entries = emptyList<com.fourgeailabs.prismtune.data.DiagnosticEntry>()
        repeat(25) { i ->
            entries = appendDiagnostic(entries, diagnosticEntryFor("call", "/x", Exception("e$i"), timeMs = i.toLong()))
        }
        assertEquals(20, entries.size)
        assertEquals(5L, entries.first().timeMs)
    }

    // --- Offline Top Picks ---
    @Test
    fun topPicks_mostPlayedFirstDedupedCapped() {
        val most = listOf(track("a", views = 50), track("b", views = 100), track("a", views = 50))
        val rated = listOf(track("c", rating = 9f), track("b", rating = 10f))
        val result = buildOfflineTopPicks(most, rated, emptyList(), emptyList(), emptyList(), emptyList(), limit = 2)
        assertEquals(listOf("b", "a"), result.tracks.map { it.key })
    }

    @Test
    fun topPicks_albumAndPlaylistTracksIncluded() {
        val result = buildOfflineTopPicks(
            emptyList(), emptyList(), emptyList(), emptyList(),
            topAlbumTracks = listOf(track("album-track")),
            topPlaylistTracks = listOf(track("playlist-track")),
        )
        assertEquals(listOf("album-track", "playlist-track"), result.tracks.map { it.key })
    }

    @Test
    fun topPicks_noDataAtAllIsHonestEmpty() {
        val result = buildOfflineTopPicks(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(result.tracks.isEmpty())
        assertTrue(result.plexDataMissing)
        assertTrue(result.reason!!.contains("No play history"))
    }

    @Test
    fun topPicks_localRecentFillsOnlyWhenNothingElse() {
        val result = buildOfflineTopPicks(
            emptyList(), emptyList(), emptyList(),
            localRecent = listOf(track("recent-1")),
            emptyList(), emptyList(),
        )
        assertEquals(listOf("recent-1"), result.tracks.map { it.key })
    }

    @Test
    fun topAlbumKeys_groupsByAlbumAndSumsViews() {
        val tracks = listOf(
            PlexTrack(key = "1", title = "t1", albumKey = "albA", viewCount = 10),
            PlexTrack(key = "2", title = "t2", albumKey = "albA", viewCount = 20),
            PlexTrack(key = "3", title = "t3", albumKey = "albB", viewCount = 5),
        )
        assertEquals(listOf("albA", "albB"), topAlbumKeys(tracks))
    }
}
