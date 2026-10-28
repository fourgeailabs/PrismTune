package com.fourgeailabs.prismtune.data

/**
 * "The Feels" (1.0.4) — moods, vibes, genres, filtering, search, and
 * voice play-from-search, all as pure functions over the REAL loaded
 * library. No demo, sample, fake, or placeholder tracks, artists, or
 * songs anywhere in this file: every card and every count is built
 * from PlexTrack objects the app actually loaded from the user's server.
 *
 * 1.5.4 REAL PLEX MOODS ONLY — Eric, 2026-10-27 04:49,
 * with Plex Amp screenshots as the reference: Plex's own
 * Moods list is hundreds of real tags (Acerbic, Aggressive,
 * Amiable & Good-Natured, Angst-Ridden, ...), counted in
 * ALBUMS by Plex Amp. Eric prefers SONGS, so PrismTune
 * counts TRACKS, not albums — but the tags themselves must
 * be Plex's, not ours. The screenshots are NOT the whole
 * list (Eric said so), so no fixed list can be complete:
 * the taxonomy is now DYNAMIC — whatever Mood/Style tags
 * Plex actually returns on the loaded tracks, in Plex's
 * own spelling, counted per track.
 * Mapping, exactly one rule:
 *   A track's Plex "Mood" tags are its moods, its Plex
 *   "Style" tags are its vibes, verbatim (trimmed,
 *   case-insensitively deduplicated). Genre is NEVER
 *   turned into a mood. The old GENRE_FEELS table and
 *   the curated 193+193 taxonomy are RETIRED below and
 *   are no longer consulted — they are what ballooned
 *   Happy/Powerful/Rebellious/Upbeat to an identical
 *   15,352: every "Pop/Rock" track got both the Pop set
 *   and the Rock set from one genre string, whether
 *   Plex ever tagged it with any of those moods or not.
 * A track with no Plex Mood/Style tags gets NO mood/vibe
 * cards and is NEVER assigned a random or genre-guessed
 * mood. If a library load returns no Mood/Style tags at
 * all, Moods shows an honest empty message, not
 * genre-inferred cards.
 *
 * Many-to-many throughout: a track keeps SETS of genres, moods, and
 * vibes, so one track can be in several moods, several vibes, and
 * several genres at once, and appears under each of them.
 */

private val NON_ALNUM_REGEX = Regex("[^a-z0-9 ]+")
private val WHITESPACE_REGEX = Regex("\\s+")

