package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.buildCuratedByMe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedByMeTest {
    private fun track(key: String, artist: String, album: String, genres: List<String> = emptyList()) =
        PlexTrack(key = key, title = key, artistTitle = artist, albumTitle = album, genres = genres)

    @Test fun emptyLikesMeansEmptyCardNeverFiller() {
        assertTrue(buildCuratedByMe(emptyList(), listOf(track("a", "X", "Y"))).isEmpty())
    }

    @Test fun likedFirstThenSimilarAndDislikedNeverIncluded() {
        val liked = listOf(track("like-1", "Artist A", "Album A", listOf("Rock")))
        val library = listOf(
            track("like-1", "Artist A", "Album A"),
            track("sim-1", "Artist A", "Album B"),
            track("bad-1", "Artist A", "Album C"),
            track("far-1", "Artist Z", "Album Z"),
        )
        val curated = buildCuratedByMe(liked, library, disliked = setOf("bad-1"))
        assertEquals(listOf("like-1", "sim-1"), curated.map { it.key })
    }

    @Test fun dislikedWinsOverLiked() {
        val liked = listOf(track("x", "Artist A", "Album A"))
        val curated = buildCuratedByMe(liked, listOf(track("x", "Artist A", "Album A")), disliked = setOf("x"))
        assertTrue(curated.isEmpty())
    }

    @Test fun likedTrackCountsEvenWhenNotInLibrary() {
        val liked = listOf(track("only-liked", "Artist A", "Album A"))
        val curated = buildCuratedByMe(liked, emptyList())
        assertEquals(listOf("only-liked"), curated.map { it.key })
    }
}
