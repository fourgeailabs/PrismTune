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
    fun pick(connections: List<PlexConnection>): PlexConnection? {
        if (connections.isEmpty()) return null
        return connections.firstOrNull { it.local }
            ?: connections.firstOrNull { it.protocol == "https" }
            ?: connections.first()
    }
}

data class PlexSection(
    val key: String,
    val title: String,
    val type: String = "artist",
)

data class PlexArtist(
    val key: String,
    val title: String,
    val thumb: String? = null,
    val summary: String? = null,
)

data class PlexAlbum(
    val key: String,
    val title: String,
    val artistTitle: String = "",
    val thumb: String? = null,
    val year: Int? = null,
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
)

data class PinResult(
    val id: Long,
    val code: String,
    val authToken: String? = null,
)