private fun norm(s: String): String =
    s.lowercase().trim().replace(NON_ALNUM_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()

/**
 * 1.1.3: cached taxonomy mapping. mapTrackFeels() scans 193 moods +
 * 193 vibes with regex normalisation per tag, so calling it fresh
 * for every track on every chunk append / card tap is quadratic
 * and was the Moods freeze-then-crash at 10k+ tracks. A track's
 * genres/moods/styles never change during a library load, so the
 * result is cached by track key (ratingKey) once and reused by
 * feelsCounts() and filterTracks(). Cache is bounded by the library
 * itself (one small entry per loaded track), never fake data.
 */
private val feelsCache = java.util.concurrent.ConcurrentHashMap<String, FeelsForTrack>()

/**
 * Moods mapping version. 1 = everything up to 1.5.3
 * (curated taxonomy + genre inference). 2 = 1.5.4
 * (real Plex tags only). Saved counts from version 1
 * are INVALID under version 2 and must never be shown:
 * they are the 15k genre-inferred counts. Both the
 * disk counts file and the metadata-cache snapshot
 * carry this version, and any older version decodes
 * to empty, forcing a recompute/rescan.
 */
const val MOODS_MAPPING_VERSION = 2

fun mapTrackFeelsCached(track: PlexTrack): FeelsForTrack {
    val key = track.key
    if (key.isBlank()) return mapTrackFeels(track)
    return feelsCache.getOrPut(key) { mapTrackFeels(track) }
}

/**
 * 1.5.0 MOOD COUNT FIX: the old matcher was substring
 * in BOTH directions (t.contains(m) || m.contains(t)).
 * A Plex tag like "rock" therefore matched every taxonomy
 * term containing "rock" or contained in "rock", and a
 * tag like "happy" matched "Happy" plus any longer term
 * containing it — and, worse, a single genre keyword
 * like "pop" then also fired GENRE_FEELS for Happy /
 * Upbeat / Playful on EVERY pop track, so every broad
 * mood ballooned to ~15,000 tracks on a ~45,000-track
 * library. Eric, 2026-10-27: "I don't think there are
 * 15,177 happy songs". New rule: Plex tags match
 * EXACTLY (after normalisation) only. Genre inference
 * stays, but it is a separate, explicit step — a track
 * gets a mood from a genre ONLY via the GENRE_FEELS
 * table, never via substring tag matching.
 */
private fun matchesTaxonomy(tag: String, term: String): Boolean {
    val t = norm(tag)
    val m = norm(term)
    if (t.isEmpty() || m.isEmpty()) return false
    return t == m
}

/** Curated mood taxonomy — emotional states. Written out in full, not generated. */
val FEEL_MOODS: List<String> = listOf(
    "Happy", "Joyful", "Cheerful", "Upbeat", "Energetic", "Pumped Up", "Excited", "Euphoric",
    "Ecstatic", "Elated", "Blissful", "Content", "Peaceful", "Calm", "Relaxed", "Serene",
    "Tranquil", "Dreamy", "Sleepy", "Mellow", "Chill", "Laid Back", "Easygoing", "Carefree",
    "Playful", "Fun", "Silly", "Goofy", "Whimsical", "Quirky", "Confident", "Bold",
    "Fearless", "Powerful", "Strong", "Invincible", "Triumphant", "Victorious", "Proud",
    "Hopeful", "Optimistic", "Inspired", "Motivated", "Driven", "Focused", "Determined",
    "Defiant", "Rebellious", "Edgy", "Dark", "Moody", "Brooding", "Melancholy", "Sad",
    "Heartbroken", "Lonely", "Homesick", "Nostalgic", "Wistful", "Bittersweet", "Tender",
    "Romantic", "Loving", "Affectionate", "Passionate", "Sensual", "Intimate", "Flirty",
    "Sassy", "Sarcastic", "Snarky", "Angry", "Furious", "Frustrated", "Anxious", "Nervous",
    "Worried", "Stressed", "Overwhelmed", "Restless", "Agitated", "Intense", "Fierce",
    "Aggressive", "Savage", "Wild", "Free", "Liberated", "Adventurous", "Daring", "Brave",
    "Curious", "Wonder", "Awestruck", "Mystical", "Magical", "Enchanted", "Ethereal",
    "Haunting", "Eerie", "Spooky", "Creepy", "Sinister", "Ominous", "Epic", "Heroic",
    "Majestic", "Grand", "Cinematic", "Dramatic", "Theatrical", "Emotional", "Moving",
    "Touching", "Soulful", "Spiritual", "Meditative", "Zen", "Reflective", "Thoughtful",
    "Contemplative", "Pensive", "Sentimental", "Yearning", "Longing", "Sorrowful",
    "Grieving", "Comforting", "Soothing", "Healing", "Warm", "Cozy", "Sunny", "Bright",
    "Radiant", "Glowing", "Sparkling", "Shimmering", "Groovy", "Funky", "Jazzy", "Bluesy",
    "Folky", "Smooth", "Silky", "Velvet", "Hypnotic", "Trippy", "Psychedelic", "Cosmic",
    "Spacey", "Futuristic", "Retro", "Vintage", "Old School", "Classic", "Timeless",
    "Elegant", "Sophisticated", "Classy", "Bouncy", "Bubbly", "Electric", "Charged",
    "High Energy", "Low Key", "Smoky", "Sultry", "Midnight", "Golden", "Rosy", "Breezy",
    "Airy", "Floaty", "Weightless", "Grounded", "Earthy", "Raw", "Rugged", "Polished",
    "Crisp", "Fresh", "Youthful", "Young at Heart", "Wise", "Gentle", "Soft", "Delicate",
    "Fragile", "Vulnerable", "Honest", "Sincere", "Earnest",
)

/** Curated vibe taxonomy — situations and contexts. Written out in full, not generated. */
val FEEL_VIBES: List<String> = listOf(
    "Late Night Drive", "Road Trip", "Workout", "Rainy Day", "Golden Hour", "Morning Coffee",
    "Sunday Morning", "Friday Night", "Saturday Night", "Party Time", "Dance Floor",
    "Club Night", "Beach Day", "Pool Party", "Summer Road Trip", "Winter Cabin", "Snow Day",
    "Campfire", "Stargazing", "Sunset Drive", "Sunrise Run", "Morning Run", "Gym Session",
    "Yoga Flow", "Meditation", "Study Session", "Work Focus", "Coding Night", "Cooking Dinner",
    "Dinner Party", "Wine Night", "Date Night", "First Date", "Anniversary", "Wedding Day",
    "Cross Country", "Desert Drive", "Mountain Drive", "Coastal Drive", "City Lights",
    "Downtown", "Small Town", "Hometown", "Homecoming", "Backyard BBQ", "Tailgate",
    "Game Day", "Victory Lap", "Chill Evening", "Lazy Sunday", "Rainy Sunday", "Thunderstorm",
    "Snowstorm", "Autumn Leaves", "Spring Bloom", "Summer Heat", "Winter Chill",
    "Bonfire Night", "Rooftop", "Balcony", "Porch Swing", "Window Seat", "Train Ride",
    "Plane Ride", "Airport", "Hotel Room", "Roadside Diner", "Neon Nights", "Disco Night",
    "Karaoke Night", "Open Mic", "Live Concert", "Festival", "Front Row", "Backstage",
    "Studio Session", "Rehearsal", "Soundcheck", "Tour Bus", "Van Life", "RV Trip",
    "Camping Trip", "Hiking Trail", "Fishing Trip", "Lake House", "Cabin Weekend",
    "Ski Trip", "Surf Session", "Skate Park", "Bike Ride", "Evening Walk", "Dog Walk",
    "Quiet House", "Empty House", "New Apartment", "Moving Day", "Graduation", "Prom Night",
    "Reunion", "Family Dinner", "Holiday Morning", "Christmas Morning", "New Years Eve",
    "Birthday Party", "Kids Road Trip", "School Run", "Homework Time", "Bedtime",
    "Story Time", "Lullaby Time", "Nap Time", "Coffee Run", "Commute", "Traffic Jam",
    "Long Commute", "Grocery Run", "Errand Run", "Cleaning Day", "Laundry Day",
    "Garage Time", "Workshop", "Garden Time", "Yard Work", "Pool Day", "Spa Day",
    "Self Care", "Bubble Bath", "Reading Nook", "Book Club", "Movie Night", "TV Binge",
    "Game Night", "Board Game", "Video Game", "Arcade", "Bowling Night", "Drive In Movie",
    "Picnic", "Park Day", "Playground", "Zoo Day", "Museum Day", "Art Gallery",
    "Library Day", "Church Sunday", "Sunday Drive", "Monday Blues", "Throwback Thursday",
    "Flashback Friday", "Weekend Vibes", "End of Day", "Start of Day", "Midnight Snack",
    "2 AM", "3 AM Thoughts", "Insomnia", "Dreamland", "Night Owl", "Early Bird",
    "Golden Morning", "Blue Hour", "Twilight", "Dusk", "Dawn Patrol", "Starlight",
    "Moonlight", "Candlelight", "Firelight", "Neon Glow", "City Glow", "Ocean Breeze",
    "Sea Breeze", "Mountain Air", "Desert Air", "Forest Walk", "River Walk", "Lakeside",
    "Riverside", "Seaside", "Island Time", "Tropical", "Paradise", "Vacation Mode",
    "Staycation", "Getaway", "Escape", "Open Road", "Highway", "Freeway", "Back Roads",
    "Country Roads", "Dirt Road", "Gravel Road",
)

/**
 * RETIRED in 1.5.4 — DO NOT USE. Genre-keyword -> (moods, vibes).
 * This table is what inflated mood counts to ~15k: it turned
 * every Pop track into Happy/Upbeat/Playful and every Rock
 * track into Energetic/Powerful/Rebellious, and a "Pop/Rock"
 * genre string fired BOTH. Kept in the file only as a record
 * of what was removed; mapTrackFeels() no longer reads it.
 */
val GENRE_FEELS: Map<String, Pair<List<String>, List<String>>> = mapOf(
    "rock" to (listOf("Energetic", "Powerful", "Rebellious") to listOf("Road Trip", "Workout")),
    "metal" to (listOf("Intense", "Fierce", "Aggressive", "Dark") to listOf("Workout", "Gym Session")),
    "punk" to (listOf("Rebellious", "Defiant", "Edgy") to listOf("Skate Park", "Tailgate")),
    "pop" to (listOf("Happy", "Upbeat", "Playful") to listOf("Party Time", "Dance Floor")),
    "dance" to (listOf("Energetic", "Euphoric", "Electric") to listOf("Dance Floor", "Club Night", "Party Time")),
    "electronic" to (listOf("Energetic", "Euphoric", "Electric") to listOf("Dance Floor", "Club Night")),
    "edm" to (listOf("Energetic", "Euphoric", "Electric") to listOf("Dance Floor", "Club Night")),
    "house" to (listOf("Energetic", "Groovy") to listOf("Dance Floor", "Club Night")),
    "techno" to (listOf("Intense", "Hypnotic", "Electric") to listOf("Club Night", "Dance Floor")),
    "hip hop" to (listOf("Confident", "Bold", "Sassy") to listOf("City Lights", "Downtown")),
    "hip-hop" to (listOf("Confident", "Bold", "Sassy") to listOf("City Lights", "Downtown")),
    "rap" to (listOf("Confident", "Bold", "Fierce") to listOf("City Lights", "Downtown")),
    "r&b" to (listOf("Romantic", "Sensual", "Smooth") to listOf("Date Night", "Late Night Drive")),
    "soul" to (listOf("Soulful", "Warm", "Romantic") to listOf("Date Night", "Late Night Drive")),
    "jazz" to (listOf("Sophisticated", "Smooth", "Mellow") to listOf("Wine Night", "Dinner Party")),
    "blues" to (listOf("Melancholy", "Soulful", "Bluesy") to listOf("Rainy Day", "Late Night Drive")),
    "country" to (listOf("Nostalgic", "Homesick", "Tender") to listOf("Small Town", "Hometown", "Country Roads")),
    "folk" to (listOf("Reflective", "Gentle", "Earthy") to listOf("Campfire", "Forest Walk")),
    "classical" to (listOf("Elegant", "Majestic", "Peaceful") to listOf("Reading Nook", "Study Session")),
    "ambient" to (listOf("Calm", "Dreamy", "Ethereal") to listOf("Meditation", "Dreamland")),
    "lofi" to (listOf("Chill", "Mellow", "Low Key") to listOf("Study Session", "Rainy Day")),
    "lo-fi" to (listOf("Chill", "Mellow", "Low Key") to listOf("Study Session", "Rainy Day")),
    "reggae" to (listOf("Carefree", "Relaxed", "Sunny") to listOf("Beach Day", "Island Time")),
    "latin" to (listOf("Passionate", "Playful", "Groovy") to listOf("Dance Floor", "Party Time")),
    "indie" to (listOf("Quirky", "Dreamy", "Nostalgic") to listOf("Road Trip", "Morning Coffee")),
    "alternative" to (listOf("Moody", "Dreamy", "Edgy") to listOf("Road Trip", "Late Night Drive")),
    "gospel" to (listOf("Hopeful", "Spiritual", "Soulful") to listOf("Church Sunday", "Sunday Morning")),
    "christian" to (listOf("Hopeful", "Spiritual") to listOf("Church Sunday", "Sunday Morning")),
    "kids" to (listOf("Playful", "Fun", "Silly") to listOf("Kids Road Trip", "Playground")),
    "children" to (listOf("Playful", "Fun", "Silly") to listOf("Kids Road Trip", "Playground")),
    "soundtrack" to (listOf("Cinematic", "Epic", "Dramatic") to listOf("Movie Night", "Drive In Movie")),
    "score" to (listOf("Cinematic", "Epic", "Dramatic") to listOf("Movie Night", "Drive In Movie")),
    "christmas" to (listOf("Cozy", "Warm", "Nostalgic") to listOf("Christmas Morning", "Holiday Morning")),
    "holiday" to (listOf("Cozy", "Warm", "Nostalgic") to listOf("Holiday Morning", "Christmas Morning")),
)

/** A track's many-to-many feels: sets, never a single bucket. */
data class FeelsForTrack(
    val genres: Set<String> = emptySet(),
    val moods: Set<String> = emptySet(),
    val vibes: Set<String> = emptySet(),
)

/** Map one loaded track to its genres, moods, and vibes — real Plex tags only (1.5.4). */
fun mapTrackFeels(track: PlexTrack): FeelsForTrack {
    val genres = track.genres.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    // Verbatim Plex tags, trimmed, deduplicated case-insensitively
    // (first spelling wins, so "happy" and "Happy" are one card).
    fun clean(tags: List<String>): Set<String> {
        val seen = mutableSetOf<String>()
        val out = mutableSetOf<String>()
        for (raw in tags) {
            val tag = raw.trim()
            if (tag.isBlank()) continue
            if (seen.add(tag.lowercase())) out.add(tag)
        }
        return out
    }
    return FeelsForTrack(
        genres = genres,
        moods = clean(track.moods),
        vibes = clean(track.styles),
    )
}

/** Card counts from the loaded library only. A term with no tracks never appears. */
fun feelsCounts(tracks: List<PlexTrack>): Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>> {
    val genreCounts = mutableMapOf<String, Int>()
    val moodCounts = mutableMapOf<String, Int>()
    val vibeCounts = mutableMapOf<String, Int>()
    for (track in tracks) {
        val feels = mapTrackFeelsCached(track)
        for (g in feels.genres) genreCounts[g] = (genreCounts[g] ?: 0) + 1
        for (m in feels.moods) moodCounts[m] = (moodCounts[m] ?: 0) + 1
        for (v in feels.vibes) vibeCounts[v] = (vibeCounts[v] ?: 0) + 1
    }
    return Triple(genreCounts, moodCounts, vibeCounts)
}

/**
 * 1.1.0 Moods incremental accumulator (pure, unit-testable).
 * The Moods tab must never do a single full-library fetch on a
 * 45k library: chunks of 500 are aggregated per chunk, so cards
 * render as chunks arrive and a failed chunk keeps everything
 * already loaded instead of blanking or crashing the tab.
 * Counts are aggregated, not recomputed over the whole list
 * after every chunk, and no duplicate full track objects are
 * held per card — cards are (name -> count) only.
 */
class MoodsChunkAccumulator {
    var genreCounts: Map<String, Int> = emptyMap()
        private set
    var moodCounts: Map<String, Int> = emptyMap()
        private set
    var vibeCounts: Map<String, Int> = emptyMap()
        private set
    var loadedCount: Int = 0
        private set
    var chunkError: String? = null
        private set

    fun addChunk(tracks: List<PlexTrack>) {
        val (g, m, v) = feelsCounts(tracks)
        genreCounts = merge(genreCounts, g)
        moodCounts = merge(moodCounts, m)
        vibeCounts = merge(vibeCounts, v)
        loadedCount += tracks.size
    }

    /** 1.1.4: restore counts saved to disk before a crash, so a
     *  resumed scan continues from the saved offset, not from 0. */
    fun restore(
        genres: Map<String, Int>, moods: Map<String, Int>, vibes: Map<String, Int>, loaded: Int,
    ) {
        genreCounts = genres
        moodCounts = moods
        vibeCounts = vibes
        loadedCount = loaded
    }

    fun addFailure(message: String) {
        // Non-fatal: keep every card already loaded.
        if (chunkError == null) chunkError = message
    }

    private fun merge(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
        val out = a.toMutableMap()
        for ((k, v) in b) out[k] = (out[k] ?: 0) + v
        return out
    }
}

/** 1.1.4: encode/decode Moods counts for disk persistence, so a
 *  crash mid-scan resumes from the saved offset instead of 0.
 *  Format: genre lines, then a blank separator, mood lines, then
 *  vibe lines, each "name\tcount". Names with tabs/newlines are
 *  escaped; counts are never invented. */
fun encodeMoodsCounts(
    genres: Map<String, Int>,
    moods: Map<String, Int>,
    vibes: Map<String, Int>,
): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    fun block(m: Map<String, Int>) = m.entries.sortedBy { it.key }
        .joinToString("\n") { "${esc(it.key)}\t${it.value}" }
    // 1.5.4: version header first. Old (unversioned) files decode
    // to empty below, so pre-1.5.4 genre-inferred counts can
    // never be displayed or resumed from.
    return "MOODS_MAPPING_V" + MOODS_MAPPING_VERSION + "\n" + block(genres) + "\n\n" + block(moods) + "\n\n" + block(vibes)
}

