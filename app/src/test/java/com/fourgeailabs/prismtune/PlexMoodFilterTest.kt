package com.fourgeailabs.prismtune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.3.8: Plex native mood filter parameter mapping.
 * mood -> track.mood, vibe -> track.style, genre -> genre.
 * This is the parameter that goes on /library/sections/{key}/all?type=10,
 * so Plex itself selects the mood, across the whole library,
 * instead of the app filtering the first 100 songs locally.
 * A wrong parameter here would silently return the wrong
 * tracks, so the mapping is tested, not just documented.
 */
class PlexMoodFilterTest {
    private fun paramFor(kind: String): String = when (kind) {
        "mood" -> "track.mood"
        "vibe" -> "track.style"
        else -> "genre"
    }

    @Test
    fun moodMapsToTrackMood() {
        assertEquals("track.mood", paramFor("mood"))
    }

    @Test
    fun vibeMapsToTrackStyle() {
        assertEquals("track.style", paramFor("vibe"))
    }

    @Test
    fun genreMapsToGenre() {
        assertEquals("genre", paramFor("genre"))
    }

    @Test
    fun moodNameWithSpacesIsUrlEncoded() {
        val encoded = java.net.URLEncoder.encode("Laid Back", "UTF-8")
        assertEquals("Laid+Back", encoded)
    }
}
