package com.fourgeailabs.prismtune.data

/** Pure, unit-testable Plex helpers. No Android framework types in here on purpose. */
object SafeAreaConfig {
    /**
     * Calibrated default (2026-10-25 photo, see README "Calibration"):
     * interactive controls live in the left 80% of the width. The photo
     * (angled, so approximate) puts the clock at ~83% across and the
     * temperature reading itself at ~89% across, so 80% leaves a clear
     * margin before the gauge zone while giving more usable room than
     * the original conservative 70%.
     */
    const val DEFAULT_FRACTION = 0.80f
    const val MIN_FRACTION = 0.55f

    /** Hard cap: controls are NEVER allowed past 85% of app width. */
    const val MAX_FRACTION = 0.85f

    /** Right edge of the top-right gauge quadrant (temp/clock zone). */
    const val GAUGE_ZONE_X = 0.80f

    /** Top edge of the gauge quadrant, as a fraction of screen height. */
    const val GAUGE_ZONE_TOP_HEIGHT = 0.12f

    /** Clamp a user-chosen fraction into the allowed range. */
    fun clamp(fraction: Float): Float = fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)

    /** True when a point is inside the top-right gauge quadrant. */
    fun isGaugeZone(xFraction: Float, yFraction: Float): Boolean =
        xFraction > GAUGE_ZONE_X && yFraction < GAUGE_ZONE_TOP_HEIGHT

    /**
     * True when a point (as a fraction of screen width, 0..1) is inside the
     * interactive zone. Anything at/above the fraction is the non-interactive
     * right strip reserved for artwork / ambient display only — this is what
     * keeps controls clear of the Equinox EV's top-right temperature gauge.
     */
    fun isInteractiveX(xFraction: Float, fraction: Float = DEFAULT_FRACTION): Boolean =
        xFraction < clamp(fraction)

    /** Full 2-D check: interactive zone AND never in the gauge quadrant. */
    fun isInteractivePoint(xFraction: Float, yFraction: Float, fraction: Float = DEFAULT_FRACTION): Boolean =
        isInteractiveX(xFraction, fraction) && !isGaugeZone(xFraction, yFraction)
}

/**
 * Measured usable area of the 2025 Equinox EV infotainment display
 * (Eric, tape-measured, final figures). This is the APP-USABLE area, not
 * the advertised full-glass spec, and the layout is coded to it.
 * Height is only 4.875 in, so layouts must stay low and wide.
 */
object EquinoxUsableArea {
    const val WIDTH_IN = 13.125
    const val HEIGHT_IN = 4.875

    /** 13.125 / 4.875 = 2.692:1. */
    val aspectRatio: Double get() = WIDTH_IN / HEIGHT_IN

    /** sqrt(13.125^2 + 4.875^2) = about 14.00 in. */
    val diagonalIn: Double get() = kotlin.math.sqrt(WIDTH_IN * WIDTH_IN + HEIGHT_IN * HEIGHT_IN)

    /** Interactive width at the default fraction: 10.50 in. */
    fun interactiveWidthIn(fraction: Float = SafeAreaConfig.DEFAULT_FRACTION): Double =
        WIDTH_IN * SafeAreaConfig.clamp(fraction)

    /** Non-interactive right reserve at the default fraction: 2.625 in. */
    fun reserveWidthIn(fraction: Float = SafeAreaConfig.DEFAULT_FRACTION): Double =
        WIDTH_IN - interactiveWidthIn(fraction)

    /** Temp centre from the left at ~89% across: about 11.68 in. */
    val tempXIn: Double get() = WIDTH_IN * 0.89

    /** Buffer between the interactive edge (80%) and the temp centre: about 1.18 in. */
    fun tempBufferIn(fraction: Float = SafeAreaConfig.DEFAULT_FRACTION): Double =
        tempXIn - interactiveWidthIn(fraction)

    /** Clock from the left at ~83% across: about 10.89 in. */
    val clockXIn: Double get() = WIDTH_IN * 0.83
}

object PlexUrls {
    const val PLEX_TV = "https://plex.tv"
    const val CLIENTS_PLEX_TV = "https://clients.plex.tv"
    const val LINK_URL = "https://plex.tv/link"

    /** The URL a phone should open after scanning the car's QR code.
     *  Deliberately plain https://plex.tv/link — the QR never encodes
     *  anything else, and the link code itself is shown as text to type. */
    fun join(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }

    fun withToken(url: String, token: String): String {
        if (token.isBlank()) return url
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}X-Plex-Token=$token"
    }

    fun thumbUrl(serverBase: String, thumb: String?, token: String): String? {
        if (thumb.isNullOrBlank()) return null
        return withToken(join(serverBase, thumb), token)
    }

    /** Direct-play stream URL for one Plex Media Part. */
    fun streamUrl(serverBase: String, partId: Long, token: String): String =
        withToken(join(serverBase, "/library/parts/$partId/file"), token)

    /** Universal-transcode URL, used when the audio-quality setting is not "Original". */
    fun transcodeUrl(
        serverBase: String,
        partId: Long,
        token: String,
        maxBitrateKbps: Int,
        container: String = "mp3",
    ): String {
        val base = join(serverBase, "/music/:/transcode/universal/start.mp3")
        return withToken(
            "$base?path=%2Flibrary%2Fparts%2F$partId%2Ffile" +
                "&mediaIndex=0&partIndex=0&protocol=http&fastSeek=1" +
                "&directPlay=0&directStream=1&subtitleSize=100&audioBoost=100" +
                "&location=lan&maxAudioBitrate=$maxBitrateKbps&audioCodec=$container",
            token,
        )
    }
}

/**
 * In-car browser sign-in (1.0.3). The car browser opens exactly the
 * same page as the phone path — plain https://plex.tv/link, where the
 * user signs in and types the same 4-character code shown on screen.
 * No code, token, or client identifier is ever put in the URL: the
 * code is typed by the user, and the app matches the approval by
 * polling the PIN id it already created. Chrome is preferred only if
 * it resolves on the car; the UI falls back to a generic browser
 * intent, and shows a plain message (never a crash) if no browser
 * resolves at all. Whether Chrome actually resolves on a given
 * Equinox can only be proven on real hardware — see the test notes.
 */
object SignInBrowser {
    const val CHROME_PACKAGE = "com.android.chrome"

    /** The only URL the car browser is ever opened to for sign-in. */
    fun targetUrl(): String = PlexUrls.LINK_URL
}

data class PlexConnection(
    val uri: String,
    val local: Boolean,
    val protocol: String = "http",
) {
    val isLocal: Boolean get() = local
}

data class PlexServer(
    val id: String,
    val name: String,
    val product: String = "",
    val connections: List<PlexConnection> = emptyList(),
    val accessToken: String = "",
    val owned: Boolean = true,
) {
    /** The token that actually talks to this server (its own, else the account token). */
    fun effectiveToken(accountToken: String): String =
        if (accessToken.isNotBlank()) accessToken else accountToken
}

/** Picks the best connection: local first, then any remote. Pure function, unit-tested. */
object ConnectionPicker {
    fun pick(connections: List<PlexConnection>): PlexConnection? =
        ordered(connections).firstOrNull()

    /** User-facing kind, never a URI/IP: local, remote, or relay. */
    fun kindOf(connection: PlexConnection): String = when {
        connection.local -> "local"
        connection.protocol == "https" -> "remote"
        else -> "relay"
    }

    /**
     * 1.1.1 ordered connections (Eric's correction, supersedes the
     * old local-first order): remote FIRST, local SECOND, relay /
     * other THIRD — for a car, remote is the reliable path, even
     * on home Wi-Fi, because the car is usually away from home.
     * A recently-working connection only breaks ties within its
     * own tier; it never promotes a local connection above a
     * remote one. Deduplicated by URI, original order kept within
     * a tier. The caller must still try each in turn — ordering
     * alone is not a reachability test, especially for a
     * *.plex.direct address that resolves to a private IP on cellular.
     */
    fun ordered(connections: List<PlexConnection>, preferredUri: String? = null): List<PlexConnection> {
        val seen = mutableSetOf<String>()
        val unique = connections.filter { seen.add(it.uri) }
        return unique.sortedWith(
            compareBy<PlexConnection> {
                when {
                    !it.local && it.protocol == "https" -> 0
                    it.local -> 1
                    else -> 2
                }
            }
                .thenBy { if (!preferredUri.isNullOrBlank() && it.uri == preferredUri) 0 else 1 },
        )
    }
}

/** Thrown only when every server connection failed. Carries kinds, never URIs/IPs/tokens. */
class AllConnectionsFailed(val triedKinds: List<String>, cause: Throwable?) :
    Exception("Could not reach the server: tried ${triedKinds.joinToString(", ")} connections and none could be reached.", cause)