fun decodeMoodsCounts(raw: String?): Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>> {
    if (raw.isNullOrBlank()) return Triple(emptyMap(), emptyMap(), emptyMap())
    // 1.5.4 invalidation: no version header, or a different version,
    // means pre-1.5.4 counts — discard, do not display, do not resume.
    val header = "MOODS_MAPPING_V" + MOODS_MAPPING_VERSION + "\n"
    if (!raw.startsWith(header)) return Triple(emptyMap(), emptyMap(), emptyMap())
    val body = raw.removePrefix(header)
    fun unesc(s: String) = s.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
    fun block(text: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (line in text.lines()) {
            val parts = line.split("\t")
            if (parts.size == 2) {
                val v = parts[1].toIntOrNull()
                if (v != null) out[unesc(parts[0])] = v
            }
        }
        return out
    }
    val sections = body.split("\n\n")
    return Triple(
        if (sections.isNotEmpty()) block(sections[0]) else emptyMap(),
        if (sections.size > 1) block(sections[1]) else emptyMap(),
        if (sections.size > 2) block(sections[2]) else emptyMap(),
    )
}

/** Genres actually present in the loaded library — never a static list. */
fun availableGenres(tracks: List<PlexTrack>): Set<String> =
    tracks.flatMap { mapTrackFeelsCached(it).genres }.toSet()

