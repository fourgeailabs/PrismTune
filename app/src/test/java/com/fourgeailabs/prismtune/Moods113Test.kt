package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.MoodsChunkAccumulator
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.decodeMoodsCounts
import com.fourgeailabs.prismtune.data.encodeMoodsCounts
import com.fourgeailabs.prismtune.data.feelsCounts
import com.fourgeailabs.prismtune.data.filterTracks
import com.fourgeailabs.prismtune.data.mapTrackFeels
import com.fourgeailabs.prismtune.data.mapTrackFeelsCached
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.3 Moods crash regression tests. Test fixtures only, never shown in the app. */
class Moods113Test {
    private fun track(key: String, genres: List<String> = listOf("Pop")) =
        PlexTrack(key = key, title = key, artistTitle = "Test Artist", albumTitle = "Test Album", genres = genres)

    @Test
    fun cachedMappingMatchesUncachedMapping() {
        val t = track("cache-track-1")
        assertEquals(mapTrackFeels(t), mapTrackFeelsCached(t))
        assertEquals(mapTrackFeelsCached(t), mapTrackFeelsCached(t))
    }

    @Test
    fun accumulatorCountsMatchFullRecomputeOverMultiChunkSequence() {
        val chunks = listOf(
            listOf(track("a1"), track("a2")),
            listOf(track("b1", listOf("Rock")), track("b2", listOf("Country"))),
            listOf(track("c1")),
        )
        val acc = MoodsChunkAccumulator()
        chunks.forEach { acc.addChunk(it) }
        val all = chunks.flatten()
        val (g, m, v) = feelsCounts(all)
        assertEquals(g, acc.genreCounts)
        assertEquals(m, acc.moodCounts)
        assertEquals(v, acc.vibeCounts)
        assertEquals(all.size, acc.loadedCount)
    }

    @Test
    fun resumeAccumulatorDoesNotResetAlreadyLoadedCounts() {
        // Simulates a crash at 10k+: already-loaded tracks keep
        // their counts, and the next chunk adds to them, not resets.
        val already = listOf(track("r1"), track("r2"))
        val acc = MoodsChunkAccumulator()
        acc.addChunk(already)
        val before = acc.genreCounts
        acc.addChunk(listOf(track("r3")))
        assertTrue(acc.loadedCount == 3)
        assertTrue((acc.genreCounts["Pop"] ?: 0) >= (before["Pop"] ?: 0))
    }

    @Test
    fun moodsCountsEncodeDecodeRoundTrips() {
        val g = mapOf("Pop" to 12, "Rock" to 3)
        val m = mapOf("Happy" to 7)
        val v = mapOf("Road Trip" to 5)
        val (dg, dm, dv) = decodeMoodsCounts(encodeMoodsCounts(g, m, v))
        assertEquals(g, dg)
        assertEquals(m, dm)
        assertEquals(v, dv)
    }

    @Test
    fun accumulatorRestoreKeepsCountsForResume() {
        val acc = MoodsChunkAccumulator()
        acc.restore(mapOf("Pop" to 100), mapOf("Happy" to 50), mapOf("Road Trip" to 20), 1000)
        assertEquals(1000, acc.loadedCount)
        acc.addChunk(listOf(track("resume-1")))
        assertEquals(1001, acc.loadedCount)
        assertTrue((acc.genreCounts["Pop"] ?: 0) >= 100)
    }

    @Test
    fun filterUsesCachedMappingAndReturnsEmptySelectionAsIs() {
        // 1.5.4: only a track with a real Plex Happy mood tag
        // matches a Happy selection — a Pop/Rock genre alone
        // must NOT match, or the 15,352 bug is back.
        val tracks = listOf(
            PlexTrack(key = "f1", title = "f1", artistTitle = "Test Artist", albumTitle = "Test Album", genres = listOf("Pop"), moods = listOf("Happy")),
            PlexTrack(key = "f2", title = "f2", artistTitle = "Test Artist", albumTitle = "Test Album", genres = listOf("Rock")),
        )
        assertEquals(tracks, filterTracks(tracks))
        val filtered = filterTracks(tracks, selectedMoods = setOf("Happy"))
        assertTrue(filtered.any { it.key == "f1" })
        assertTrue(filtered.none { it.key == "f2" })
    }
}