/**
 * 1.1.1 connection fallback, pure except for the injected probe.
 * Tries each ordered connection in turn. A timeout / unreachable /
 * network failure on one connection moves to the next; a 401 is
 * rethrown immediately (the token, not the connection, is wrong).
 * Each attempt is capped at 10 s via withTimeoutOrNull, so three
 * connections stay bounded at ~30 s instead of 3 x 15 s or more.
 * Returns the probe result, the winning base URI, and the kinds
 * tried up to and including the winner.
 */
suspend fun <T> tryServerConnections(
    connections: List<PlexConnection>,
    preferredUri: String?,
    attempt: suspend (String) -> T,
): Triple<T, String, List<String>> {
    val ordered = ConnectionPicker.ordered(connections, preferredUri)
    val tried = mutableListOf<String>()
    var lastError: Throwable? = null
    for (connection in ordered) {
        tried.add(ConnectionPicker.kindOf(connection))
        val result = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) { attempt(connection.uri) }
        } catch (e: Exception) {
            if ((e as? PlexException)?.statusCode == 401) throw e
            lastError = e
            null
        }
        if (result != null) return Triple(result, connection.uri, tried.toList())
        if (lastError == null) lastError = RuntimeException("Connection attempt timed out")
        // withTimeoutOrNull returns null on timeout without an exception to record;
        // keep lastError from a previous attempt, or the timeout marker above.
        if (tried.size == 1 && lastError?.message == "Connection attempt timed out") {
            // first attempt timed out: keep the marker, next attempt may replace it
        }
    }
    throw AllConnectionsFailed(tried, lastError)
}

/** Offline-track metadata persistence (1.1.1): key|title|artist|album|durationMs lines, | and \ escaped. No token. */
fun encodeOfflineTracks(tracks: List<PlexTrack>): String {
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n")
    return tracks.joinToString("\n") { "${esc(it.key)}|${esc(it.title)}|${esc(it.artistTitle)}|${esc(it.albumTitle)}|${it.durationMs}" }
}

fun decodeOfflineTracks(raw: String?): List<PlexTrack> {
    if (raw.isNullOrBlank()) return emptyList()
    val out = mutableListOf<PlexTrack>()
    for (line in raw.lines()) {
        if (line.isBlank()) continue
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (line[i + 1]) {
                    '\\' -> cur.append('\\')
                    '|' -> cur.append('|')
                    'n' -> cur.append('\n')
                    else -> cur.append(line[i + 1])
                }
                i += 2
            } else if (c == '|') {
                parts.add(cur.toString()); cur.clear(); i++
            } else {
                cur.append(c); i++
            }
        }
        parts.add(cur.toString())
        if (parts.size != 5) continue
        out.add(PlexTrack(key = parts[0], title = parts[1], artistTitle = parts[2], albumTitle = parts[3], durationMs = parts[4].toLongOrNull() ?: 0L))
    }
    return out
}

data class PlexSection(
    val key: String,
    val title: String,
    val type: String = "artist",
)

data class PlexArtist(
    val key: String,
    val title: String,
    /** 1.1.0: Plex "key" field for the artist, e.g.
     *  "/library/metadata/51261" or ".../children". Used by
     *  albumsForArtist() so a children call uses the server's
     *  own path instead of guessing from ratingKey alone. */
    val keyPath: String? = null,
    /** 1.1.0: the artist's OWN Plex image (artist thumb from the
     *  artists endpoint / artist metadata). Never an album cover:
     *  artworkForArtist() below returns exactly this, never a
     *  track/album thumb, so an artist row can never borrow art. */
    val thumb: String? = null,
    val summary: String? = null,
)

/** The artwork for an artist is the artist's own thumb, full stop. */
fun artworkForArtist(artist: PlexArtist): String? = artist.thumb

data class PlexAlbum(
    val key: String,
    val title: String,
    val artistTitle: String = "",
    val thumb: String? = null,
    val year: Int? = null,
    /** 1.1.0: Plex viewCount (plays) and userRating (0-10), where provided. */
    val viewCount: Int = 0,
    val userRating: Float? = null,
    /** 1.1.0: the album's artist image (parentThumb), distinct from the album cover. */
    val artistThumb: String? = null,
)