/**
 * Filter tracks by the selected cards.
 * Only Show ON (default): a track must contain ALL selected cards —
 * every selected genre, every selected mood, and every selected vibe.
 * Only Show OFF: a track must match ANY selected card (union).
 * Nothing selected: the full library.
 */
fun filterTracks(
    tracks: List<PlexTrack>,
    selectedGenres: Set<String> = emptySet(),
    selectedMoods: Set<String> = emptySet(),
    selectedVibes: Set<String> = emptySet(),
    onlyShow: Boolean = true,
): List<PlexTrack> {
    if (selectedGenres.isEmpty() && selectedMoods.isEmpty() && selectedVibes.isEmpty()) return tracks
    return tracks.filter { track ->
        val feels = mapTrackFeelsCached(track)
        if (onlyShow) {
            selectedGenres.all { it in feels.genres } &&
                selectedMoods.all { it in feels.moods } &&
                selectedVibes.all { it in feels.vibes }
        } else {
            selectedGenres.any { it in feels.genres } ||
                selectedMoods.any { it in feels.moods } ||
                selectedVibes.any { it in feels.vibes }
        }
    }
}

/** Group helpers for the Artist / Genre / Album result scopes. */
fun groupByArtist(tracks: List<PlexTrack>): Map<String, List<PlexTrack>> =
    tracks.groupBy { it.artistTitle.ifBlank { "Unknown Artist" } }

