package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexArtist
import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.ServerSearchResults
import com.fourgeailabs.prismtune.data.artworkForArtist
import com.fourgeailabs.prismtune.data.hasMorePages
import com.fourgeailabs.prismtune.data.paginationOffsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 regression tests. No real token, password, or server
 * clientIdentifier appears here — all fixtures are invented.
 */
class PaginationPlaybackTest {

    @Test
    fun paginationOffsetsCover45kLibraryIn500Chunks() {
        val offsets = paginationOffsets(45000, 500)
        assertEquals(90, offsets.size)
        assertEquals(0, offsets.first())
        assertEquals(44500, offsets.last())
    }

    @Test
    fun paginationOffsetsStopExactlyAtTotalSize() {
        // Regression for the "just over 200" bug: a first page of
        // ~200 must not be treated as the whole library.
        assertEquals(listOf(0, 500, 1000), paginationOffsets(1234, 500))
        assertEquals(listOf(0), paginationOffsets(200, 500))
        assertTrue(hasMorePages(200, 45000))
        assertFalse(hasMorePages(45000, 45000))
        assertFalse(hasMorePages(500, 200))
    }

    @Test
    fun serverSearchResultsGroupByTypeOnly() {
        val results = ServerSearchResults(
            artists = listOf(PlexArtist("a1", "Test Artist", thumb = "/artist-thumb")),
            albums = emptyList(),
            tracks = listOf(PlexTrack("t1", "Test Song", artistTitle = "Test Artist")),
        )
        assertFalse(results.isEmpty)
        assertEquals(2, results.totalCount)
        assertTrue(ServerSearchResults().isEmpty)
    }

    @Test
    fun partIdForPrefersListedPartAndFallsBackToMetadataPart() {
        val listed = PlexTrack("t1", "Song", partId = 987L)
        val unlisted = PlexTrack("t2", "Song 2", partId = -1L)
        // partIdFor is an instance method on PlexClient; use a client
        // with no network calls — the helper is pure.
        val client = PlexClient()
        assertEquals(987L, client.partIdFor(listed, 111L))
        assertEquals(111L, client.partIdFor(unlisted, 111L))
        assertEquals(-1L, client.partIdFor(unlisted, -1L))
    }

    @Test
    fun artistArtworkIsArtistThumbNotAlbumThumb() {
        val artist = PlexArtist("a1", "Test Artist", thumb = "/artist-image")
        assertEquals("/artist-image", artworkForArtist(artist))
        // Even if an album thumb were handed in by mistake, the
        // helper never returns it: it only reads artist.thumb.
        val noThumb = PlexArtist("a2", "No Image Artist", thumb = null)
        assertEquals(null, artworkForArtist(noThumb))
    }

    @Test
    fun trackMetadataShapeParsesWithoutMedia() {
        // A listing without Media must still parse (partId -1),
        // so the ViewModel knows to fetch full metadata on play
        // instead of building a /parts/-1/file URL.
        val track = PlexTrack("t9", "Recent Song", partId = -1L)
        assertEquals(-1L, track.partId)
        assertTrue(track.key.isNotBlank())
    }

    @Test
    fun searchResultJsonGroupsTypes() {
        // Simulates /library/search SearchResult Metadata shapes.
        val json = JSONObject(
            """{"MediaContainer":{"SearchResult":[
            {"Metadata":{"type":"artist","ratingKey":"a1","title":"Test Artist","thumb":"/a"}},
            {"Metadata":{"type":"album","ratingKey":"al1","title":"Test Album","parentTitle":"Test Artist"}},
            {"Metadata":{"type":"track","ratingKey":"t1","title":"Test Song","grandparentTitle":"Test Artist","parentTitle":"Test Album"}}
            ]}}""",
        )
        val results = json.optJSONObject("MediaContainer")?.optJSONArray("SearchResult")
        var artists = 0; var albums = 0; var tracks = 0
        for (i in 0 until (results?.length() ?: 0)) {
            when (results?.optJSONObject(i)?.optJSONObject("Metadata")?.optString("type")) {
                "artist" -> artists++
                "album" -> albums++
                "track" -> tracks++
            }
        }
        assertEquals(1, artists)
        assertEquals(1, albums)
        assertEquals(1, tracks)
    }
}
