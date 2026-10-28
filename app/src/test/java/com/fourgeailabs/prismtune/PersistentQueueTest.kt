package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.decodeSavedQueue
import com.fourgeailabs.prismtune.data.encodeSavedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistentQueueTest {
    private fun track() = PlexTrack(
        key = "123", title = "Song", artistTitle = "Artist", albumTitle = "Album",
        thumb = "/thumb", durationMs = 200000, partId = 5, partKey = "/part",
        index = 3, discNumber = 1, albumThumb = "/album", artistThumb = "/artist",
        year = 2020, artistKey = "a1", albumKey = "al1",
        genres = listOf("Pop"), moods = listOf("Happy"), styles = listOf("Road Trip"),
    )

    @Test fun fullMetadataAndSourceRoundTrip() {
        val encoded = encodeSavedQueue(
            listOf(track()), 0, 42000,
            sourceKind = "Playlist", sourceName = "All Music", sourceKey = "51262", serverId = "srv1",
        )
        val decoded = decodeSavedQueue(encoded)!!
        assertEquals("Playlist", decoded.sourceKind)
        assertEquals("All Music", decoded.sourceName)
        assertEquals("51262", decoded.sourceKey)
        assertEquals("srv1", decoded.serverId)
        assertEquals(42000, decoded.positionMs)
        val t = decoded.tracks[0]
        assertEquals("Song", t.title)
        assertEquals(5, t.partId)
        assertEquals("/part", t.partKey)
        assertEquals(3, t.index)
        assertEquals(1, t.discNumber)
        assertEquals("/album", t.albumThumb)
        assertEquals("/artist", t.artistThumb)
        assertEquals(2020, t.year)
        assertEquals(listOf("Pop"), t.genres)
        assertEquals(listOf("Happy"), t.moods)
        assertEquals(listOf("Road Trip"), t.styles)
    }

    @Test fun oldSavedQueueWithoutSourceStillDecodes() {
        val old = """{"tracks":[{"key":"9","title":"Old","artist":"A","album":"B","thumb":"","duration":1000,"partId":-1}],"index":0,"position":0}"""
        val decoded = decodeSavedQueue(old)!!
        assertNull(decoded.sourceKind)
        assertEquals("Old", decoded.tracks[0].title)
    }

    @Test fun corruptSavedQueueNeverCrashes() {
        assertNull(decodeSavedQueue("not json {"))
        assertNull(decodeSavedQueue(null))
    }
}
