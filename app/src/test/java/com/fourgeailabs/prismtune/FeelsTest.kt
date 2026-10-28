package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.availableGenres
import com.fourgeailabs.prismtune.data.filterTracks
import com.fourgeailabs.prismtune.data.glossColorsFor
import com.fourgeailabs.prismtune.data.groupByAlbum
import com.fourgeailabs.prismtune.data.groupByArtist
import com.fourgeailabs.prismtune.data.groupByGenre
import com.fourgeailabs.prismtune.data.mapTrackFeels
import com.fourgeailabs.prismtune.data.pickLucky
import com.fourgeailabs.prismtune.data.resolveVoiceSearch
import com.fourgeailabs.prismtune.data.VoicePlayOutcome
import com.fourgeailabs.prismtune.data.searchLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test library only — these are invented test fixtures, never real catalogue data and never shown in the app. */
private fun track(
    title: String,
    artist: String,
    album: String = "Test Album",
    genres: List<String> = emptyList(),
    moods: List<String> = emptyList(),
    styles: List<String> = emptyList(),
) = PlexTrack(key = title, title = title, artistTitle = artist, albumTitle = album, genres = genres, moods = moods, styles = styles)

private val LIBRARY = listOf(
    track("Red Dirt Road", "Toby Keith", "Shock'n Y'all", genres = listOf("Country")),
    track("Should've Been a Cowboy", "Toby Keith", "Toby Keith", genres = listOf("Country")),
    track("Courtesy of the Red, White and Blue", "Toby Keith", "Unleashed", genres = listOf("Country")),
    track("Happy Song", "Test Artist", genres = listOf("Pop", "Rock"), moods = listOf("Happy", "Chill")),
    track("Calm Song", "Other Artist", genres = listOf("Ambient"), moods = listOf("Chill")),
    track("No Tags Song", "Quiet Artist", genres = emptyList()),
)

class FeelsTest {

    @Test
    fun realPlexTagsOnlyNoGenreInference() {
        // 1.5.4: a Pop/Rock track with NO Plex mood tags gets
        // NO moods — genre must never guess them. This is the
        // 15,352 bug: Pop/Rock used to fire both Pop and Rock
        // mood sets from one genre string.
        val popRockNoTags = track("Genre Only", "Test Artist", genres = listOf("Pop/Rock"))
        val feels = mapTrackFeels(popRockNoTags)
        assertTrue(feels.moods.isEmpty())
        assertTrue(feels.vibes.isEmpty())
        // Real Plex tags pass through verbatim, including
        // terms from the user's Plex Amp screenshots that were
        // never in our old 193-term taxonomy.
        val plexTags = track("Tagged", "Test Artist", genres = listOf("Pop/Rock"),
            moods = listOf("Acerbic", "Amiable & Good-Natured", "Angst-Ridden"),
            styles = listOf("Alternative/Indie Rock"))
        val tagged = mapTrackFeels(plexTags)
        assertTrue(tagged.moods.containsAll(listOf("Acerbic", "Amiable & Good-Natured", "Angst-Ridden")))
        assertTrue(tagged.vibes.contains("Alternative/Indie Rock"))
        // Case-insensitive dedup: "happy" + "Happy" = one card.
        val dup = track("Dup", "Test Artist", moods = listOf("happy", "Happy", " Happy "))
        assertEquals(1, mapTrackFeels(dup).moods.size)
    }

    @Test
    fun oneTrackInMultipleMoodsVibesAndGenres() {
        val feels = mapTrackFeels(LIBRARY[3])
        assertTrue(feels.genres.containsAll(listOf("Pop", "Rock")))
        // Plex mood tags Happy + Chill ONLY — no genre inference,
        // so no extra moods from Pop/Rock, and no vibes (no styles).
        assertTrue(feels.moods.contains("Happy"))
        assertTrue(feels.moods.contains("Chill"))
        assertTrue(feels.vibes.isEmpty())
        // A track with no tags and no genre gets no mood or vibe.
        val plain = mapTrackFeels(LIBRARY[5])
        assertTrue(plain.moods.isEmpty())
        assertTrue(plain.vibes.isEmpty())
    }

    @Test
    fun genreAvailabilityOnlyFromLibrary() {
        val genres = availableGenres(LIBRARY)
        assertTrue(genres.containsAll(listOf("Country", "Pop", "Rock", "Ambient")))
        assertFalse(genres.contains("Jazz"))
        assertFalse(genres.contains("Metal"))
    }

