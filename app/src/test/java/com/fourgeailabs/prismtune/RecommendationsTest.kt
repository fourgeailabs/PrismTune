package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.recentlyPlayedMoods
import com.fourgeailabs.prismtune.data.recommendedPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    private fun track(key: String, artist: String, album: String, genres: List<String> = emptyList()) =
        PlexTrack(key = key, title = key, artistTitle = artist, albumTitle = album, genres = genres)

    @Test fun emptyHistoryEmptyRecommendations() {
        assertTrue(recommendedPlaylist(emptyList(), listOf(track("a", "X", "Y"))).isEmpty())
        assertTrue(recentlyPlayedMoods(emptyList()).isEmpty())
    }

    @Test fun recommendedPlaylistSharesArtistAndExcludesPlayed() {
        val recent = listOf(track("rec-1", "Artist A", "Album A", listOf("Rock")))
        val library = listOf(track("rec-1", "Artist A", "Album A"), track("rec-2", "Artist A", "Album B"), track("rec-3", "Artist Z", "Album Z"))
        val rec = recommendedPlaylist(recent, library)
        assertEquals(listOf("rec-2"), rec.map { it.key })
    }

    @Test fun recommendedPlaylistExcludesDisliked() {
        // 1.5.6 thumbs-down curation: a disliked track is
        // never recommended, however well it scores.
        val recent = listOf(track("rec-1", "Artist A", "Album A", listOf("Rock")))
        val library = listOf(track("rec-2", "Artist A", "Album B"), track("rec-3", "Artist A", "Album C"))
        val rec = recommendedPlaylist(recent, library, disliked = setOf("rec-2"))
        assertEquals(listOf("rec-3"), rec.map { it.key })
        // Without the dislike, both score and both return.
        assertEquals(listOf("rec-2", "rec-3"), recommendedPlaylist(recent, library).map { it.key })
    }
}
