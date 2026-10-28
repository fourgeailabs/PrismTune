package com.fourgeailabs.prismtune.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.1.0 resume queue + position persistence — pure encode/decode,
 * unit-testable. Stored in private prefs (SettingsStore), never
 * with a token or password: track metadata only.
 *
 * 1.1.5 persistent metadata: the full track metadata is saved
 * (genres/moods/styles, album/artist thumbs, year, disc, keys),
 * plus the queue's SOURCE — playlist/album/artist/mood name and
 * its Plex key — so reopening the app can requeue the most
 * recent playlist and song, not just a nameless window of
 * tracks. Old saved queues (no source, no extra fields) still
 * decode, with source null and extra fields empty.
 */
data class SavedQueue(
    val tracks: List<PlexTrack>,
    val index: Int,
    val positionMs: Long,
    val sourceKind: String? = null,
    val sourceName: String? = null,
    val sourceKey: String? = null,
    val serverId: String? = null,
)

fun encodeSavedQueue(
    tracks: List<PlexTrack>,
    index: Int,
    positionMs: Long,
    sourceKind: String? = null,
    sourceName: String? = null,
    sourceKey: String? = null,
    serverId: String? = null,
): String {
    val arr = JSONArray()
    for (t in tracks) {
        arr.put(JSONObject().apply {
            put("key", t.key); put("title", t.title)
            put("artist", t.artistTitle); put("album", t.albumTitle)
            put("thumb", t.thumb ?: ""); put("duration", t.durationMs)
            put("partId", t.partId); put("partKey", t.partKey ?: "")
            put("trackIndex", t.index); put("disc", t.discNumber)
            put("albumThumb", t.albumThumb ?: ""); put("artistThumb", t.artistThumb ?: "")
            put("year", t.year ?: 0); put("artistKey", t.artistKey ?: "")
            put("albumKey", t.albumKey ?: "")
            put("genres", JSONArray(t.genres)); put("moods", JSONArray(t.moods))
            put("styles", JSONArray(t.styles))
        })
    }
    return JSONObject().apply {
        put("tracks", arr); put("index", index); put("position", positionMs)
        if (sourceKind != null) put("sourceKind", sourceKind)
        if (sourceName != null) put("sourceName", sourceName)
        if (sourceKey != null) put("sourceKey", sourceKey)
        if (serverId != null) put("serverId", serverId)
    }.toString()
}

fun decodeSavedQueue(raw: String?): SavedQueue? {
    if (raw.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("tracks") ?: return null
        val tracks = ArrayList<PlexTrack>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fun strList(name: String): List<String> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            }
            tracks.add(
                PlexTrack(
                    key = o.optString("key"), title = o.optString("title"),
                    artistTitle = o.optString("artist"), albumTitle = o.optString("album"),
                    thumb = o.optString("thumb").takeIf { it.isNotBlank() },
                    durationMs = o.optLong("duration"), partId = o.optLong("partId", -1),
                    partKey = o.optString("partKey").takeIf { it.isNotBlank() },
                    index = o.optInt("trackIndex", 0), discNumber = o.optInt("disc", 0),
                    albumThumb = o.optString("albumThumb").takeIf { it.isNotBlank() },
                    artistThumb = o.optString("artistThumb").takeIf { it.isNotBlank() },
                    year = o.optInt("year", 0).takeIf { it != 0 },
                    artistKey = o.optString("artistKey").takeIf { it.isNotBlank() },
                    albumKey = o.optString("albumKey").takeIf { it.isNotBlank() },
                    genres = strList("genres"), moods = strList("moods"), styles = strList("styles"),
                ),
            )
        }
        if (tracks.isEmpty()) return null
        SavedQueue(
            tracks, obj.optInt("index", 0), obj.optLong("position", 0),
            sourceKind = obj.optString("sourceKind").takeIf { it.isNotBlank() },
            sourceName = obj.optString("sourceName").takeIf { it.isNotBlank() },
            sourceKey = obj.optString("sourceKey").takeIf { it.isNotBlank() },
            serverId = obj.optString("serverId").takeIf { it.isNotBlank() },
        )
    } catch (_: Exception) {
        // Corrupt saved queue degrades to "nothing to resume",
        // never a crash. The last-played track still shows.
        null
    }
}