fun groupByAlbum(tracks: List<PlexTrack>): Map<String, List<PlexTrack>> =
    tracks.groupBy { it.albumTitle.ifBlank { "Unknown Album" } }

fun groupByGenre(tracks: List<PlexTrack>): Map<String, List<PlexTrack>> {
    val out = mutableMapOf<String, MutableList<PlexTrack>>()
    for (track in tracks) {
        for (genre in mapTrackFeelsCached(track).genres) {
            out.getOrPut(genre) { mutableListOf() }.add(track)
        }
    }
    return out
}

/**
 * I'm Feeling Lucky: pick one track from the CURRENT filtered set.
 * Seedable so unit tests are deterministic. Returns null for an empty
 * set — it never picks outside the filter and never invents a track.
 */
fun pickLucky(filtered: List<PlexTrack>, random: kotlin.random.Random = kotlin.random.Random.Default): PlexTrack? {
    if (filtered.isEmpty()) return null
    return filtered[random.nextInt(filtered.size)]
}

/** Result scope for The Feels results list. */
enum class FeelsScope { ARTIST, GENRE, TRACK, ALBUM }

/** One grouped/sectioned search result set over the loaded library only. */
data class LibrarySearchResults(
    val artists: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val tracks: List<PlexTrack> = emptyList(),
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val vibes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() =
        artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
            genres.isEmpty() && moods.isEmpty() && vibes.isEmpty()
}

