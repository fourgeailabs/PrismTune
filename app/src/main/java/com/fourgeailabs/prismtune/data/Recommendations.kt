package com.fourgeailabs.prismtune.data

/**
 * 1.2.1 recommendations, from real history only — never invented.
 *
 * Recently Played Moods: moods/vibes/genres from the tracks the
 * user actually played recently, most frequent first.
 *
 * Recommended Moods: the same signal, but as a discovery list —
 * moods/vibes that appear in recently played tracks, ranked by
 * how often they appear, so a mood the user keeps returning to
 * surfaces first.
 *
 * Recommended Playlist: tracks from the loaded library that
 * share an artist, album, genre, mood, or vibe with recently
 * played tracks, excluding tracks already recently played,
 * ranked by how many signals they share. If there is no recent
 * history, the result is empty with a plain reason — never a
 * fake "recommended" list.
 *
 * All functions are pure and unit-testable.
 */
fun recentlyPlayedMoods(recent: List<PlexTrack>, limit: Int = 10): List<String> {
    val counts = mutableMapOf<String, Int>()
    for (t in recent) {
        val feels = mapTrackFeelsCached(t)
        for (name in feels.moods + feels.vibes) {
            counts[name] = (counts[name] ?: 0) + 1
        }
        for (g in t.genres) {
            counts[g] = (counts[g] ?: 0) + 1
        }
    }
    return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
}

fun recommendedMoods(recent: List<PlexTrack>, library: List<PlexTrack>, limit: Int = 10): List<String> {
    if (recent.isEmpty() || library.isEmpty()) return emptyList()
    val recentSignals = recentlyPlayedMoods(recent, limit = 100).toSet()
    if (recentSignals.isEmpty()) return emptyList()
    val counts = mutableMapOf<String, Int>()
    for (t in library) {
        val feels = mapTrackFeelsCached(t)
        for (name in feels.moods + feels.vibes + t.genres) {
            if (name in recentSignals) counts[name] = (counts[name] ?: 0) + 1
        }
    }
    return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
}

/** 1.2.3 "Recommended for you": songs, albums, moods, and
 *  genres, from recently played tracks AND recently searched
 *  queries — so searching for an artist, album, genre, or mood
 *  diversifies recommendations, not just playing. Songs and
 *  albums are real library items only, never invented; a
 *  search query that matches no library item contributes no
 *  song/album, only a mood/genre name if it is one. Empty
 *  history (no plays, no searches) gives an empty result. */