data class PlexTrack(
    val key: String,
    val title: String,
    val artistTitle: String = "",
    val albumTitle: String = "",
    val thumb: String? = null,
    val durationMs: Long = 0,
    val partId: Long = -1,
    val partKey: String? = null,
    val index: Int = 0,
    /** 1.1.0 full metadata: track art is thumb (album art fallback
     *  chain), albumThumb is parentThumb, artistThumb is
     *  grandparentThumb — the actual artist's image, not album art. */
    val albumThumb: String? = null,
    val artistThumb: String? = null,
    val year: Int? = null,
    val summary: String? = null,
    /** Plex parentIndex for a track is its disc number. */
    val discNumber: Int = 0,
    val artistKey: String? = null,
    val albumKey: String? = null,
    /**
     * 1.0.4: real Plex tags parsed from the track's library metadata.
     * "Genre" / "Mood" / "Style" arrays of {tag} objects (Plex also
     * returns them as plain strings in some responses; the parser
     * handles both). Empty when the server has no tags for the track —
     * The Feels then falls back to genre inference, and a track with
     * neither gets no mood/vibe, never a random one.
     */
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    /** 1.1.0: Plex viewCount (plays) and userRating (0-10), where provided. */
    val viewCount: Int = 0,
    val userRating: Float? = null,
    /** 1.1.0 transient: local downloaded file for offline playback, never persisted with the queue. */
    val localPath: String? = null,
) {
    val durationLabel: String
        get() {
            val totalSeconds = durationMs / 1000
            return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
        }
}

data class PlexPlaylist(
    val key: String,
    val title: String,
    val thumb: String? = null,
    val itemCount: Int = 0,
    /** 1.1.2: Plex "key" field from /playlists, e.g.
     *  "/playlists/51262" — same pattern as PlexArtist.keyPath.
     *  A playlist ratingKey is a playlist ID, NOT a library
     *  metadata ID, so /library/metadata/<playlistId>/children
     *  404s (All Music, 51262, 2026-10-26). Tracks must load
     *  from /playlists/<id>/items instead. */
    val keyPath: String? = null,
)

/**
 * 1.1.2 playlist items path: Plex's own key if it already
 * names the items endpoint, its /playlists/<id> key plus
 * "/items", otherwise "/playlists/<ratingKey>/items".
 * Never a /library/metadata path for a playlist.
 */
fun playlistItemsPath(playlist: PlexPlaylist): String {
    val kp = playlist.keyPath.orEmpty()
    return when {
        kp.endsWith("/items") -> kp
        kp.startsWith("/playlists/") -> "$kp/items"
        else -> "/playlists/${playlist.key}/items"
    }
}

/** 1.1.2 fallback only: the old metadata-children path, tried
 *  solely after the items endpoint 404s. */
fun playlistMetadataChildrenPath(playlist: PlexPlaylist): String =
    "/library/metadata/${playlist.key}/children"

/**
 * 1.1.2 fallback decision, kept pure so unit tests cover it
 * without a live server: only a 404 from the items endpoint
 * falls back to metadata children. A 401 (wrong token) or any
 * other failure is thrown, never masked by a fallback.
 */
fun isPlaylistItems404(e: Exception): Boolean =
    (e as? PlexException)?.statusCode == 404 || e.message?.contains(" 404 ") == true

/** 1.1.0 server-side search results: only the query's matches,
 *  grouped by type. Never the full library disguised as results. */
data class ServerSearchResults(
    val artists: List<PlexArtist> = emptyList(),
    val albums: List<PlexAlbum> = emptyList(),
    val tracks: List<PlexTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
    val totalCount: Int get() = artists.size + albums.size + tracks.size
}

/** 1.1.0: last-played track persisted across restarts. Metadata
 *  only — never a token, never a password. */
data class LastPlayedTrack(
    val key: String = "",
    val title: String = "",
    val artistTitle: String = "",
    val albumTitle: String = "",
    val thumb: String? = null,
    val durationMs: Long = 0,
)

/** 1.1.0 pagination offsets for a Plex container: start values
 *  0, pageSize, 2*pageSize, … while start < totalSize. Pure. */
fun paginationOffsets(totalSize: Int, pageSize: Int): List<Int> {
    if (totalSize <= 0 || pageSize <= 0) return emptyList()
    val out = mutableListOf<Int>()
    var start = 0
    while (start < totalSize) {
        out.add(start)
        start += pageSize
    }
    return out
}

/** 1.1.0: true while more pages remain after `fetched` items. */
fun hasMorePages(fetched: Int, totalSize: Int): Boolean = fetched < totalSize

data class PinResult(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    /** 1.0.5: which Plex API host this PIN was created on. Creation
     *  and polling must stay on the same host for one sign-in attempt,
     *  so the ViewModel carries this through to pollPin(). */
    val host: String = PlexUrls.PLEX_TV,
)