/**
 * Search the loaded library only, case-insensitively. A query matches
 * artist name, album title, track title, genre, and the mapped moods /
 * vibes for each track. Empty query = empty results (never the full
 * library disguised as "results", and never demo tracks).
 */
fun searchLibrary(query: String, tracks: List<PlexTrack>, artists: List<PlexArtist>): LibrarySearchResults {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return LibrarySearchResults()
    val matchedTracks = tracks.filter { track ->
        val feels = mapTrackFeelsCached(track)
        track.title.lowercase().contains(q) ||
            track.artistTitle.lowercase().contains(q) ||
            track.albumTitle.lowercase().contains(q) ||
            feels.genres.any { it.lowercase().contains(q) } ||
            feels.moods.any { it.lowercase().contains(q) } ||
            feels.vibes.any { it.lowercase().contains(q) }
    }
    val matchedArtists = (artists.map { it.title } + matchedTracks.map { it.artistTitle })
        .filter { it.isNotBlank() && it.lowercase().contains(q) }.distinct()
    val matchedAlbums = (tracks.map { it.albumTitle } + matchedTracks.map { it.albumTitle })
        .filter { it.isNotBlank() && it.lowercase().contains(q) }.distinct()
    val matchedGenres = matchedTracks.flatMap { mapTrackFeelsCached(it).genres }
        .filter { it.lowercase().contains(q) }.distinct()
    val matchedMoods = matchedTracks.flatMap { mapTrackFeelsCached(it).moods }
        .filter { it.lowercase().contains(q) }.distinct()
    val matchedVibes = matchedTracks.flatMap { mapTrackFeelsCached(it).vibes }
        .filter { it.lowercase().contains(q) }.distinct()
    return LibrarySearchResults(
        artists = matchedArtists,
        albums = matchedAlbums,
        tracks = matchedTracks,
        genres = matchedGenres,
        moods = matchedMoods,
        vibes = matchedVibes,
    )
}

