package com.fourgeailabs.prismtune.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plex account token, stored encrypted.
 *
 * EncryptedSharedPreferences is tried first; on devices / emulators where the
 * AndroidKeyStore is unavailable it falls back to a private SharedPreferences
 * file so login still works (the token is never logged, and the file is
 * private to this app). Sign-out wipes it.
 */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "prismtune_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("prismtune_secure_fallback", Context.MODE_PRIVATE)
    }

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val token: StateFlow<String?> = _token.asStateFlow()

    fun getToken(): String? = _token.value

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).commit()
        _token.value = token
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _token.value = null
    }

    companion object {
        private const val KEY_TOKEN = "plex_account_token"
    }
}

/** Plain private prefs for non-secret settings (safe-area, quality, etc.). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prismtune_settings", Context.MODE_PRIVATE)

    var safeAreaFraction: Float
        get() = SafeAreaConfig.clamp(prefs.getFloat(KEY_SAFE_AREA, SafeAreaConfig.DEFAULT_FRACTION))
        set(value) {
            prefs.edit().putFloat(KEY_SAFE_AREA, SafeAreaConfig.clamp(value)).apply()
        }

    /** "Original" (direct play), "320", "192", or "128" kbps transcode ceiling. */
    var audioQuality: String
        get() = prefs.getString(KEY_QUALITY, "Original") ?: "Original"
        set(value) { prefs.edit().putString(KEY_QUALITY, value).apply() }

    /** 1.2.0: UI colour palette name: Amber, Cream, Sky, Mint, Berry.
     *  1.2.1: commit(), not apply(), so a crash/kill immediately
     *  after picking a palette cannot lose the choice — the old
     *  apply() was async and was a persist-loss suspect. */
    var paletteName: String
        get() = prefs.getString(KEY_PALETTE, "Amber") ?: "Amber"
        set(value) { prefs.edit().putString(KEY_PALETTE, value).commit() }

    /** 1.2.0: palette mode: "fixed" (selected palette always) or
     *  "nowplaying" (accent follows the current track). */
    var paletteMode: String
        get() = prefs.getString(KEY_PALETTE_MODE, "fixed") ?: "fixed"
        set(value) { prefs.edit().putString(KEY_PALETTE_MODE, value).commit() }

    /** 1.5.7 "Keep current colours": the encoded snapshot of
     *  the palette exactly as it looks right now (see
     *  encodePalette/decodePalette in Theme.kt), saved when
     *  the user taps Keep current colours in Settings. It is
     *  a plain colour record — no artwork, no track, no
     *  server data — and commit(), not apply(), so a
     *  crash/kill right after keeping cannot lose it, the
     *  same persist lesson as paletteName in 1.2.1. Null
     *  means nothing has been kept yet. */
    var customPalette: String?
        get() = prefs.getString(KEY_CUSTOM_PALETTE, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_CUSTOM_PALETTE).commit()
            else prefs.edit().putString(KEY_CUSTOM_PALETTE, value).commit()
        }

    var serverId: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) { prefs.edit().putString(KEY_SERVER, value).apply() }

    /** 1.1.1: URI of the server connection that worked most recently
     *  (a URI only — never a token). Preferred first on the next
     *  load, so a dead local address is not retried first on cellular. */
    var lastWorkingConnectionUri: String?
        get() = prefs.getString(KEY_LAST_CONNECTION, null)
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_LAST_CONNECTION).apply()
            else prefs.edit().putString(KEY_LAST_CONNECTION, value).apply()
        }

    /** 1.1.1: Offline Top Picks track metadata (key/title/artist/
     *  album/duration only, never a token), so downloaded tracks
     *  can still be shown and played after a restart when the
     *  server is unreachable and the library cannot reload. */
    var savedOfflineTracksJson: String?
        get() = prefs.getString(KEY_OFFLINE_TRACKS, null)
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_OFFLINE_TRACKS).apply()
            else prefs.edit().putString(KEY_OFFLINE_TRACKS, value).apply()
        }

    var shuffle: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) { prefs.edit().putBoolean(KEY_SHUFFLE, value).apply() }

    /** 0 = off, 1 = all, 2 = one. */
    var repeatMode: Int
        get() = prefs.getInt(KEY_REPEAT, 0)
        set(value) { prefs.edit().putInt(KEY_REPEAT, value).apply() }

    /** 1.1.0 last-played track, metadata only — never a token or password. */
    var lastPlayedTrack: com.fourgeailabs.prismtune.data.LastPlayedTrack?
        get() {
            val title = prefs.getString(KEY_LAST_TITLE, null) ?: return null
            return com.fourgeailabs.prismtune.data.LastPlayedTrack(
                key = prefs.getString(KEY_LAST_KEY, "") ?: "",
                title = title,
                artistTitle = prefs.getString(KEY_LAST_ARTIST, "") ?: "",
                albumTitle = prefs.getString(KEY_LAST_ALBUM, "") ?: "",
                thumb = prefs.getString(KEY_LAST_THUMB, null),
                durationMs = prefs.getLong(KEY_LAST_DURATION, 0),
            )
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                e.remove(KEY_LAST_KEY).remove(KEY_LAST_TITLE).remove(KEY_LAST_ARTIST)
                    .remove(KEY_LAST_ALBUM).remove(KEY_LAST_THUMB).remove(KEY_LAST_DURATION)
            } else {
                e.putString(KEY_LAST_KEY, value.key).putString(KEY_LAST_TITLE, value.title)
                    .putString(KEY_LAST_ARTIST, value.artistTitle).putString(KEY_LAST_ALBUM, value.albumTitle)
                    .putLong(KEY_LAST_DURATION, value.durationMs)
                if (value.thumb.isNullOrBlank()) e.remove(KEY_LAST_THUMB) else e.putString(KEY_LAST_THUMB, value.thumb)
            }
            e.apply()
        }

    var deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE, null)
            if (id.isNullOrBlank()) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE, id).apply()
            }
            return id
        }
        set(value) { prefs.edit().putString(KEY_DEVICE, value).apply() }

    /** 1.1.0: auto Offline Top Picks on/off (default on). No
     *  Wi-Fi gate: per the user's correction, downloads and refresh
     *  may run on mobile data as well as Wi-Fi. */
    var autoOffline: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OFFLINE, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_OFFLINE, value).apply() }

    /** 1.1.0 resume queue JSON (metadata only, never a token). */
    var savedQueueJson: String?
        get() = prefs.getString(KEY_SAVED_QUEUE, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_SAVED_QUEUE).apply()
            else prefs.edit().putString(KEY_SAVED_QUEUE, value).apply()
        }

    /** 1.1.4 Moods progress, persisted to disk so a crash does
     *  NOT restart the scan from 0: loaded count, total count,
     *  and genre/mood/vibe counts encoded as "name\tcount" lines
     *  per section. Metadata only, never a token. */
    var moodsLoadedCount: Int
        get() = prefs.getInt(KEY_MOODS_LOADED, 0)
        set(value) { prefs.edit().putInt(KEY_MOODS_LOADED, value).commit() }

    var moodsTotalCount: Int
        get() = prefs.getInt(KEY_MOODS_TOTAL, -1)
        set(value) { prefs.edit().putInt(KEY_MOODS_TOTAL, value).commit() }

    /** 1.2.3: recent search queries, most recent first, max 20.
     *  Used only for Home recommendations — songs, albums,
     *  moods, and genres based on what was searched, not just
     *  what was played. Query text only, never a token. */
    var searchHistory: List<String>
        get() {
            val raw = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
            return raw.split("\n").filter { it.isNotBlank() }.take(20)
        }
        set(value) {
            prefs.edit().putString(KEY_SEARCH_HISTORY, value.take(20).joinToString("\n")).commit()
        }

    var moodsCountsJson: String?
        get() = prefs.getString(KEY_MOODS_COUNTS, null)
        set(value) {
            if (value.isNullOrBlank()) prefs.edit().remove(KEY_MOODS_COUNTS).commit()
            else prefs.edit().putString(KEY_MOODS_COUNTS, value).commit()
        }

    fun clearMoodsProgress() {
        prefs.edit().remove(KEY_MOODS_LOADED).remove(KEY_MOODS_TOTAL).remove(KEY_MOODS_COUNTS).apply()
    }

    /** 1.1.0 local play history: "key|count" lines, newest first, capped at 200. */
    var playHistory: Map<String, Int>
        get() {
            val raw = prefs.getString(KEY_PLAY_HISTORY, "") ?: ""
            return raw.lines().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
            }.toMap()
        }
        set(value) {
            val text = value.entries.sortedByDescending { it.value }.take(200)
                .joinToString("\n") { "${it.key}|${it.value}" }
            prefs.edit().putString(KEY_PLAY_HISTORY, text).apply()
        }

    fun recordPlay(trackKey: String) {
        if (trackKey.isBlank()) return
        val map = playHistory.toMutableMap()
        map[trackKey] = (map[trackKey] ?: 0) + 1
        playHistory = map
    }

    /** 1.5.8 user-requested download stems: which files
     *  in the offline store the USER asked for (Download
     *  button / thumbs-up "Yes please"), as opposed to
     *  files the app put there itself — the next-10
     *  playback buffer and Offline Top Picks auto-
     *  downloads. The Now Playing "Downloaded to car"
     *  label and the Settings Downloads menu read this
     *  set, NOT mere file existence: before 1.5.8 the
     *  label read file existence, so a song the buffer
     *  had silently downloaded showed "Downloaded to
     *  car" even though the user never asked for it.
     *  Stems, not keys, because the disk only knows
     *  stems (see OfflineStore.stemFor). */
    var userDownloadStems: Set<String>
        get() = (prefs.getString(KEY_USER_DOWNLOAD_STEMS, "") ?: "")
            .lines().filter { it.isNotBlank() }.toSet()
        set(value) {
            prefs.edit().putString(KEY_USER_DOWNLOAD_STEMS, value.joinToString("\n")).apply()
        }
    var likedTrackKeys: Set<String>
        get() = (prefs.getString(KEY_LIKED_TRACKS, "") ?: "")
            .lines().filter { it.isNotBlank() }.toSet()
        set(value) {
            prefs.edit().putString(KEY_LIKED_TRACKS, value.joinToString("\n")).apply()
        }

    var dislikedTrackKeys: Set<String>
        get() = (prefs.getString(KEY_DISLIKED_TRACKS, "") ?: "")
            .lines().filter { it.isNotBlank() }.toSet()
        set(value) {
            prefs.edit().putString(KEY_DISLIKED_TRACKS, value.joinToString("\n")).apply()
        }

    companion object {
        private const val KEY_SAFE_AREA = "safe_area_fraction"
        private const val KEY_QUALITY = "audio_quality"
        private const val KEY_PALETTE = "palette_name"
        private const val KEY_PALETTE_MODE = "palette_mode"
        private const val KEY_CUSTOM_PALETTE = "custom_palette"
        private const val KEY_SERVER = "server_id"
        private const val KEY_LAST_CONNECTION = "last_working_connection_uri"
        private const val KEY_OFFLINE_TRACKS = "offline_tracks"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_AUTO_OFFLINE = "auto_offline"
        private const val KEY_SAVED_QUEUE = "saved_queue_json"
        private const val KEY_MOODS_LOADED = "moods_loaded_count"
        private const val KEY_MOODS_TOTAL = "moods_total_count"
        private const val KEY_MOODS_COUNTS = "moods_counts_json"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_PLAY_HISTORY = "play_history"
        private const val KEY_LIKED_TRACKS = "liked_track_keys"
        private const val KEY_USER_DOWNLOAD_STEMS = "user_download_stems"
        private const val KEY_DISLIKED_TRACKS = "disliked_track_keys"
        private const val KEY_LAST_KEY = "last_played_key"
        private const val KEY_LAST_TITLE = "last_played_title"
        private const val KEY_LAST_ARTIST = "last_played_artist"
        private const val KEY_LAST_ALBUM = "last_played_album"
        private const val KEY_LAST_THUMB = "last_played_thumb"
        private const val KEY_LAST_DURATION = "last_played_duration"
    }
}