    @Test
    fun onlyShowIsIntersectionAndOffIsUnion() {
        // Happy + Chill, Only Show ON: only the track with BOTH.
        val inter = filterTracks(LIBRARY, selectedMoods = setOf("Happy", "Chill"), onlyShow = true)
        assertEquals(listOf("Happy Song"), inter.map { it.title })
        // Same selection, Only Show OFF: any track with Happy OR Chill.
        val union = filterTracks(LIBRARY, selectedMoods = setOf("Happy", "Chill"), onlyShow = false)
        assertEquals(setOf("Happy Song", "Calm Song"), union.map { it.title }.toSet())
        // Nothing selected: the full library, not an empty screen.
        assertEquals(LIBRARY.size, filterTracks(LIBRARY).size)
    }

    @Test
    fun feelingLuckyOnlyFromFilteredSetAndDeterministic() {
        val filtered = filterTracks(LIBRARY, selectedGenres = setOf("Country"), onlyShow = true)
        val seed = kotlin.random.Random(42)
        val pick = pickLucky(filtered, seed)
        assertTrue(pick != null && pick in filtered)
        assertEquals(pick, pickLucky(filtered, kotlin.random.Random(42)))
        assertNull(pickLucky(emptyList(), kotlin.random.Random(42)))
    }

    @Test
    fun glossyColourIsDeterministicPerName() {
        assertEquals(glossColorsFor("Chill"), glossColorsFor("Chill"))
        assertEquals(glossColorsFor("Late Night Drive"), glossColorsFor("Late Night Drive"))
        val colours = listOf("Chill", "Happy", "Road Trip", "Workout", "Rainy Day", "Golden Hour")
            .map { glossColorsFor(it) }.toSet()
        assertTrue(colours.size > 1)
    }

    @Test
    fun searchMatchesArtistAlbumTrackGenreMoodAndVibe() {
        val artists = LIBRARY.map { it.artistTitle }.distinct()
            .map { com.fourgeailabs.prismtune.data.PlexArtist(key = it, title = it) }
        val byArtist = searchLibrary("toby keith", LIBRARY, artists)
        assertTrue(byArtist.artists.contains("Toby Keith"))
        assertEquals(3, byArtist.tracks.size)
        val byGenre = searchLibrary("country", LIBRARY, artists)
        assertTrue(byGenre.genres.contains("Country"))
        val byMood = searchLibrary("chill", LIBRARY, artists)
        assertTrue(byMood.moods.contains("Chill"))
        // 1.5.4: a vibe match must come from a real Plex style
        // tag on a track, never from a genre-inferred vibe.
        val vibeLib = listOf(track("Road Song", "Drive Artist", styles = listOf("Road Trip")))
        val byVibe = searchLibrary("road trip", vibeLib, emptyList())
        assertTrue(byVibe.vibes.contains("Road Trip"))
        val byTitle = searchLibrary("RED DIRT", LIBRARY, artists)
        assertEquals(listOf("Red Dirt Road"), byTitle.tracks.map { it.title })
        assertTrue(searchLibrary("zzz-no-such-thing", LIBRARY, artists).isEmpty)
        assertTrue(searchLibrary("", LIBRARY, artists).isEmpty)
    }

    @Test
    fun scopeGroupingCoversArtistAlbumAndGenre() {
        val byArtist = groupByArtist(LIBRARY)
        assertEquals(3, byArtist["Toby Keith"]?.size)
        val byAlbum = groupByAlbum(LIBRARY)
        assertTrue(byAlbum.containsKey("Shock'n Y'all"))
        val byGenre = groupByGenre(LIBRARY)
        assertEquals(3, byGenre["Country"]?.size)
        // Many-to-many: the Pop/Rock track appears under both genres.
        assertTrue(byGenre["Pop"]?.any { it.title == "Happy Song" } == true)
        assertTrue(byGenre["Rock"]?.any { it.title == "Happy Song" } == true)
    }

    @Test
    fun voiceSearchQueuesArtistFirstThenTrackThenNothing() {
        val played = resolveVoiceSearch("Toby Keith", LIBRARY, libraryLoaded = true)
        assertTrue(played is VoicePlayOutcome.Played)
        assertEquals(
            listOf("Red Dirt Road", "Should've Been a Cowboy", "Courtesy of the Red, White and Blue"),
            (played as VoicePlayOutcome.Played).tracks.map { it.title },
        )
        // Case-insensitive, contains.
        assertTrue(resolveVoiceSearch("toby", LIBRARY, true) is VoicePlayOutcome.Played)
        // No artist match: fall back to track title.
        val byTrack = resolveVoiceSearch("Happy Song", LIBRARY, true)
        assertTrue(byTrack is VoicePlayOutcome.Played)
        // No match: never a random track.
        assertEquals(VoicePlayOutcome.NoMatch, resolveVoiceSearch("Nobody At All", LIBRARY, true))
        // Library not loaded: never a guess, playback stays stopped.
        assertEquals(VoicePlayOutcome.NotLoaded, resolveVoiceSearch("Toby Keith", LIBRARY, false))
        assertEquals(VoicePlayOutcome.NotLoaded, resolveVoiceSearch("Toby Keith", emptyList(), false))
    }
}