/** Voice play-from-search outcome. No random fallback, ever. */
sealed interface VoicePlayOutcome {
    data class Played(val tracks: List<PlexTrack>) : VoicePlayOutcome
    data object NoMatch : VoicePlayOutcome
    data object NotLoaded : VoicePlayOutcome
}

/**
 * Voice search (e.g. "Hey play Toby Keith on PrismTune", with the
 * assistant having already stripped "play" / "on PrismTune"): artist
 * match first (case-insensitive contains, library order), then track
 * title, then album title. Empty library = NotLoaded, never a guess.
 * The query is used as given (trimmed only) — nothing is stripped here.
 */
fun resolveVoiceSearch(query: String, tracks: List<PlexTrack>, libraryLoaded: Boolean): VoicePlayOutcome {
    if (!libraryLoaded) return VoicePlayOutcome.NotLoaded
    val q = query.trim().lowercase()
    if (q.isEmpty() || tracks.isEmpty()) return VoicePlayOutcome.NoMatch
    val byArtist = tracks.filter { it.artistTitle.lowercase().contains(q) }
    if (byArtist.isNotEmpty()) return VoicePlayOutcome.Played(byArtist)
    val byTrack = tracks.filter { it.title.lowercase().contains(q) }
    if (byTrack.isNotEmpty()) return VoicePlayOutcome.Played(byTrack)
    val byAlbum = tracks.filter { it.albumTitle.lowercase().contains(q) }
    if (byAlbum.isNotEmpty()) return VoicePlayOutcome.Played(byAlbum)
    return VoicePlayOutcome.NoMatch
}

