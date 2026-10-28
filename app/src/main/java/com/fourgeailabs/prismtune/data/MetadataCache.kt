package com.fourgeailabs.prismtune.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1.2.3 persistent metadata cache.
 *
 * The user ordered it plainly: metadata must persist at all
 * times — force close, quit, cold boot, phone restart, it does
 * not matter. The metadata loaded initially must be reloaded
 * automatically and saved to the device, so the app feels
 * snappy, with a soft resync on every server scan that adds
 * new media and removes media no longer on the server.
 *
 * What is cached, in one app-private file
 * (filesDir/prismtune_metadata_v1.json, never in a prefs file
 * that would choke on 45k tracks):
 * - allTracks: every track in the first music section, with
 *   key, title, artist, album, thumbs, duration, partId,
 *   partKey, index, disc, album/artist keys, genres, moods,
 *   styles, viewCount, userRating — everything Home, Moods,
 *   search, voice, and playback need, without a server round
 *   trip. Local file paths are NEVER cached.
 * - artists, playlists, recent tracks, sections.
 * - Moods counts (genre/mood/vibe) and loaded/total counts.
 *
 * Soft resync: the ViewModel loads the cached snapshot first
 * (instant UI, even offline), then loads from the server and
 * REPLACES the snapshot with the fresh list. Replacement is
 * the prune: a track, artist, or playlist no longer on the
 * server simply is not in the new snapshot, so it disappears
 * from the app. A failed server load never wipes the cache —
 * the cached snapshot stays on screen with an offline note.
 * Sign-out deletes the file, so a different user never sees
 * the previous user's library.
 */
data class MetadataSnapshot(
    val allTracks: List<PlexTrack> = emptyList(),
    val artists: List<PlexArtist> = emptyList(),
    val playlists: List<PlexPlaylist> = emptyList(),
    val recent: List<PlexTrack> = emptyList(),
    // 1.2.3: recently played tracks, so Home's Recently Played
    // and its queue survive cold boot, not just the last track.
    val recentlyPlayed: List<PlexTrack> = emptyList(),
    // 1.2.3: most recently added albums, for Home's Recently
    // Added section (albums, not songs).
    val recentAlbums: List<PlexAlbum> = emptyList(),
    val sections: List<PlexSection> = emptyList(),
    val moodsGenreCounts: Map<String, Int> = emptyMap(),
    val moodsMoodCounts: Map<String, Int> = emptyMap(),
    val moodsVibeCounts: Map<String, Int> = emptyMap(),
    val moodsLoadedCount: Int = 0,
    val moodsTotalCount: Int = -1,
    val moodsMappingVersion: Int = 0,
    val savedAtMs: Long = 0L,
)

fun trackToJson(t: PlexTrack): JSONObject {
    val o = JSONObject()
    o.put("key", t.key)
    o.put("title", t.title)
    o.put("artistTitle", t.artistTitle)
    o.put("albumTitle", t.albumTitle)
    o.put("thumb", t.thumb ?: JSONObject.NULL)
    o.put("durationMs", t.durationMs)
    o.put("partId", t.partId)
    o.put("partKey", t.partKey ?: JSONObject.NULL)
    o.put("index", t.index)
    o.put("albumThumb", t.albumThumb ?: JSONObject.NULL)
    o.put("artistThumb", t.artistThumb ?: JSONObject.NULL)
    o.put("year", t.year ?: JSONObject.NULL)
    o.put("summary", t.summary ?: JSONObject.NULL)
    o.put("discNumber", t.discNumber)
    o.put("artistKey", t.artistKey ?: JSONObject.NULL)
    o.put("albumKey", t.albumKey ?: JSONObject.NULL)
    o.put("genres", JSONArray(t.genres))
    o.put("moods", JSONArray(t.moods))
    o.put("styles", JSONArray(t.styles))
    o.put("viewCount", t.viewCount)
    o.put("userRating", t.userRating?.toDouble() ?: JSONObject.NULL)
    return o
}

