package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.MoodsChunkAccumulator
import com.fourgeailabs.prismtune.data.PlexArtist
import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.correctedPlaylistCount
import com.fourgeailabs.prismtune.data.resolveTrackDurationMs
import com.fourgeailabs.prismtune.playback.shouldCallPlayerForShuffle
import com.fourgeailabs.prismtune.playback.shouldPauseOnMute
import com.fourgeailabs.prismtune.playback.shuffleStateAfterAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 correction tests: Moods large-library chunking, shuffle
 * safety, duration fallback, artist children path, playlist counts.
 * No real token, password, or server identifier anywhere here.
 */
class CorrectionsTest {

    // --- Moods: 45k lightweight tracks, chunked, never one fetch ---
    @Test
    fun moods_45kTracks_chunkedAggregation_matchesTotals() {
        val acc = MoodsChunkAccumulator()
        var fetches = 0
        val total = 45_000
        var start = 0
        while (start < total) {
            // Simulate one paginated page (500 tracks), as trackPage does.
            val page = (start until minOf(start + 500, total)).map { i ->
                PlexTrack(key = "$i", title = "Track $i", genres = listOf("Rock"))
            }
            fetches++
            assertTrue("a single fetch must never be the whole library", page.size <= 500)
            acc.addChunk(page)
            start += page.size
        }
        assertEquals(90, fetches)
        assertEquals(45_000, acc.loadedCount)
        assertEquals(45_000, acc.genreCounts["Rock"])
    }

    @Test
    fun moods_chunkFailure_keepsAlreadyLoadedCards() {
        val acc = MoodsChunkAccumulator()
        acc.addChunk(listOf(PlexTrack(key = "1", title = "A", genres = listOf("Rock"))))
        acc.addFailure("Chunk 2 failed")
        assertEquals(1, acc.loadedCount)
        assertEquals(1, acc.genreCounts["Rock"])
        assertEquals("Chunk 2 failed", acc.chunkError)
    }

    // --- Shuffle safety ---
    @Test
    fun shuffle_emptyQueue_storesPreferenceWithoutPlayerCall() {
        assertFalse(shouldCallPlayerForShuffle(queueEmpty = true, released = false))
        assertEquals(true, shuffleStateAfterAttempt(current = false, requested = true, playerSucceeded = true))
    }

    @Test
    fun shuffle_releasedPlayer_neverCallsPlayerAndKeepsState() {
        assertFalse(shouldCallPlayerForShuffle(queueEmpty = false, released = true))
        assertEquals(false, shuffleStateAfterAttempt(current = false, requested = true, playerSucceeded = false))
        assertEquals(true, shuffleStateAfterAttempt(current = true, requested = false, playerSucceeded = false))
    }

    @Test
    fun shuffle_failedPlayerCall_keepsPreviousState() {
        assertEquals(false, shuffleStateAfterAttempt(false, true, false))
    }

    // --- Duration fallback (the 0:00 fix) ---
    @Test
    fun duration_topLevelWins() {
        assertEquals(187_000L, resolveTrackDurationMs(187_000, 0, 0))
    }

    @Test
    fun duration_mediaDurationUsedWhenTopLevelMissing() {
        assertEquals(187_000L, resolveTrackDurationMs(0, 187_000, 0))
    }

    @Test
    fun duration_partDurationUsedWhenOthersMissing() {
        assertEquals(187_000L, resolveTrackDurationMs(0, 0, 187_000))
    }

    @Test
    fun duration_allMissing_staysZeroNeverInvented() {
        assertEquals(0L, resolveTrackDurationMs(0, 0, 0))
    }

    // --- Artist children path (the 404 fix) ---
    @Test
    fun artistChildrenPath_prefersServerKeyPath() {
        val client = PlexClient()
        assertEquals(
            "/library/metadata/51261/children",
            client.artistChildrenPath(PlexArtist(key = "51261", title = "X", keyPath = "/library/metadata/51261")),
        )
        assertEquals(
            "/library/metadata/51264/children",
            client.artistChildrenPath(PlexArtist(key = "51264", title = "X", keyPath = "/library/metadata/51264/children")),
        )
        assertEquals(
            "/library/metadata/51265/children",
            client.artistChildrenPath(PlexArtist(key = "51265", title = "X")),
        )
    }

    // --- Playlist counts (the 0-track fix) ---
    @Test
    fun playlistCount_childrenTotalCorrectsZeroLeafCount() {
        assertEquals(279, correctedPlaylistCount(0, 279))
    }

    @Test
    fun playlistCount_realLeafCountKept() {
        assertEquals(46_593, correctedPlaylistCount(46_593, 279))
    }

    @Test
    fun playlistCount_missingChildrenCountNeverInvented() {
        assertEquals(0, correctedPlaylistCount(0, -1))
    }
}

class MutePauseTest {
    @org.junit.Test fun muteTransitionPausesOnlyWhenPlaying() {
        org.junit.Assert.assertTrue(shouldPauseOnMute(true, 0, 5))
        org.junit.Assert.assertFalse(shouldPauseOnMute(false, 0, 5))
        org.junit.Assert.assertFalse(shouldPauseOnMute(true, 0, 0))
        org.junit.Assert.assertFalse(shouldPauseOnMute(true, 3, 5))
    }
}