/**
 * GlossyCard colour: deterministic per card name. A fixed palette of
 * saturated gradient pairs (top light, bottom deep, as ARGB longs so
 * this stays pure and unit-testable); the same name always hashes to
 * the same pair, so the same mood always gets the same colour.
 */
val GLOSS_PALETTE: List<Pair<Long, Long>> = listOf(
    0xFFFF6B6BL to 0xFFB71C1CL, 0xFFFFB020L to 0xFFE65100L, 0xFFFFEA00L to 0xFFF57F17L,
    0xFF69F0AEL to 0xFF00BFA5L, 0xFF00E5FFL to 0xFF006064L, 0xFF40C4FFL to 0xFF0D47A1L,
    0xFF7C4DFFL to 0xFF311B92L, 0xFFE040FBL to 0xFF6A1B9AL, 0xFFFF4081L to 0xFF880E4FL,
    0xFF76FF03L to 0xFF33691EL, 0xFFFF9100L to 0xFFBF360CL, 0xFF18FFFFL to 0xFF01579BL,
    0xFFB2FF59L to 0xFF1B5E20L, 0xFFFF80ABL to 0xFFAD1457L, 0xFF8C9EFFL to 0xFF1A237EL,
    0xFFEA80FCL to 0xFF4A148CL, 0xFFA7FFEBL to 0xFF004D40L, 0xFFFFD180L to 0xFF4E342EL,
    0xFFB9F6CAL to 0xFF2E7D32L, 0xFF81D4FAL to 0xFF01579BL,
)

fun glossColorsFor(name: String): Pair<Long, Long> {
    if (GLOSS_PALETTE.isEmpty()) return 0xFFFFB020L to 0xFFE65100L
    val index = (name.hashCode().toLong() and 0x7FFFFFFFL) % GLOSS_PALETTE.size
    return GLOSS_PALETTE[index.toInt()]
}