fun trackFromJson(o: JSONObject): PlexTrack {
    fun optStr(name: String): String? = if (o.isNull(name)) null else o.optString(name)
    fun optInt(name: String): Int? = if (o.isNull(name)) null else o.optInt(name)
    fun strList(name: String): List<String> {
        val arr = o.optJSONArray(name) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }
    return PlexTrack(
        key = o.optString("key"),
        title = o.optString("title"),
        artistTitle = o.optString("artistTitle"),
        albumTitle = o.optString("albumTitle"),
        thumb = optStr("thumb"),
        durationMs = o.optLong("durationMs"),
        partId = o.optLong("partId", -1),
        partKey = optStr("partKey"),
        index = o.optInt("index"),
        albumThumb = optStr("albumThumb"),
        artistThumb = optStr("artistThumb"),
        year = optInt("year"),
        summary = optStr("summary"),
        discNumber = o.optInt("discNumber"),
        artistKey = optStr("artistKey"),
        albumKey = optStr("albumKey"),
        genres = strList("genres"),
        moods = strList("moods"),
        styles = strList("styles"),
        viewCount = o.optInt("viewCount"),
        userRating = if (o.isNull("userRating")) null else o.optDouble("userRating").toFloat(),
        localPath = null,
    )
}

fun snapshotToJson(s: MetadataSnapshot): JSONObject {
    val o = JSONObject()
    o.put("savedAtMs", s.savedAtMs)
    o.put("moodsLoadedCount", s.moodsLoadedCount)
    o.put("moodsTotalCount", s.moodsTotalCount)
    o.put("moodsMappingVersion", s.moodsMappingVersion)
    fun countsJson(m: Map<String, Int>): JSONObject {
        val jo = JSONObject()
        for ((k, v) in m) jo.put(k, v)
        return jo
    }
    o.put("moodsGenreCounts", countsJson(s.moodsGenreCounts))
    o.put("moodsMoodCounts", countsJson(s.moodsMoodCounts))
    o.put("moodsVibeCounts", countsJson(s.moodsVibeCounts))
    fun tracksJson(tracks: List<PlexTrack>): JSONArray {
        val arr = JSONArray()
        for (t in tracks) arr.put(trackToJson(t))
        return arr
    }
    o.put("allTracks", tracksJson(s.allTracks))
    o.put("recent", tracksJson(s.recent))
    o.put("recentlyPlayed", tracksJson(s.recentlyPlayed))
    val recentAlbumsArr = JSONArray()
    for (a in s.recentAlbums) {
        val ao = JSONObject()
        ao.put("key", a.key); ao.put("title", a.title); ao.put("artistTitle", a.artistTitle)
        ao.put("thumb", a.thumb ?: JSONObject.NULL)
        ao.put("artistThumb", a.artistThumb ?: JSONObject.NULL)
        ao.put("year", a.year ?: JSONObject.NULL)
        recentAlbumsArr.put(ao)
    }
    o.put("recentAlbums", recentAlbumsArr)
    val artistsArr = JSONArray()
    for (a in s.artists) {
        val ao = JSONObject()
        ao.put("key", a.key); ao.put("title", a.title)
        ao.put("keyPath", a.keyPath ?: JSONObject.NULL)
        ao.put("thumb", a.thumb ?: JSONObject.NULL)
        artistsArr.put(ao)
    }
    o.put("artists", artistsArr)
    val playlistsArr = JSONArray()
    for (p in s.playlists) {
        val po = JSONObject()
        po.put("key", p.key); po.put("title", p.title)
        po.put("thumb", p.thumb ?: JSONObject.NULL)
        po.put("itemCount", p.itemCount)
        po.put("keyPath", p.keyPath ?: JSONObject.NULL)
        playlistsArr.put(po)
    }
    o.put("playlists", playlistsArr)
    val sectionsArr = JSONArray()
    for (sec in s.sections) {
        val so = JSONObject()
        so.put("key", sec.key); so.put("title", sec.title); so.put("type", sec.type)
        sectionsArr.put(so)
    }
    o.put("sections", sectionsArr)
    return o
}

