package com.fourgeailabs.prismtune.data

/**
 * 1.1.0 "Offline Top Picks" builder — pure, unit-testable.
 *
 * Sources, in priority order, deduplicated by track key:
 *  (a) Plex most-played tracks, highest viewCount first;
 *  (b) Plex highly rated tracks (userRating >= 8/10 — Plex ratings
 *      are 0-10; there is no separate "favourite" flag for tracks
 *      in this API, so a high rating is the favourite signal),
 *      then PrismTune's own most-played tracks (local play counts)
 *      where Plex data is missing;
 *  (c) top albums expanded to their tracks (albums are never
 *      playlist entries themselves — only their real tracks are);
 *  (d) top playlists expanded to their tracks (same rule).
 * Default cap is 100 tracks, or fewer if fewer qualify.
 *
 * If Plex provides no play-count or rating data at all AND there
 * is no local play history, the result is empty and
 * [TopPicksResult.reason] explains why in plain words — never a
 * fake "top" list. The fallback to local most-played + recently
 * played is applied by the caller passing those lists in; when
 * even those are empty, the empty state is the honest answer.
 */
data class TopPicksResult(
    val tracks: List<PlexTrack>,
    /** True when no Plex play-count/rating data existed at all. */
    val plexDataMissing: Boolean,
    val reason: String?,
)

fun buildOfflineTopPicks(
    mostPlayed: List<PlexTrack>,
    highlyRated: List<PlexTrack>,
    localMostPlayed: List<PlexTrack>,
    localRecent: List<PlexTrack>,
    topAlbumTracks: List<PlexTrack>,
    topPlaylistTracks: List<PlexTrack>,
    limit: Int = 100,
): TopPicksResult {
    val plexHasData = mostPlayed.any { it.viewCount > 0 } ||
        highlyRated.any { (it.userRating ?: 0f) >= 8f }
    val seen = LinkedHashSet<String>()
    val out = ArrayList<PlexTrack>()
    fun add(t: PlexTrack) {
        if (t.key.isBlank()) return
        if (seen.add(t.key) && out.size < limit) out.add(t)
    }
    mostPlayed.sortedByDescending { it.viewCount }.forEach(::add)
    highlyRated.filter { (it.userRating ?: 0f) >= 8f }
        .sortedByDescending { it.userRating ?: 0f }.forEach(::add)
    localMostPlayed.forEach(::add)
    topAlbumTracks.forEach(::add)
    topPlaylistTracks.forEach(::add)
    // Fallback: local recently played fills remaining slots only
    // when the "top" sources produced nothing at all.
    if (out.isEmpty()) localRecent.forEach(::add)
    val reason = if (out.isEmpty()) {
        "No play history or ratings found in Plex yet, and nothing " +
            "has been played in PrismTune yet. Play some music first " +
            "and Offline Top Picks will fill in automatically."
    } else null
    return TopPicksResult(out, plexDataMissing = !plexHasData, reason = reason)
}

/** Top albums from loaded tracks: group by album, sum viewCounts. */
fun topAlbumKeys(tracks: List<PlexTrack>, limit: Int = 10): List<String> =
    tracks.filter { !it.albumKey.isNullOrBlank() }
        .groupBy { it.albumKey!! }
        .entries.sortedByDescending { (_, ts) -> ts.sumOf { it.viewCount } }
        .take(limit).map { it.key }
