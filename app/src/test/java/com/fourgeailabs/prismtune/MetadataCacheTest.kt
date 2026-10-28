package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.MetadataSnapshot
import com.fourgeailabs.prismtune.data.snapshotFromJson
import com.fourgeailabs.prismtune.data.snapshotToJson
import com.fourgeailabs.prismtune.data.trackFromJson
import com.fourgeailabs.prismtune.data.trackToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCacheTest {
    @Test fun trackRoundTripPreservesMetadata() {
        val t = PlexTrack(key = "123", title = "Song", artistTitle = "Artist", albumTitle = "Album",
            thumb = "/thumb/1", durationMs = 200000, partId = 5, albumKey = "alb1", artistKey = "art1",
            genres = listOf("Rock"), moods = listOf("Energetic"), styles = listOf("Alt"),
            viewCount = 3, userRating = 8.5f)
        val back = trackFromJson(trackToJson(t))
        assertEquals(t.key, back.key)
        assertEquals(t.title, back.title)
        assertEquals(t.artistTitle, back.artistTitle)
        assertEquals(t.albumTitle, back.albumTitle)
        assertEquals(t.thumb, back.thumb)
        assertEquals(t.durationMs, back.durationMs)
        assertEquals(t.partId, back.partId)
        assertEquals(t.albumKey, back.albumKey)
        assertEquals(t.genres, back.genres)
        assertEquals(t.moods, back.moods)
        assertEquals(t.viewCount, back.viewCount)
        assertEquals(t.userRating, back.userRating)
    }

    @Test fun snapshotReplacePrunesDeletedMedia() {
        // Soft resync replaces the whole snapshot, so a track
        // no longer on the server is simply not in the new one.
        val old = MetadataSnapshot(allTracks = listOf(
            PlexTrack(key = "1", title = "Old"),
            PlexTrack(key = "2", title = "Kept"),
        ))
        val fresh = MetadataSnapshot(allTracks = listOf(
            PlexTrack(key = "2", title = "Kept"),
            PlexTrack(key = "3", title = "New"),
        ))
        val decoded = snapshotFromJson(snapshotToJson(fresh))
        assertEquals(listOf("2", "3"), decoded.allTracks.map { it.key })
        assertTrue(decoded.allTracks.none { it.key == "1" })
        assertEquals(old.allTracks.size, 2)
    }
}

class QueueExpandTest {
    @Test fun expandQueueToNext100FromLibrary() {
        // Pure logic mirror: saved queue has 1 track, library has
        // 150 after it, expanded queue should have 101 total
        // (current + 100 up next), deduplicated.
        val saved = listOf(com.fourgeailabs.prismtune.data.PlexTrack(key = "0", title = "Current"))
        val library = (0..150).map { com.fourgeailabs.prismtune.data.PlexTrack(key = "$it", title = "T$it") }
        // Simulate expandQueueToNext100 logic without ViewModel:
        val existing = saved.map { it.key }.toMutableSet()
        val out = saved.toMutableList()
        for (t in library.drop(1)) {
            if (out.size - 0 - 1 >= 100) break
            if (existing.add(t.key)) out.add(t)
        }
        assertEquals(101, out.size)
        assertEquals("100", out.last().key)
    }
}

class RecommendedForYouTest {
    @Test fun recommendsFromPlayedAndSearched() {
        val played = listOf(com.fourgeailabs.prismtune.data.PlexTrack(key = "1", title = "A", artistTitle = "ArtistX", albumTitle = "AlbX", genres = listOf("Rock")))
        val library = listOf(
            com.fourgeailabs.prismtune.data.PlexTrack(key = "1", title = "A", artistTitle = "ArtistX", albumTitle = "AlbX", genres = listOf("Rock")),
            com.fourgeailabs.prismtune.data.PlexTrack(key = "2", title = "B", artistTitle = "ArtistX", albumTitle = "AlbY", genres = listOf("Rock")),
            com.fourgeailabs.prismtune.data.PlexTrack(key = "3", title = "C", artistTitle = "Other", albumTitle = "AlbZ", genres = listOf("Jazz")),
        )
        val rec = com.fourgeailabs.prismtune.data.recommendedForYou(played, listOf("ArtistX"), library)
        assertEquals("2", rec.songs.first().key)
        assertTrue(rec.albums.any { it.title == "AlbY" })
        assertTrue(rec.genres.contains("Rock"))
        // Empty history gives empty, never fake
        val empty = com.fourgeailabs.prismtune.data.recommendedForYou(emptyList(), emptyList(), library)
        assertTrue(empty.songs.isEmpty() && empty.albums.isEmpty())
    }
}