fun snapshotFromJson(o: JSONObject): MetadataSnapshot {
    fun tracks(name: String): List<PlexTrack> {
        val arr = o.optJSONArray(name) ?: return emptyList()
        val out = ArrayList<PlexTrack>()
        for (i in 0 until arr.length()) {
            runCatching { out.add(trackFromJson(arr.getJSONObject(i))) }
        }
        return out
    }
    fun counts(name: String): Map<String, Int> {
        val jo = o.optJSONObject(name) ?: return emptyMap()
        val out = mutableMapOf<String, Int>()
        val keys = jo.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = jo.optInt(k)
        }
        return out
    }
    val artists = ArrayList<PlexArtist>()
    val artistsArr = o.optJSONArray("artists")
    if (artistsArr != null) {
        for (i in 0 until artistsArr.length()) {
            val ao = artistsArr.getJSONObject(i)
            artists.add(PlexArtist(
                key = ao.optString("key"),
                title = ao.optString("title"),
                keyPath = if (ao.isNull("keyPath")) null else ao.optString("keyPath"),
                thumb = if (ao.isNull("thumb")) null else ao.optString("thumb"),
            ))
        }
    }
    val playlists = ArrayList<PlexPlaylist>()
    val playlistsArr = o.optJSONArray("playlists")
    if (playlistsArr != null) {
        for (i in 0 until playlistsArr.length()) {
            val po = playlistsArr.getJSONObject(i)
            playlists.add(PlexPlaylist(
                key = po.optString("key"),
                title = po.optString("title"),
                thumb = if (po.isNull("thumb")) null else po.optString("thumb"),
                itemCount = po.optInt("itemCount"),
                keyPath = if (po.isNull("keyPath")) null else po.optString("keyPath"),
            ))
        }
    }
    val sections = ArrayList<PlexSection>()
    val sectionsArr = o.optJSONArray("sections")
    if (sectionsArr != null) {
        for (i in 0 until sectionsArr.length()) {
            val so = sectionsArr.getJSONObject(i)
            sections.add(PlexSection(
                key = so.optString("key"),
                title = so.optString("title"),
                type = so.optString("type", "artist"),
            ))
        }
    }
    return MetadataSnapshot(
        allTracks = tracks("allTracks"),
        artists = artists,
        playlists = playlists,
        recent = tracks("recent"),
        recentlyPlayed = tracks("recentlyPlayed"),
        recentAlbums = run {
            val arr = o.optJSONArray("recentAlbums") ?: JSONArray()
            val out = ArrayList<PlexAlbum>()
            for (i in 0 until arr.length()) {
                val ao = arr.getJSONObject(i)
                out.add(PlexAlbum(
                    key = ao.optString("key"), title = ao.optString("title"),
                    artistTitle = ao.optString("artistTitle"),
                    thumb = if (ao.isNull("thumb")) null else ao.optString("thumb"),
                    artistThumb = if (ao.isNull("artistThumb")) null else ao.optString("artistThumb"),
                    year = if (ao.isNull("year")) null else ao.optInt("year"),
                ))
            }
            out
        },
        sections = sections,
        moodsGenreCounts = counts("moodsGenreCounts"),
        moodsMoodCounts = counts("moodsMoodCounts"),
        moodsVibeCounts = counts("moodsVibeCounts"),
        moodsLoadedCount = o.optInt("moodsLoadedCount"),
        moodsTotalCount = o.optInt("moodsTotalCount", -1),
        moodsMappingVersion = o.optInt("moodsMappingVersion", 0),
        savedAtMs = o.optLong("savedAtMs"),
    )
}

class MetadataCache(context: Context) {
    private val file: File = File(context.filesDir, "prismtune_metadata_v1.json")

    fun load(): MetadataSnapshot? {
        return try {
            if (!file.exists()) return null
            // 1.3.1: a runaway cache file must never OOM boot —
            // 50MB is far above a real 45k-track snapshot.
            if (file.length() > 50L * 1024 * 1024) return null
            val text = file.readText()
            if (text.isBlank()) return null
            snapshotFromJson(JSONObject(text))
        } catch (_: Throwable) { null }
    }

    fun save(snapshot: MetadataSnapshot) {
        try {
            val tmp = File(file.parentFile, "prismtune_metadata_v1.json.tmp")
            tmp.writeText(snapshotToJson(snapshot.copy(savedAtMs = System.currentTimeMillis())).toString())
            // Atomic rename: a crash mid-write never leaves a
            // half-written snapshot behind.
            if (tmp.renameTo(file)) return
            file.writeText(tmp.readText())
            tmp.delete()
        } catch (_: Exception) { /* cache is best-effort, never a crash */ }
    }

    fun clear() {
        try { file.delete() } catch (_: Exception) { }
    }
}