data class RecommendedForYou(
    val songs: List<PlexTrack> = emptyList(),
    val albums: List<PlexAlbum> = emptyList(),
    val moods: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

fun recommendedForYou(
    recent: List<PlexTrack>,
    searchQueries: List<String>,
    library: List<PlexTrack>,
): RecommendedForYou {
    if ((recent.isEmpty() && searchQueries.isEmpty()) || library.isEmpty()) return RecommendedForYou()
    val recentKeys = recent.map { it.key }.toSet()
    val recentArtists = recent.map { it.artistTitle }.filter { it.isNotBlank() }.toSet()
    val recentAlbums = recent.map { it.albumTitle }.filter { it.isNotBlank() }.toSet()
    val recentSignals = recentlyPlayedMoods(recent, limit = 100).toSet()
    val queryLower = searchQueries.map { it.lowercase().trim() }.filter { it.isNotBlank() }
    val queryArtists = library.map { it.artistTitle }.filter { it.isNotBlank() }.distinct()
        .filter { a -> queryLower.any { q -> a.lowercase().contains(q) || q.contains(a.lowercase()) } }.toSet()
    val queryAlbums = library.map { it.albumTitle }.filter { it.isNotBlank() }.distinct()
        .filter { a -> queryLower.any { q -> a.lowercase().contains(q) || q.contains(a.lowercase()) } }.toSet()
    val scored = mutableListOf<Pair<PlexTrack, Int>>()
    for (t in library) {
        if (t.key in recentKeys) continue
        var score = 0
        if (t.artistTitle in recentArtists) score += 3
        if (t.albumTitle in recentAlbums) score += 2
        if (t.artistTitle in queryArtists) score += 3
        if (t.albumTitle in queryAlbums) score += 2
        val feels = mapTrackFeelsCached(t)
        score += (feels.moods + feels.vibes + t.genres).count { it in recentSignals }
        if (queryLower.any { q -> t.title.lowercase().contains(q) || t.artistTitle.lowercase().contains(q) || t.albumTitle.lowercase().contains(q) }) score += 2
        if (score > 0) scored.add(t to score)
    }
    val songs = scored.sortedByDescending { it.second }.take(12).map { it.first }
    // Albums derived from the recommended songs + library albums
    // matching recent/searched artists, deduplicated by album key.
    val albumByKey = mutableMapOf<String, PlexAlbum>()
    for (t in songs) {
        val key = t.albumKey ?: t.albumTitle
        if (key.isNullOrBlank()) continue
        albumByKey.putIfAbsent(key, PlexAlbum(key = t.albumKey ?: t.albumTitle, title = t.albumTitle, artistTitle = t.artistTitle, thumb = t.albumThumb ?: t.thumb))
    }
    for (t in library) {
        if (albumByKey.size >= 12) break
        if (t.artistTitle in recentArtists || t.artistTitle in queryArtists || t.albumTitle in recentAlbums || t.albumTitle in queryAlbums) {
            val key = t.albumKey ?: t.albumTitle
            if (key.isNullOrBlank()) continue
            albumByKey.putIfAbsent(key, PlexAlbum(key = t.albumKey ?: t.albumTitle, title = t.albumTitle, artistTitle = t.artistTitle, thumb = t.albumThumb ?: t.thumb))
        }
    }
    // 1.5.4: match search queries against the library's REAL
    // mood/vibe terms, not the retired curated taxonomy, so
    // searching "Acerbic" (a real Plex tag from the user's Plex
    // Amp screenshots) surfaces it as a mood.
    val libraryTerms = library.flatMap { t ->
        val feels = mapTrackFeelsCached(t)
        feels.moods + feels.vibes
    }.distinct()
    val moods = (recommendedMoods(recent, library) + queryLower.filter { q ->
        q in libraryTerms.map { it.lowercase() }
    }.map { q -> libraryTerms.firstOrNull { it.lowercase() == q } ?: q })
        .distinct().take(10)
    val genreCounts = mutableMapOf<String, Int>()
    for (t in recent) for (g in t.genres) genreCounts[g] = (genreCounts[g] ?: 0) + 1
    for (t in library) for (g in t.genres) {
        if (queryLower.any { q -> g.lowercase().contains(q) || q.contains(g.lowercase()) }) genreCounts[g] = (genreCounts[g] ?: 0) + 1
    }
    val genres = genreCounts.entries.sortedByDescending { it.value }.take(10).map { it.key }
    return RecommendedForYou(songs = songs, albums = albumByKey.values.take(12), moods = moods, genres = genres)
}

fun recommendedPlaylist(
    recent: List<PlexTrack>,
    library: List<PlexTrack>,
    limit: Int = 50,
    disliked: Set<String> = emptySet(),
): List<PlexTrack> {
    if (recent.isEmpty() || library.isEmpty()) return emptyList()
    val recentKeys = recent.map { it.key }.toSet()
    val recentArtists = recent.map { it.artistTitle }.filter { it.isNotBlank() }.toSet()
    val recentAlbums = recent.map { it.albumTitle }.filter { it.isNotBlank() }.toSet()
    val recentSignals = recentlyPlayedMoods(recent, limit = 100).toSet()
    val scored = mutableListOf<Pair<PlexTrack, Int>>()
    for (t in library) {
        if (t.key in recentKeys) continue
        // 1.5.6 thumbs-down curation: disliked tracks are
        // never recommended, however well they score.
        if (t.key in disliked) continue
        var score = 0
        if (t.artistTitle in recentArtists) score += 3
        if (t.albumTitle in recentAlbums) score += 2
        val feels = mapTrackFeelsCached(t)
        score += (feels.moods + feels.vibes + t.genres).count { it in recentSignals }
        if (score > 0) scored.add(t to score)
    }
    return scored.sortedByDescending { it.second }.take(limit).map { it.first }
}

/**
 * 1.5.8 "Curated by Me!" — 2026-10-27 08:18: the
 * Home card for songs the user will actually want to
 * listen to all the time, curated by his own thumbs.
 * The liked songs themselves come first, in the order
 * given, then library songs that sound like them —
 * same artist, same album, same moods/vibes/genres —
 * scored the same way as recommendedPlaylist. A
 * disliked track is NEVER in it, however well it would
 * score, and disliked wins over liked if a key ever
 * appears in both. Only real library/liked tracks are
 * ever returned — an empty likes list means an empty
 * card with an honest "thumbs up some songs" message,
 * never a filler playlist. Pure, so unit tests cover
 * it without Android.
 */
fun buildCuratedByMe(
    liked: List<PlexTrack>,
    library: List<PlexTrack>,
    disliked: Set<String> = emptySet(),
    limit: Int = 50,
): List<PlexTrack> {
    if (liked.isEmpty()) return emptyList()
    val seen = LinkedHashSet<String>()
    val out = ArrayList<PlexTrack>()
    fun add(t: PlexTrack) {
        if (t.key.isBlank() || t.key in disliked) return
        if (seen.add(t.key) && out.size < limit) out.add(t)
    }
    liked.forEach(::add)
    if (out.size >= limit) return out
    val likedArtists = liked.map { it.artistTitle }.filter { it.isNotBlank() }.toSet()
    val likedAlbums = liked.map { it.albumTitle }.filter { it.isNotBlank() }.toSet()
    val likedSignals = (
        liked.flatMap { mapTrackFeelsCached(it).let { f -> f.moods + f.vibes } } +
            liked.flatMap { it.genres }
        ).toSet()
    val scored = mutableListOf<Pair<PlexTrack, Int>>()
    for (t in library) {
        if (t.key in seen || t.key in disliked) continue
        var score = 0
        if (t.artistTitle in likedArtists) score += 3
        if (t.albumTitle in likedAlbums) score += 2
        val feels = mapTrackFeelsCached(t)
        score += (feels.moods + feels.vibes + t.genres).count { it in likedSignals }
        if (score > 0) scored.add(t to score)
    }
    scored.sortedByDescending { it.second }.map { it.first }.forEach(::add)
    return out
}

/** 1.5.9 THUMB TOGGLES, 2026-10-27 10:09: tapping
 *  an already-selected thumb a second time UNSELECTS
 *  it — the song goes back to neutral, neither liked
 *  nor disliked — in case the user changed their mind.
 *  Pure, so the VM just applies the returned pair.
 *  A song is never in both sets: liking clears any
 *  dislike, disliking clears any like, and a second
 *  tap on the same thumb clears that thumb only. */
fun toggleLike(liked: Set<String>, disliked: Set<String>, key: String): Pair<Set<String>, Set<String>> =
    if (key in liked) (liked - key) to disliked
    else (liked + key) to (disliked - key)

fun toggleDislike(liked: Set<String>, disliked: Set<String>, key: String): Pair<Set<String>, Set<String>> =
    if (key in disliked) liked to (disliked - key)
    else (liked - key) to (disliked + key)
