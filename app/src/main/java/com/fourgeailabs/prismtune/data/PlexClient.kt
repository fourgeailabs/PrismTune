package com.fourgeailabs.prismtune.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Plex HTTP client, JSON-first.
 *
 * Plex's own apps send `Accept: application/json`; plex.tv and Plex Media
 * Server both honour it, so we never have to parse Plex XML.
 *
 * Auth (TV / console style, exactly as the user asked):
 *  1. POST https://plex.tv/api/v2/pins  -> { id, code }  (code is 4 chars)
 *  2. Show `code` + a QR code for https://plex.tv/link on the car screen.
 *  3. The driver (or a passenger) scans the QR / opens plex.tv/link on a
 *     phone, signs in, and types the code.
 *  4. Poll GET https://plex.tv/api/v2/pins/{id} until `authToken` appears.
 *  5. That token is the account token; servers may then supply their own
 *     per-server token via /api/v2/resources.
 */
class PlexClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        const val PRODUCT = "PrismTune"
        const val VERSION = "1.0.0"
        const val PLATFORM = "Android Automotive"
        const val DEVICE = "Chevrolet Equinox EV"
    }

    private fun clientId(deviceId: String): String = "prismtune-$deviceId"

    private fun plexHeaders(deviceId: String): Map<String, String> = mapOf(
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Version" to VERSION,
        "X-Plex-Client-Identifier" to clientId(deviceId),
        "X-Plex-Platform" to PLATFORM,
        "X-Plex-Device" to DEVICE,
        "Accept" to "application/json",
    )

    private suspend fun get(url: String, token: String?, deviceId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            plexHeaders(deviceId).forEach { (k, v) -> builder.header(k, v) }
            if (!token.isNullOrBlank()) builder.header("X-Plex-Token", token)
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw PlexException("GET $url failed: ${resp.code} $body")
                JSONObject(body)
            }
        }

    private suspend fun postEmpty(url: String, deviceId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val empty = "".toRequestBody("application/json".toMediaType())
            val builder = Request.Builder().url(url).post(empty)
            plexHeaders(deviceId).forEach { (k, v) -> builder.header(k, v) }
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw PlexException("POST $url failed: ${resp.code} $body")
                JSONObject(body)
            }
        }

    /** Step 1 of PIN login. Returns the pin id + the 4-character code to display. */
    suspend fun createPin(deviceId: String): PinResult {
        val json = postEmpty("${PlexUrls.PLEX_TV}/api/v2/pins", deviceId)
        return PinResult(
            id = json.optLong("id"),
            code = json.optString("code"),
        )
    }

    /** Step 4 of PIN login. Returns authToken once the user has approved on their phone. */
    suspend fun pollPin(pinId: Long, deviceId: String): PinResult {
        val json = get("${PlexUrls.PLEX_TV}/api/v2/pins/$pinId", null, deviceId)
        val token = json.optString("authToken").takeIf { it.isNotBlank() }
        return PinResult(
            id = json.optLong("id", pinId),
            code = json.optString("code"),
            authToken = token,
        )
    }

    /** Servers reachable with this account token. */
    suspend fun resources(token: String, deviceId: String): List<PlexServer> {
        val json = get("${PlexUrls.PLEX_TV}/api/v2/resources?includeHttps=1&includeRelay=1", token, deviceId)
        val out = mutableListOf<PlexServer>()
        // plex.tv returns MediaContainer as an object with a Device array, or
        // (in some responses) as a bare array; parseResources handles both.
        parseResources(json, out)
        return out
    }

    private fun parseResources(json: JSONObject, out: MutableList<PlexServer>) {
        val container = json.opt("MediaContainer") ?: return
        val devices: JSONArray = when (container) {
            is JSONArray -> container
            is JSONObject -> container.optJSONArray("Device") ?: JSONArray()
            else -> JSONArray()
        }
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (d.optString("provides") != "server") continue
            val conns = mutableListOf<PlexConnection>()
            val connArr = d.optJSONArray("Connection") ?: JSONArray()
            for (j in 0 until connArr.length()) {
                val c = connArr.optJSONObject(j) ?: continue
                val uri = c.optString("uri")
                if (uri.isNotBlank()) {
                    conns.add(
                        PlexConnection(
                            uri = uri,
                            local = c.optInt("local", 0) == 1,
                            protocol = c.optString("protocol", "http"),
                        ),
                    )
                }
            }
            out.add(
                PlexServer(
                    id = d.optString("clientIdentifier"),
                    name = d.optString("name"),
                    product = d.optString("product"),
                    connections = conns,
                    accessToken = d.optString("accessToken"),
                    owned = d.optInt("owned", 1) == 1,
                ),
            )
        }
    }

    /** Music library sections on a server. */
    suspend fun musicSections(serverBase: String, token: String, deviceId: String): List<PlexSection> {
        val json = get(PlexUrls.join(serverBase, "/library/sections"), token, deviceId)
        val out = mutableListOf<PlexSection>()
        val dirs = json.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
        for (i in 0 until dirs.length()) {
            val d = dirs.optJSONObject(i) ?: continue
            if (d.optString("type") == "artist") {
                out.add(PlexSection(key = d.optString("key"), title = d.optString("title")))
            }
        }
        return out
    }

    /** All artists in a section, alphabetical (Plex returns them sorted already). */
    suspend fun artists(serverBase: String, token: String, deviceId: String, sectionKey: String): List<PlexArtist> {
        val json = get(PlexUrls.join(serverBase, "/library/sections/$sectionKey/all?type=8"), token, deviceId)
        val out = mutableListOf<PlexArtist>()
        val dirs = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
        for (i in 0 until dirs.length()) {
            val d = dirs.optJSONObject(i) ?: continue
            out.add(
                PlexArtist(
                    key = d.optString("ratingKey"),
                    title = d.optString("title"),
                    thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                    summary = d.optString("summary").takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }

    /** Albums for one artist (children of the artist). */
    suspend fun albumsForArtist(serverBase: String, token: String, deviceId: String, artistKey: String): List<PlexAlbum> {
        val json = get(PlexUrls.join(serverBase, "/library/metadata/$artistKey/children"), token, deviceId)
        val out = mutableListOf<PlexAlbum>()
        val metas = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
        for (i in 0 until metas.length()) {
            val d = metas.optJSONObject(i) ?: continue
            out.add(
                PlexAlbum(
                    key = d.optString("ratingKey"),
                    title = d.optString("title"),
                    artistTitle = d.optString("parentTitle"),
                    thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                    year = d.optInt("year").takeIf { it > 0 },
                ),
            )
        }
        return out
    }

    /** Tracks for one album (children of the album). */
    suspend fun tracksForAlbum(serverBase: String, token: String, deviceId: String, albumKey: String): List<PlexTrack> =
        tracksFromMetadata(serverBase, token, deviceId, "/library/metadata/$albumKey/children")

    /** Tracks in a playlist. */
    suspend fun tracksForPlaylist(serverBase: String, token: String, deviceId: String, playlistKey: String): List<PlexTrack> =
        tracksFromMetadata(serverBase, token, deviceId, "/library/metadata/$playlistKey/children")

    /** Tracks in a section, most recently added first (used by Home). */
    suspend fun recentlyAdded(serverBase: String, token: String, deviceId: String, sectionKey: String): List<PlexTrack> {
        val json = get(
            PlexUrls.join(serverBase, "/library/sections/$sectionKey/recentlyAdded?type=10&X-Plex-Container-Size=24"),
            token,
            deviceId,
        )
        return parseTracks(json)
    }

    suspend fun playlists(serverBase: String, token: String, deviceId: String): List<PlexPlaylist> {
        val json = get(PlexUrls.join(serverBase, "/playlists?playlistType=audio"), token, deviceId)
        val out = mutableListOf<PlexPlaylist>()
        val metas = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
        for (i in 0 until metas.length()) {
            val d = metas.optJSONObject(i) ?: continue
            out.add(
                PlexPlaylist(
                    key = d.optString("ratingKey"),
                    title = d.optString("title"),
                    thumb = d.optString("thumb").takeIf { it.isNotBlank() },
                    itemCount = d.optInt("leafCount", 0),
                ),
            )
        }
        return out
    }

    /** Simple title search across artists / albums / tracks. */
    suspend fun search(serverBase: String, token: String, deviceId: String, query: String): List<PlexTrack> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val json = get(
            PlexUrls.join(serverBase, "/library/search?query=$encoded&limit=30"),
            token,
            deviceId,
        )
        val out = mutableListOf<PlexTrack>()
        val results = json.optJSONObject("MediaContainer")?.optJSONArray("SearchResult") ?: JSONArray()
        for (i in 0 until results.length()) {
            val s = results.optJSONObject(i) ?: continue
            val m = s.optJSONObject("Metadata") ?: continue
            if (m.optString("type") == "track") out.add(parseTrack(m))
        }
        return out
    }

    private suspend fun tracksFromMetadata(serverBase: String, token: String, deviceId: String, path: String): List<PlexTrack> {
        val json = get(PlexUrls.join(serverBase, path), token, deviceId)
        return parseTracks(json)
    }

    private fun parseTracks(json: JSONObject): List<PlexTrack> {
        val out = mutableListOf<PlexTrack>()
        val metas = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
        for (i in 0 until metas.length()) {
            val d = metas.optJSONObject(i) ?: continue
            out.add(parseTrack(d))
        }
        return out
    }

    private fun parseTrack(d: JSONObject): PlexTrack {
        var partId = -1L
        val media = d.optJSONArray("Media")
        if (media != null && media.length() > 0) {
            val parts = media.optJSONObject(0)?.optJSONArray("Part")
            if (parts != null && parts.length() > 0) {
                partId = parts.optJSONObject(0)?.optLong("id", -1L) ?: -1L
            }
        }
        return PlexTrack(
            key = d.optString("ratingKey"),
            title = d.optString("title"),
            artistTitle = d.optString("grandparentTitle"),
            albumTitle = d.optString("parentTitle"),
            thumb = d.optString("thumb").takeIf { it.isNotBlank() }
                ?: d.optString("parentThumb").takeIf { it.isNotBlank() }
                ?: d.optString("grandparentThumb").takeIf { it.isNotBlank() },
            durationMs = d.optLong("duration", 0),
            partId = partId,
            index = d.optInt("index", 0),
        )
    }
}

class PlexException(message: String) : Exception(message)
