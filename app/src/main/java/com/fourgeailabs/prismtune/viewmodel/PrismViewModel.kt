package com.fourgeailabs.prismtune.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fourgeailabs.prismtune.data.AllConnectionsFailed
import com.fourgeailabs.prismtune.data.ConnectionPicker
import com.fourgeailabs.prismtune.data.decodeOfflineTracks
import com.fourgeailabs.prismtune.data.encodeOfflineTracks
import com.fourgeailabs.prismtune.data.tryServerConnections
import com.fourgeailabs.prismtune.data.DiagnosticStep
import com.fourgeailabs.prismtune.data.PlexAlbum
import com.fourgeailabs.prismtune.data.PlexArtist
import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexPlaylist
import com.fourgeailabs.prismtune.data.PlexSection
import com.fourgeailabs.prismtune.data.PlexServer
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.DiagnosticEntry
import com.fourgeailabs.prismtune.data.OfflineStore
import com.fourgeailabs.prismtune.data.PlexUrls
import com.fourgeailabs.prismtune.data.appendDiagnostic
import com.fourgeailabs.prismtune.data.buildOfflineTopPicks
import com.fourgeailabs.prismtune.data.decodeSavedQueue
import com.fourgeailabs.prismtune.data.diagnosticEntryFor
import com.fourgeailabs.prismtune.data.encodeSavedQueue
import com.fourgeailabs.prismtune.data.topAlbumKeys
import com.fourgeailabs.prismtune.data.SettingsStore
import com.fourgeailabs.prismtune.data.runConnectionDiagnostic
import com.fourgeailabs.prismtune.data.TokenStore
import com.fourgeailabs.prismtune.playback.PlaybackManager
import com.fourgeailabs.prismtune.playback.PrismTuneAppState
import com.fourgeailabs.prismtune.qr.QrCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SignInState {
    data object Idle : SignInState
    data object Creating : SignInState
    /**
     * 1.0.3: back to the 4-character weak PIN (1.0.0 flow). linkUrl is
     * plain https://plex.tv/link, the QR encodes exactly that URL, and
     * code is the 4-character code to type there — on a phone, or in
     * the car browser via "Open sign-in in car browser". No Auth App
     * URL is used or rendered in this version.
     */
    data class Waiting(
        val code: String,
        val qrBitmap: Bitmap?,
        val linkUrl: String,
    ) : SignInState
    /** 1.0.8: username/password sign-in in progress (distinct from PIN Creating, which the UI labels as getting a link code). */
    data object PasswordSigningIn : SignInState
    data class Error(val message: String) : SignInState
    data object Done : SignInState
}

/**
 * 1.0.5: plain-language mapping for a sign-in failure, unit-testable and
 * pure (no Android classes). The 2026-10-25 Equinox/phone report was
 * "Could not reach Plex: Unable to resolve host \"plex.tv\": No address
 * associated with hostname" — a DNS/network failure, shown raw, which
 * the user read as Plex rejecting his code, when in fact no code was
 * ever created. A DNS failure must never read as a code rejection:
 * it gets the network message, a 401 gets the rejection message, and
 * anything else keeps the previous generic message. Expiry is handled
 * separately by the polling code and is never merged into this mapping.
 * The cause chain is walked because OkHttp may wrap the
 * UnknownHostException thrown by the system resolver.
 */
fun signInErrorMessage(e: Throwable): String {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is java.net.UnknownHostException) {
            return "No connection to Plex — PrismTune could not find plex.tv. " +
                "Tap Test connection below to see which step fails, " +
                "then tap Get link code again."
        }
        cause = cause.cause
    }
    val message = e.message.orEmpty()
    if (message.contains("Unable to resolve host", ignoreCase = true) ||
        message.contains("No address associated with hostname", ignoreCase = true)
    ) {
        return "No connection to Plex — PrismTune could not find plex.tv. " +
            "Tap Test connection below to see which step fails, " +
            "then tap Get link code again."
    }
    if ((e as? com.fourgeailabs.prismtune.data.PlexException)?.statusCode == 401 ||
        message.contains(" 401 ")
    ) {
        return "Plex rejected that sign-in, please sign in again"
    }
    return "Could not reach Plex: $message"
}

/**
 * 1.0.6: exact error mapping for PIN *creation* only (Get link
 * code), kept deliberately separate from signInErrorMessage and
 * from handleUnauthorized. On 2026-10-25 the phone tapped Get link
 * code, spun for a few seconds, and showed the generic token-
 * rejection text with no code/QR ever appearing — on fresh taps
 * and re-taps alike — while the 1.0.5 diagnostic PIN POST had
 * succeeded. The generic text made it impossible to tell whether
 * PIN creation itself had failed, on which host, or with what
 * status. So this mapping NEVER returns the token-rejection text,
 * even for a 401 on PIN creation: it names the host(s), the HTTP
 * status, and Plex's body (truncated to 200 chars), and says
 * plainly that no code was created. DNS failures keep the
 * plain-language network message. No token, code, or PIN id is
 * ever included: createPin's messages carry only host + status +
 * Plex body, and the body is truncated here as a second guard.
 */
fun pinCreationErrorMessage(e: Throwable): String {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is java.net.UnknownHostException) {
            return "No connection to Plex — PrismTune could not find plex.tv. " +
                "Tap Test connection below to see which step fails, " +
                "then tap Get link code again."
        }
        cause = cause.cause
    }
    val message = e.message.orEmpty()
    if (message.contains("Unable to resolve host", ignoreCase = true) ||
        message.contains("No address associated with hostname", ignoreCase = true)
    ) {
        return "No connection to Plex — PrismTune could not find plex.tv. " +
            "Tap Test connection below to see which step fails, " +
            "then tap Get link code again."
    }
    // Both-hosts failure from PlexClient.combinePinFailures:
    // segments are joined by " | ", each "POST <host>/api/v2/pins
    // failed: <status> <body>".
    val segments = message.split(" | ").filter { it.contains("/api/v2/pins") }
    if (segments.size >= 2) {
        val details = segments.map { seg ->
            val host = when {
                seg.contains("clients.plex.tv") -> "clients.plex.tv"
                seg.contains("plex.tv") -> "plex.tv"
                else -> "plex.tv"
            }
            val afterFailed = seg.substringAfter("failed:", "").trim()
            val status = afterFailed.substringBefore(' ').ifBlank { "?" }
            val body = afterFailed.substringAfter(' ', "").take(200).trim()
            if (body.isBlank()) {
                "POST /api/v2/pins on $host returned $status"
            } else {
                "POST /api/v2/pins on $host returned $status — $body"
            }
        }
        return "Link code request failed on plex.tv and clients.plex.tv: " +
            details.joinToString("; ") + ". No code was created."
    }
    if (message.contains("/api/v2/pins")) {
        val host = if (message.contains("clients.plex.tv")) "clients.plex.tv" else "plex.tv"
        val afterFailed = message.substringAfter("failed:", "").trim()
        val status = afterFailed.substringBefore(' ').ifBlank { "?" }
        val body = afterFailed.substringAfter(' ', "").take(200).trim()
        return if (body.isBlank()) {
            "Link code request failed on $host: POST /api/v2/pins returned $status. No code was created."
        } else {
            "Link code request failed on $host: POST /api/v2/pins returned $status — $body. No code was created."
        }
    }
    return "Link code request failed: ${message.take(200)}. No code was created."
}

/**
 * 1.0.8: exact error mapping for username/password sign-in
 * only, kept separate from PIN and token-rejection messages.
 * Wrong password, two-factor required, and network failures
 * are each distinguishable, never collapsed to "Plex
 * rejected". The mapping input is only the exception
 * (URL + status + Plex body): the client never puts the
 * password or the verification code into an exception, so
 * neither can appear here. Bodies are truncated to 200
 * chars as a second guard.
 */
fun passwordSignInErrorMessage(e: Throwable): String {
    if (e is com.fourgeailabs.prismtune.data.TwoFactorRequired) {
        return "Plex needs a verification code (two-factor authentication). " +
            "Enter the code from your authenticator app below and sign in again."
    }
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is java.net.UnknownHostException) {
            return "No connection to Plex — PrismTune could not find plex.tv. " +
                "Tap Test connection below to see which step fails, " +
                "then try signing in again."
        }
        cause = cause.cause
    }
    val message = e.message.orEmpty()
    if (message.contains("Unable to resolve host", ignoreCase = true) ||
        message.contains("No address associated with hostname", ignoreCase = true)
    ) {
        return "No connection to Plex — PrismTune could not find plex.tv. " +
            "Tap Test connection below to see which step fails, " +
            "then try signing in again."
    }
    if (message.contains("/users/sign_in") || message.contains("/users/signin")) {
        val host = if (message.contains("clients.plex.tv")) "clients.plex.tv" else "plex.tv"
        val afterFailed = message.substringAfter("failed:", "").trim()
        val status = afterFailed.substringBefore(' ').ifBlank { "?" }
        val body = afterFailed.substringAfter(' ', "").take(200).trim()
        return if (body.isBlank()) {
            "Plex username/password sign-in failed on $host: returned $status. No token was saved."
        } else {
            "Plex username/password sign-in failed on $host: returned $status — $body. No token was saved."
        }
    }
    return "Plex username/password sign-in failed: ${message.take(200)}. No token was saved."
}

data class LibraryState(
    val servers: List<PlexServer> = emptyList(),
    val selectedServer: PlexServer? = null,
    val serverBase: String? = null,
    val sections: List<PlexSection> = emptyList(),
    val artists: List<PlexArtist> = emptyList(),
    val albums: List<PlexAlbum> = emptyList(),
    val tracks: List<PlexTrack> = emptyList(),
    val playlists: List<PlexPlaylist> = emptyList(),
    val recent: List<PlexTrack> = emptyList(),
    /** 1.2.3: most recently added ALBUMS, for Home's Recently
     *  Added section — albums, not songs, at the user's request. */
    val recentAlbums: List<PlexAlbum> = emptyList(),
    val searchResults: List<PlexTrack> = emptyList(),
    /** 1.1.0 server-side search: only the query's matches, never
     *  the full library. searching=true means "loading", and
     *  "No matches" is shown only after it completes empty. */
    val serverSearchResults: com.fourgeailabs.prismtune.data.ServerSearchResults? = null,
    val searching: Boolean = false,
    val searchError: String? = null,
    val searchAlbums: List<PlexAlbum> = emptyList(),
    val searchTracks: List<PlexTrack> = emptyList(),
    /** 1.1.0 dedicated detail views: an artist/album/playlist tap
     *  loads ONLY that item's children here, never into the shared
     *  Artists/Albums/Tracks lists, and detailError lives here so
     *  a failed tap never leaves a stale red error on the list. */
    val detailArtist: PlexArtist? = null,
    val detailAlbum: PlexAlbum? = null,
    val detailPlaylist: PlexPlaylist? = null,
    val detailAlbums: List<PlexAlbum> = emptyList(),
    val detailTracks: List<PlexTrack> = emptyList(),
    val detailError: String? = null,
    val detailLoading: Boolean = false,
    /** 1.1.0 Moods incremental load: counts aggregated per chunk,
     *  loaded/total progress, and a non-fatal chunk error. */
    val moodsGenreCounts: Map<String, Int> = emptyMap(),
    val moodsMoodCounts: Map<String, Int> = emptyMap(),
    val moodsVibeCounts: Map<String, Int> = emptyMap(),
    /** 1.5.4 SERVER MOOD LIST: Plex's own mood/style terms
     *  for the section, fetched from the server's secondary
     *  directories — the API equivalent of the DLNA Moods
     *  view seen in File Explorer. Primary Moods list
     *  source; scan counts above fill in counts for terms
     *  the scan actually saw, and terms with no scan count
     *  are shown with no count, never a fake one.
     *  serverTagsLoaded = a fetch was attempted (even if
     *  it came back empty), so we don't re-probe on every
     *  Moods composition. */
    val serverMoods: List<String> = emptyList(),
    val serverVibes: List<String> = emptyList(),
    val serverTagsLoaded: Boolean = false,
    val moodsLoadedCount: Int = 0,
    val moodsTotalCount: Int? = null,
    val moodsLoading: Boolean = false,
    val moodsError: String? = null,
    /** 1.1.4: name of the mood/vibe/genre currently being
     *  loaded for immediate playback, null when not loading. */
    val moodsPlayingFeel: String? = null,
    /** 1.0.4: every track in the first music section — what The Feels, library search, and voice search run on. */
    val allTracks: List<PlexTrack> = emptyList(),
    val allTracksLoaded: Boolean = false,
    val loadingAllTracks: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** 1.1.1: "Trying remote connection…" while falling back; null otherwise. Never a URI/IP. */
    val connectionStatus: String? = null,
)

class PrismViewModel(application: Application) : AndroidViewModel(application) {
    private val client = PlexClient()
    val tokenStore = TokenStore(application)
    val settings = SettingsStore(application)
    // 1.2.3 persistent metadata cache: file-backed, loaded on
    // init, replaced on every successful server resync.
    val metadataCache = com.fourgeailabs.prismtune.data.MetadataCache(application)
    val playback: PlaybackManager =
        PrismTuneAppState.playbackManager ?: PlaybackManager(application).also {
            PrismTuneAppState.playbackManager = it
        }.also { it.setDeviceId(deviceId) }

    // 1.2.3: buffer the next 10 songs, playing or paused.
    private var bufferJob: kotlinx.coroutines.Job? = null

    /** 1.2.3: download the next 10 tracks after the current one
     *  to the offline store, one at a time, in the background.
     *  Runs whenever the current index changes, and continues
     *  even while the current song is paused — buffering is
     *  tied to the current song being on, not to it playing.
     *  When a buffered track becomes current, playback swaps
     *  in the local file, so skipping has no live-buffer wait.
     *  Pruning in refreshOfflineTopPicks keeps the next 10, so
     *  a Top Picks refresh does not delete buffered tracks. */
    fun startBufferNext10() {
        bufferJob?.cancel()
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        val queue = playback.queue.value
        val current = playback.currentIndex.value
        if (queue.isEmpty() || current < 0) return
        // 1.3.9: buffer the songs that will actually play next,
        // which is the shuffled sequence when shuffle is on.
        val next10 = playback.upNextIndices(10).mapNotNull { queue.getOrNull(it) }
        if (next10.isEmpty()) return
        // 1.3.2: crash-after-a-few-seconds guard — buffering
        // used to start immediately on boot (auto-restore sets
        // an index even while paused) and download 10 full
        // tracks back-to-back while the library was still
        // loading, competing for network, disk, and memory.
        // Now it waits 10 seconds after an index change, skips
        // if the library is still loading, and pauses 2 seconds
        // between tracks, so boot finishes first.
        bufferJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 1.3.7: if the user just hit play, buffer in 2 s,
            // not 10 s. The 10 s delay is only for a boot /
            // auto-restore index change, where the library is
            // still loading and buffering would compete with it.
            kotlinx.coroutines.delay(if (playback.isPlaying.value) 2_000 else 10_000)
            if (_library.value.loading) return@launch
            for (track in next10) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive != true) break
                if (offlineStore.isDownloaded(track.key)) continue
                runCatching { downloadTrack(base, token, track) }
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    init {
        playback.setDeviceId(deviceId)
        // 1.1.3: lazy partId resolution for tracks past the first
        // 21 in a queue. Resolves on demand, then replaces the
        // queue item so playback restarts with a real partId.
        playback.onTrackNeedsPartId = { index, track ->
            viewModelScope.launch {
                val lib = _library.value
                val base = lib.serverBase ?: return@launch
                val token = libraryToken()
                if (token.isBlank()) return@launch
                val full = runCatching {
                    client.trackMetadata(base, token, deviceId, track.key)
                }.getOrNull()
                if (full != null && full.partId >= 0) {
                    playback.updateTrackPartId(index, full)
                }
            }
        }
    }

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _safeAreaFraction = MutableStateFlow(settings.safeAreaFraction)
    val safeAreaFraction: StateFlow<Float> = _safeAreaFraction.asStateFlow()

    private val _audioQuality = MutableStateFlow(settings.audioQuality)
    val audioQuality: StateFlow<String> = _audioQuality.asStateFlow()

    /** 1.2.0 palette picker + Now Playing dynamic mode. */
    private val _paletteName = MutableStateFlow(settings.paletteName)
    val paletteName: StateFlow<String> = _paletteName.asStateFlow()
    private val _paletteMode = MutableStateFlow(settings.paletteMode)
    val paletteMode: StateFlow<String> = _paletteMode.asStateFlow()

    fun setPalette(name: String) {
        settings.paletteName = name
        _paletteName.value = name
    }

    fun setPaletteMode(mode: String) {
        settings.paletteMode = mode
        _paletteMode.value = mode
    }

    /** 1.5.7 kept palette: the encoded snapshot from Settings
     *  > Keep current colours, or null if nothing kept yet. */
    private val _customPalette = MutableStateFlow(settings.customPalette)
    val customPalette: StateFlow<String?> = _customPalette.asStateFlow()

    fun keepCurrentPalette(palette: com.fourgeailabs.prismtune.ui.PrismPalette) {
        // Freeze EXACTLY what the screen looks like right
        // now as a fixed palette named "Kept": the snapshot
        // is stored, the mode switches to fixed, and the
        // name switches to Kept, so MainActivity stops
        // deriving colours from artwork on the next track
        // and just uses the snapshot, across restarts.
        val encoded = com.fourgeailabs.prismtune.ui.encodePalette(
            palette.copy(name = com.fourgeailabs.prismtune.ui.KEPT_PALETTE_NAME),
        )
        settings.customPalette = encoded
        _customPalette.value = encoded
        settings.paletteName = com.fourgeailabs.prismtune.ui.KEPT_PALETTE_NAME
        _paletteName.value = com.fourgeailabs.prismtune.ui.KEPT_PALETTE_NAME
        settings.paletteMode = "fixed"
        _paletteMode.value = "fixed"
    }

    private val _recentlyPlayed = MutableStateFlow<List<PlexTrack>>(emptyList())
    val recentlyPlayed: StateFlow<List<PlexTrack>> = _recentlyPlayed.asStateFlow()

    /** 1.5.6 thumbs + download state, 2026-10-27
     * 05:42: a thumbs up / thumbs down in the main player
     * to curate the queue, a thumbs up asks whether to
     * also download that song, and a download button to
     * save the current song to the car. Thumbs live only
     * on-device (TokenStore); they are NOT sent to Plex
     * and do not claim to change Plex's own ratings.
     * Download reuses the existing offline download path
     * (downloadTrack / OfflineStore) — same files the
     * Offline Top Picks use, so a downloaded song plays
     * offline exactly like any other offline track. */
    private val _likedKeys = MutableStateFlow<Set<String>>(emptySet())
    val likedKeys: StateFlow<Set<String>> = _likedKeys.asStateFlow()
    private val _dislikedKeys = MutableStateFlow<Set<String>>(emptySet())
    val dislikedKeys: StateFlow<Set<String>> = _dislikedKeys.asStateFlow()
    private val _downloadPromptKey = MutableStateFlow<String?>(null)
    val downloadPromptKey: StateFlow<String?> = _downloadPromptKey.asStateFlow()
    private val _downloadingKey = MutableStateFlow<String?>(null)
    val downloadingKey: StateFlow<String?> = _downloadingKey.asStateFlow()
    private val _downloadFailedKey = MutableStateFlow<String?>(null)
    val downloadFailedKey: StateFlow<String?> = _downloadFailedKey.asStateFlow()

    fun thumbUp(track: com.fourgeailabs.prismtune.data.PlexTrack) {
        if (track.key.isBlank()) return
        // 1.5.9 toggle: a second tap on an already-liked
        // song un-likes it back to neutral — and an
        // un-like must NOT pop the download prompt.
        val wasLiked = track.key in _likedKeys.value
        val (liked, disliked) = com.fourgeailabs.prismtune.data.toggleLike(_likedKeys.value, _dislikedKeys.value, track.key)
        _likedKeys.value = liked
        settings.likedTrackKeys = liked
        _dislikedKeys.value = disliked
        settings.dislikedTrackKeys = disliked
        // A NEW thumbs up asks whether to also download
        // it — as a pop-up (1.5.8), not the old inline
        // row. Only if the user has not already
        // downloaded it: "downloaded" here means
        // USER-requested (see isUserDownloaded), never
        // a silent buffer file.
        if (!wasLiked && !isUserDownloaded(track.key)) _downloadPromptKey.value = track.key
    }

    fun thumbDown(track: com.fourgeailabs.prismtune.data.PlexTrack) {
        if (track.key.isBlank()) return
        // 1.5.9 toggle: a second tap on an
        // already-disliked song un-dislikes it back to
        // neutral, so it can come back into Curated by
        // Me! and Recommended.
        val (liked, disliked) = com.fourgeailabs.prismtune.data.toggleDislike(_likedKeys.value, _dislikedKeys.value, track.key)
        _likedKeys.value = liked
        settings.likedTrackKeys = liked
        _dislikedKeys.value = disliked
        settings.dislikedTrackKeys = disliked
    }

    fun dismissDownloadPrompt() { _downloadPromptKey.value = null }

    /** 1.5.8 RESETS, 2026-10-27 08:44: separate
     *  resets for likes and dislikes, NOT one combined
     *  wipe — the user picks which half of the curation
     *  to throw away. Resetting likes empties Curated
     *  by Me! back to its empty state (fills are
     *  derived from likes, so they go too); resetting
     *  dislikes lets previously-banned songs back into
     *  Curated by Me! and Recommended. Downloads,
     *  queue and Recently Played are untouched. */
    fun resetLikedSongs() {
        _likedKeys.value = emptySet()
        settings.likedTrackKeys = emptySet()
    }

    fun resetDislikedSongs() {
        _dislikedKeys.value = emptySet()
        settings.dislikedTrackKeys = emptySet()
    }

    fun isDownloaded(key: String): Boolean = offlineStore.isDownloaded(key)

    /** 1.5.8: true only when the USER asked for this track
     *  AND its file is actually on disk. File existence
     *  alone is NOT enough — the next-10 buffer and
     *  Offline Top Picks put files on disk silently, and
     *  before 1.5.8 that made the Now Playing label say
     *  "Downloaded to car" for songs the user never
     *  downloaded. A user download that was later
     *  deleted (or whose file vanished) reads false
     *  again, honestly. */
    fun isUserDownloaded(key: String): Boolean {
        if (key.isBlank()) return false
        return offlineStore.stemFor(key) in settings.userDownloadStems && offlineStore.isDownloaded(key)
    }

    /** 1.5.8: open the download pop-up for the current
     *  track. This is what the Download button does now —
     *  it asks first, it never downloads on a bare tap. */
    fun requestDownload() {
        val track = playback.currentTrack ?: return
        if (track.key.isBlank() || isUserDownloaded(track.key)) return
        _downloadPromptKey.value = track.key
    }

    fun downloadCurrentTrack() {
        val track = playback.currentTrack ?: return
        if (track.key.isBlank()) return
        _downloadPromptKey.value = null
        _downloadFailedKey.value = null
        // Already on disk (buffered or Top Picks): no
        // network, no re-download — just mark it as the
        // user's download, so the label becomes true for
        // a song the user actually claimed.
        if (offlineStore.isDownloaded(track.key)) {
            settings.userDownloadStems = settings.userDownloadStems + offlineStore.stemFor(track.key)
            return
        }
        val base = _library.value.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        _downloadingKey.value = track.key
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = downloadTrack(base, token, track)
            _downloadingKey.value = null
            if (ok) {
                settings.userDownloadStems = settings.userDownloadStems + offlineStore.stemFor(track.key)
            } else {
                _downloadFailedKey.value = track.key
            }
        }
    }

    /** 1.5.8 one Downloads-menu row: a file on disk, its
     *  library metadata when known, and whether the user
     *  asked for it (vs the app buffering it itself). */
    data class DownloadedItem(
        val stem: String,
        val title: String,
        val artist: String,
        val isUserDownload: Boolean,
        val bytes: Long,
    )

    private val _downloads = MutableStateFlow<List<DownloadedItem>>(emptyList())
    val downloads: StateFlow<List<DownloadedItem>> = _downloads.asStateFlow()

    /** 1.5.8: rebuild the Downloads menu from disk.
     *  Titles come from the loaded library / queue /
     *  recent lists when the stem matches a known track;
     *  a file with no known metadata still lists, under
     *  its stem, rather than being hidden or invented. */
    fun refreshDownloads() {
        val known = (_library.value.allTracks + _library.value.tracks +
            _offline.value.tracks + _recentlyPlayed.value + playback.queue.value)
            .distinctBy { it.key }
        val userStems = settings.userDownloadStems
        _downloads.value = offlineStore.downloadedFiles().map { f ->
            val stem = f.nameWithoutExtension
            val track = known.firstOrNull { offlineStore.stemFor(it.key) == stem }
            DownloadedItem(
                stem = stem,
                title = track?.title?.takeIf { it.isNotBlank() } ?: "Unknown track",
                artist = track?.artistTitle?.takeIf { it.isNotBlank() } ?: stem,
                isUserDownload = stem in userStems,
                bytes = f.length(),
            )
        }
    }

    /** 1.5.8: delete one download, from disk and from
     *  the user set, then refresh the menu. */
    fun deleteDownload(stem: String) {
        offlineStore.deleteStem(stem)
        settings.userDownloadStems = settings.userDownloadStems - stem
        refreshDownloads()
        refreshOfflineCounts()
    }

    /** 1.5.8: delete ALL downloads, except the song
     *  currently playing (by stem — the store compares
     *  stems, so the playing track must be passed as a
     *  stem, not its original key, or a hashed key would
     *  slip past the protection). The playing song is
     *  kept so playback does not break mid-song; the
     *  menu says so plainly. */
    fun deleteAllDownloads() {
        val playingStem = playback.currentTrack?.let { offlineStore.stemFor(it.key) }
        offlineStore.clearAll(playingStem)
        val remaining = offlineStore.downloadedFiles().map { it.nameWithoutExtension }.toSet()
        settings.userDownloadStems = settings.userDownloadStems.intersect(remaining)
        refreshDownloads()
        refreshOfflineCounts()
    }

    private fun refreshOfflineCounts() {
        val off = _offline.value
        _offline.value = off.copy(
            downloadedCount = off.tracks.count { offlineStore.isDownloaded(it.key) },
            storageBytes = offlineStore.storageBytes(),
        )
    }

    /** 1.1.0: most recently listened-to song, restored from
     *  private prefs on launch so the mini player shows on open,
     *  even before anything is played this session. */
    private val _lastPlayed = MutableStateFlow<PlexTrack?>(null)
    val lastPlayed: StateFlow<PlexTrack?> = _lastPlayed.asStateFlow()

    // 1.1.0 diagnostics: last few sanitised errors, for Settings > Diagnostics.
    private val _diagnostics = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val diagnostics: StateFlow<List<DiagnosticEntry>> = _diagnostics.asStateFlow()

    // 1.1.0 resume: saved queue + position, restored on launch.
    private val _restoredQueue = MutableStateFlow<com.fourgeailabs.prismtune.data.SavedQueue?>(null)
    val restoredQueue: StateFlow<com.fourgeailabs.prismtune.data.SavedQueue?> = _restoredQueue.asStateFlow()

    /** 1.1.5: where the current queue came from — kind, name,
     *  Plex key — persisted with the queue so reopening can
     *  requeue the most recent playlist, not a nameless list. */
    data class QueueSource(val kind: String, val name: String, val key: String?)
    private val _queueSource = MutableStateFlow<QueueSource?>(null)
    val queueSource: StateFlow<QueueSource?> = _queueSource.asStateFlow()
    private var lastQueueSource: QueueSource? = null
    private var lastFeelKind: String? = null
    private var autoRestoreAttempted = false

    /** 1.5.7 pending cold-boot play: set when the user taps
     *  play on the mini player (or Now Playing) before the
     *  library has loaded, so there is no serverBase yet for
     *  resumeSavedQueue to use. tryAutoRestore honours it
     *  only if the library load lands within 30 s of the
     *  tap — after that it is dropped, so music never starts
     *  unexpectedly minutes later when the car reconnects. */
    private var pendingPlayAtMs: Long? = null

    // 1.1.0 auto offline toggle (default on; downloads may run on
    // mobile data too, per the user's correction — no Wi-Fi gate).
    private val _autoOffline = MutableStateFlow(settings.autoOffline)
    val autoOffline: StateFlow<Boolean> = _autoOffline.asStateFlow()

    /** 1.1.0 Offline Top Picks state for Settings + Home. */
    data class OfflineState(
        val tracks: List<PlexTrack> = emptyList(),
        val downloadedCount: Int = 0,
        val downloading: Boolean = false,
        val downloadIndex: Int = 0,
        val downloadTotal: Int = 0,
        val storageBytes: Long = 0,
        val error: String? = null,
        val emptyReason: String? = null,
    )
    private val _offline = MutableStateFlow(OfflineState())
    val offline: StateFlow<OfflineState> = _offline.asStateFlow()
    private val offlineStore = OfflineStore(java.io.File(application.filesDir, "offline"))
    private var offlineRefreshing = false
    private var lastQueueSaveMs = 0L

    /** 1.1.0: the token that actually worked for sections/artists
     *  in loadHome (account vs server token are not always
     *  interchangeable). Subsequent library calls use this first,
     *  falling back to the server's effective token — this is the
     *  fix for metadata/children 404s where the effective token
     *  differed from the one that listed the artists. */
    private var workingToken: String? = null
    private fun libraryToken(): String =
        workingToken ?: _library.value.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()).orEmpty()

    private var pollJob: Job? = null
    private var currentPinId: Long? = null
    // 1.0.5: the host the current PIN was created on (plex.tv or
    // clients.plex.tv). Polling must use this same host; it is
    // cleared wherever currentPinId is cleared.
    private var currentPinHost: String? = null
    private var pollDeadlineMs: Long = 0L

    // 1.0.5 connection diagnostic (sign-in screen, "Test connection").
    // Independent of sign-in state: it never cancels polling, never
    // sets a sign-in code, and never shows a PIN — see
    // ConnectionDiagnostic.kt for why each step is run separately.
    private val _connectionTest = MutableStateFlow<List<DiagnosticStep>?>(null)
    val connectionTest: StateFlow<List<DiagnosticStep>?> = _connectionTest.asStateFlow()
    private val _connectionTesting = MutableStateFlow(false)
    val connectionTesting: StateFlow<Boolean> = _connectionTesting.asStateFlow()

    fun testConnection() {
        if (_connectionTesting.value) return
        viewModelScope.launch {
            _connectionTesting.value = true
            _connectionTest.value = null
            try {
                _connectionTest.value = runConnectionDiagnostic(client, deviceId)
            } catch (e: Exception) {
                _connectionTest.value = listOf(
                    DiagnosticStep("Connection test", false, "test could not run"),
                )
            } finally {
                _connectionTesting.value = false
            }
        }
    }

    init {
        // 1.3.1: BOOT CRASH FIX — the metadata cache load and the
        // buffer watcher used to live in the first init block,
        // BEFORE _library and _recentlyPlayed were declared.
        // Their coroutines run on another thread, so on a fast
        // cold/warm boot they could touch those properties
        // before the constructor finished initializing them.
        // They now live here, AFTER every property is declared,
        // so a boot can never race the constructor. The load
        // itself is also wrapped so a corrupt/huge cache file
        // can never kill launch.
        viewModelScope.launch {
            playback.currentIndex.collect { runCatching { startBufferNext10() } }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cached = runCatching { metadataCache.load() }.getOrNull()
            if (cached != null && (cached.allTracks.isNotEmpty() || cached.artists.isNotEmpty() || cached.playlists.isNotEmpty())) {
                // 1.5.4 INVALIDATION: a cached snapshot saved under the
                // old genre-inferred mapping (version != 2) must NOT
                // restore its mood counts — they are the 15k counts.
                // Tracks/artists/playlists are kept, counts are
                // dropped, so loadMoodsIncrementally recomputes
                // them from the already-loaded tracks with the
                // new real-Plex-tag mapping, no 45k rescan needed.
                val moodsStale = cached.moodsMappingVersion != com.fourgeailabs.prismtune.data.MOODS_MAPPING_VERSION
                if (moodsStale) {
                    settings.moodsCountsJson = null
                    settings.moodsLoadedCount = 0
                    settings.moodsTotalCount = 0
                }
                val current = _library.value
                _library.value = current.copy(
                    allTracks = cached.allTracks,
                    allTracksLoaded = cached.allTracks.isNotEmpty(),
                    artists = cached.artists,
                    playlists = cached.playlists,
                    recent = cached.recent,
                    recentAlbums = cached.recentAlbums,
                    sections = cached.sections,
                    moodsGenreCounts = if (moodsStale) emptyMap() else cached.moodsGenreCounts,
                    moodsMoodCounts = if (moodsStale) emptyMap() else cached.moodsMoodCounts,
                    moodsVibeCounts = if (moodsStale) emptyMap() else cached.moodsVibeCounts,
                    moodsLoadedCount = if (moodsStale) 0 else cached.moodsLoadedCount,
                    moodsTotalCount = cached.moodsTotalCount.takeIf { it >= 0 },
                )
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceTracks = cached.allTracks
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceLibraryLoaded = cached.allTracks.isNotEmpty()
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.playlists = cached.playlists
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.recentTracks = cached.recent
                if (cached.recentlyPlayed.isNotEmpty()) {
                    _recentlyPlayed.value = cached.recentlyPlayed
                }
            }
        }
        // 1.5.6: restore thumbs curation from private prefs.
        _likedKeys.value = settings.likedTrackKeys
        _dislikedKeys.value = settings.dislikedTrackKeys
        settings.lastPlayedTrack?.let { lp ->
            _lastPlayed.value = PlexTrack(
                key = lp.key, title = lp.title, artistTitle = lp.artistTitle,
                albumTitle = lp.albumTitle, thumb = lp.thumb, durationMs = lp.durationMs,
            )
        }
        // 1.0.4 voice hook: the media service's search callback runs
        // through this handler, which only ever plays tracks from the
        // loaded library (or reports not-loaded / no-match). It never
        // invents a track, and it never plays a random fallback.
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voicePlayFromSearch = { query ->
            val lib = _library.value
            val outcome = com.fourgeailabs.prismtune.data.resolveVoiceSearch(
                query, lib.allTracks, lib.allTracksLoaded,
            )
            if (outcome is com.fourgeailabs.prismtune.data.VoicePlayOutcome.Played) {
                playTracks(outcome.tracks, 0)
            }
            outcome
        }
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voicePlayTracks = { tracks, index ->
            playTracks(tracks, index)
        }
        // 1.2.0: car browser playlist playback.
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.playPlaylistByKey = { key ->
            val pl = _library.value.playlists.firstOrNull { it.key == key }
            if (pl != null) {
                viewModelScope.launch {
                    val base = _library.value.serverBase ?: return@launch
                    val token = libraryToken()
                    if (token.isBlank()) return@launch
                    val tracks = runCatching {
                        client.tracksForPlaylistFor(base, token, deviceId, pl)
                    }.getOrNull()
                    if (!tracks.isNullOrEmpty()) {
                        _library.value = _library.value.copy(detailPlaylist = pl, detailTracks = tracks)
                        playTracks(tracks, 0)
                    }
                }
            }
        }
        // 1.1.1: restore Offline Top Picks metadata saved on a
        // previous successful refresh, so downloaded tracks can be
        // shown and played even if this session's library load fails.
        runCatching {
            val restored = decodeOfflineTracks(settings.savedOfflineTracksJson)
            if (restored.isNotEmpty()) {
                val withLocal = restored.map { tr ->
                    val f = offlineStore.fileFor(tr.key)
                    if (f.exists() && f.length() > 0) tr.copy(localPath = f.absolutePath) else tr
                }
                _offline.value = _offline.value.copy(
                    tracks = withLocal,
                    downloadedCount = withLocal.count { offlineStore.isDownloaded(it.key) },
                    storageBytes = offlineStore.storageBytes(),
                )
            }
        }
        // 1.1.0 resume: restore the saved queue (metadata only) so
        // the mini player / play can resume it after a kill.
        // 1.3.1: guarded — a corrupt saved queue must never kill boot.
        // 1.4.0: validate the saved queue before it can ever
        // be restored. A bad window (empty, out-of-range index,
        // blank keys) is cleared here, not restored, so it can
        // never brick a launch. Sign-in is untouched.
        _restoredQueue.value = runCatching { decodeSavedQueue(settings.savedQueueJson) }.getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() && it.index in it.tracks.indices && it.tracks.all { tr -> tr.key.isNotBlank() } }
            ?: run { if (settings.savedQueueJson != null) settings.savedQueueJson = null; null }
        // 1.1.0 position saving, throttled: every 5s while playing,
        // and immediately on pause/stop — never on every 250ms tick.
        viewModelScope.launch {
            var wasPlaying = false
            kotlinx.coroutines.flow.combine(playback.positionMs, playback.isPlaying) { pos, playing -> pos to playing }
                .collect { (pos, playing) ->
                    if (!playing && wasPlaying) saveQueueSnapshot()
                    if (playing && System.currentTimeMillis() - lastQueueSaveMs >= 5000) saveQueueSnapshot()
                    wasPlaying = playing
                }
        }
        viewModelScope.launch {
            playback.currentIndex.collect { idx ->
                saveQueueSnapshot()
                // 1.5.6 — Recently Played used to record ONLY
                // manual plays (playTracks). Songs reached by
                // Next, auto-advance, steering-wheel or media-
                // session controls never landed in it, so a
                // session of tens of songs could leave Home's
                // Recently Played empty. Every track the player
                // actually lands on is now recorded, newest
                // first, capped at 20. playTracks also prepends
                // — distinctBy keeps that from duplicating.
                val q = playback.queue.value
                val cur = q.getOrNull(idx)
                if (cur != null && cur.key.isNotBlank()) {
                    _recentlyPlayed.value = (listOf(cur) + _recentlyPlayed.value)
                        .distinctBy { it.key }.take(20)
                }
            }
        }
    }

    /** Persist the current queue + index + position (metadata only).
     * 1.1.3: NEVER serialise a 45k queue every 5 s on the main
     * thread — that froze playback. Persist a window around the
     * current track (50 before, 500 after, index adjusted), encode
     * on Dispatchers.IO, and do NOT decode it back on every save.
     * Resume still works from the window; tracks outside it were
     * never reachable without reloading the library anyway. */
    fun saveQueueSnapshot() {
        val queue = playback.queue.value
        if (queue.isEmpty()) return
        lastQueueSaveMs = System.currentTimeMillis()
        val index = playback.currentIndex.value.coerceIn(0, queue.size - 1)
        val from = (index - 50).coerceAtLeast(0)
        val to = (index + 500).coerceAtMost(queue.size - 1)
        val window = queue.subList(from, to + 1).map { it.copy(localPath = null) }
        val savedIndex = index - from
        val position = playback.positionMs.value
        val source = lastQueueSource
        val serverId = _library.value.selectedServer?.id
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            settings.savedQueueJson = encodeSavedQueue(
                window, savedIndex, position,
                sourceKind = source?.kind, sourceName = source?.name,
                sourceKey = source?.key, serverId = serverId,
            )
        }
    }

    /**
     * 1.1.5 auto-restore: after the library loads and a server
     * base is known, requeue the most recent playlist/song
     * PAUSED at its saved position — no tap needed, no full
     * library rescan, no autoplay on launch. Playlist sources
     * are re-fetched from the server so the full playlist
     * requeues, not just the 551-track window saved in prefs;
     * other sources fall back to that window. Runs once per
     * launch, and a failed fetch degrades to the window, never
     * a crash and never a blank queue when a saved one exists.
     */
    /** 1.2.3: rebuild a saved queue to a full next-100 from
     *  previously loaded metadata. If the saved window has
     *  fewer than 100 tracks after the current one, append
     *  tracks from the cached library (allTracks) after the
     *  current track, deduplicated by key, until there are
     *  100 up next or the library runs out. This is what makes
     *  Up Next survive a close/reopen with a full queue, not
     *  just the few tracks that happened to be saved. */
    fun expandQueueToNext100(
        tracks: List<com.fourgeailabs.prismtune.data.PlexTrack>,
        index: Int,
        library: List<com.fourgeailabs.prismtune.data.PlexTrack>,
    ): List<com.fourgeailabs.prismtune.data.PlexTrack> {
        // 1.3.1: empty queue or out-of-range index must return
        // the queue unchanged, never a coerceIn(0, -1) crash.
        if (tracks.isEmpty() || index < 0 || index >= tracks.size) return tracks
        if (tracks.size - index - 1 >= 100) return tracks
        val currentKey = tracks.getOrNull(index)?.key ?: return tracks
        val libIdx = library.indexOfFirst { it.key == currentKey }
        if (libIdx < 0) return tracks
        val existing = tracks.map { it.key }.toMutableSet()
        val out = tracks.toMutableList()
        for (t in library.drop(libIdx + 1)) {
            if (out.size - index - 1 >= 100) break
            // 1.5.6 thumbs-down curation: a disliked track
            // never fills the queue from the library.
            if (t.key in _dislikedKeys.value) continue
            if (existing.add(t.key)) out.add(t)
        }
        return out
    }

    private fun tryAutoRestore() {
        if (autoRestoreAttempted) return
        autoRestoreAttempted = true
        val saved = _restoredQueue.value ?: return
        if (playback.queue.value.isNotEmpty()) return
        val lib = _library.value
        val base = lib.serverBase
        val token = libraryToken()
        viewModelScope.launch {
            try {
                // 1.3.1: an empty saved queue must never reach a
                // coerceIn(0, size-1) below — that throws when
                // size is 0 and would crash boot.
                if (saved.tracks.isEmpty()) return@launch
                val withLocal = saved.tracks.map { t ->
                    val f = offlineStore.fileFor(t.key)
                    if (f.exists() && f.length() > 0) t.copy(localPath = f.absolutePath) else t
                }
                // 1.4.0 SAFE LAUNCH: no playlist re-fetch and no
                // player restore on launch. A re-fetch + restore
                // on the Equinox is exactly the path that bricked
                // every launch after a play: it built ExoPlayer
                // items and started the service before the user
                // tapped anything. The saved window below is
                // shown as metadata only; tapping it plays it
                // through resumeSavedQueue(), on purpose.
                // Fallback: windowed saved tracks, paused.
                // 1.2.1: even if the server is unreachable, restore
                // the queue as metadata so Next Up, the mini player,
                // and the right panel show it instead of empty.
                // Do NOT mark auto-restore as done for a metadata
                // restore — the next successful library load will
                // retry the real player restore.
                // 1.2.3: expand the saved window to a full next-100
                // from previously loaded metadata, so Up Next is
                // not just the saved window after a reopen.
                val expanded = expandQueueToNext100(
                    withLocal, saved.index.coerceIn(0, withLocal.size - 1), lib.allTracks
                )
                if (base == null && withLocal.none { it.localPath != null }) {
                    lastQueueSource = saved.sourceKind?.let { QueueSource(it, saved.sourceName ?: it, saved.sourceKey) }
                    _queueSource.value = lastQueueSource
                    playback.setQueueMetadata(expanded, saved.index.coerceIn(0, expanded.size - 1))
                    _lastPlayed.value = expanded.getOrNull(saved.index.coerceIn(0, expanded.size - 1))
                    autoRestoreAttempted = false
                    return@launch
                }
                lastQueueSource = saved.sourceKind?.let { QueueSource(it, saved.sourceName ?: it, saved.sourceKey) }
                _queueSource.value = lastQueueSource
                // 1.4.0 SAFE LAUNCH: metadata only, never the
                // player. setQueueMetadata shows the queue in
                // Up Next / mini player without building
                // ExoPlayer items, seeking, or starting a
                // foreground service. Play comes only from an
                // explicit tap, via resumeSavedQueue().
                playback.setQueueMetadata(expanded, saved.index.coerceIn(0, expanded.size - 1))
                _lastPlayed.value = expanded.getOrNull(saved.index.coerceIn(0, expanded.size - 1))
                // 1.5.7 COLD-BOOT PLAY FIX, part 1 —
                // 2026-10-27 07:28: pressing play on the mini
                // player on a cold boot did nothing. Cause:
                // this metadata-only restore never told the
                // player the server connection, so
                // PlaybackManager.queueServerBase stayed blank
                // and togglePlayPause's 1.5.0 rebuild guard
                // (which needs a non-blank base) refused to
                // build player items — player.play() on an
                // empty playlist is a silent no-op. Now the
                // connection is recorded WITHOUT building
                // items or starting the service, so the safe-
                // launch shape is unchanged but a later tap
                // can actually build and play. Part 2 is
                // playFromMiniPlayer, below.
                if (base != null) {
                    playback.setQueueConnection(base, token, _audioQuality.value)
                }
                // 1.5.7 pending play: if the user tapped play
                // while the library was still loading, this
                // load landing within 30 s of that tap means
                // the tap finally has a serverBase to play
                // with — resume (which restores player items
                // and plays) instead of leaving the tap dead.
                // Older than 30 s, the tap is dropped, so
                // music never starts out of nowhere later.
                val pendingAt = pendingPlayAtMs
                if (pendingAt != null) {
                    pendingPlayAtMs = null
                    if (System.currentTimeMillis() - pendingAt <= 30_000) {
                        resumeSavedQueue()
                    }
                }
            } catch (_: Exception) {
                // Restore failure must never crash launch; the
                // saved queue stays on disk for a manual retry.
            }
        }
    }

    /** Stale-banner fix: a new navigation clears the previous screen error. */
    fun clearScreenErrors() {
        val lib = _library.value
        if (lib.error != null || lib.detailError != null || lib.searchError != null) {
            _library.value = lib.copy(error = null, detailError = null, searchError = null)
        }
    }

    /** Record a sanitised diagnostic entry (never a token/password). */
    fun recordDiagnostic(call: String, endpoint: String, e: Throwable) {
        _diagnostics.value = appendDiagnostic(_diagnostics.value, diagnosticEntryFor(call, endpoint, e))
    }

    fun clearDiagnostics() {
        _diagnostics.value = emptyList()
    }

    /**
     * 1.4.0: clear the saved queue WITHOUT signing out and
     * WITHOUT clearing the token, server, or metadata cache.
     * This is the no-reinstall recovery for a launch-after-play
     * crash loop: if a saved queue is what bricks launch, this
     * wipes it and leaves sign-in intact. Called from Settings
     * > Diagnostics. Also used internally when a saved queue
     * fails validation, so a bad window can never be restored.
     */
    fun clearSavedQueue() {
        settings.savedQueueJson = null
        _restoredQueue.value = null
        autoRestoreAttempted = true
    }

    /**
     * 1.0.4: load every track in the first music section, once. The
     * Feels, library search, and voice search all run on this list, not
     * on the 24 recently-added tracks or on one album at a time.
     * Safe to call repeatedly: a second call while loading or after a
     * successful load is a no-op.
     */
    fun ensureAllTracksLoaded() {
        val lib = _library.value
        if (lib.allTracksLoaded || lib.loadingAllTracks) return
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        val section = lib.sections.firstOrNull() ?: return
        viewModelScope.launch {
            _library.value = _library.value.copy(loadingAllTracks = true)
            try {
                val all = client.allTracks(base, token, deviceId, section.key)
                _library.value = _library.value.copy(
                    allTracks = all, allTracksLoaded = true, loadingAllTracks = false,
                )
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceTracks = all
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceLibraryLoaded = true
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "all tracks")) {
                    _library.value = _library.value.copy(loadingAllTracks = false)
                }
            }
        }
    }

    /** Voice search against the loaded library, via the existing playback path. */
    fun playFromVoiceSearch(query: String): com.fourgeailabs.prismtune.data.VoicePlayOutcome {
        val lib = _library.value
        val outcome = com.fourgeailabs.prismtune.data.resolveVoiceSearch(
            query, lib.allTracks, lib.allTracksLoaded,
        )
        if (outcome is com.fourgeailabs.prismtune.data.VoicePlayOutcome.Played) {
            playTracks(outcome.tracks, 0)
        }
        return outcome
    }

    /** Polls one PIN id until its authToken appears or the deadline
     *  passes. Lives in viewModelScope, not in any composable, so
     *  leaving the app for the car browser and coming back does not
     *  kill it. Only one poll job runs at a time. */
    private fun startPolling(pinId: Long, host: String = com.fourgeailabs.prismtune.data.PlexUrls.PLEX_TV) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (System.currentTimeMillis() < pollDeadlineMs) {
                delay(2000)
                val result = runCatching { client.pollPin(pinId, deviceId, host) }.getOrNull()
                val token = result?.authToken
                if (!token.isNullOrBlank()) {
                    tokenStore.saveToken(token)
                    currentPinId = null
                    currentPinHost = null
                    _signIn.value = SignInState.Done
                    loadServers()
                    return@launch
                }
            }
            currentPinId = null
            currentPinHost = null
            _signIn.value = SignInState.Error("Link code expired. Tap Get link code for a fresh code.")
        }
    }

    /**
     * Called when the Sign-in screen returns to a Waiting state (for
     * example, after the user went to the car browser and came back).
     * If polling is still running, this is a no-op. If it stopped
     * without a token, it restarts for the SAME pin id — it never
     * creates a second PIN or a second code.
     */
    fun resumeSignInPolling() {
        val pinId = currentPinId ?: return
        if (_signIn.value !is SignInState.Waiting) return
        if (pollJob?.isActive == true) return
        if (System.currentTimeMillis() >= pollDeadlineMs) {
            currentPinId = null
            currentPinHost = null
            _signIn.value = SignInState.Error("Link code expired. Tap Get link code for a fresh code.")
            return
        }
        startPolling(pinId, currentPinHost ?: com.fourgeailabs.prismtune.data.PlexUrls.PLEX_TV)
    }

    val isSignedIn: Boolean get() = tokenStore.getToken() != null
    val deviceId: String get() = settings.deviceId

    fun startSignIn() {
        pollJob?.cancel()
        currentPinId = null
        currentPinHost = null
        _signIn.value = SignInState.Creating
        viewModelScope.launch {
            try {
                val pin = client.createPin(deviceId)
                if (pin.code.isBlank()) throw IllegalStateException("Plex returned an empty link code.")
                // 1.0.3: weak 4-character PIN, exactly as in 1.0.0.
                // The QR encodes plain plex.tv/link and the code is
                // shown large to type there. No strong parameter is
                // sent, no Auth App URL is built or rendered, and the
                // code is never invented, guessed, or fallen back to.
                // If this POST fails, the catch below shows an error
                // and NO code and NO QR are ever displayed.
                currentPinId = pin.id
                currentPinHost = pin.host
                pollDeadlineMs = System.currentTimeMillis() + 15 * 60 * 1000L
                _signIn.value = SignInState.Waiting(
                    code = pin.code,
                    qrBitmap = QrCode.render(PlexUrls.LINK_URL, 640),
                    linkUrl = PlexUrls.LINK_URL,
                )
                startPolling(pin.id, pin.host)
            } catch (e: Exception) {
                currentPinId = null
                currentPinHost = null
                // 1.0.6: PIN creation uses pinCreationErrorMessage,
                // NOT signInErrorMessage — a PIN-creation failure
                // must never read as a token rejection, and must
                // show the host/status/body instead. The Error branch
                // shows Get link code again, with no code and no QR.
                _signIn.value = SignInState.Error(pinCreationErrorMessage(e))
            }
        }
    }

    /**
     * 1.0.8: username/password sign-in, additional to PIN.
     * The password is passed straight to PlexClient and is
     * never stored anywhere in this ViewModel, never logged,
     * and never included in any error message (see
     * passwordSignInErrorMessage, which only renders the
     * exception's URL/status/Plex body). On success the
     * returned token — and only the token — is saved via
     * TokenStore, state becomes Done, and loadServers runs
     * exactly as after a PIN approval. A TwoFactorRequired
     * failure becomes the distinct 2FA prompt message, not
     * a generic rejection, so the user can enter the code
     * and tap Sign in again with it.
     */
    fun signInWithPassword(login: String, password: String, verificationCode: String?) {
        if (login.isBlank() || password.isBlank()) {
            _signIn.value = SignInState.Error(
                "Enter your Plex username or email and password, then sign in again.",
            )
            return
        }
        pollJob?.cancel()
        currentPinId = null
        currentPinHost = null
        _signIn.value = SignInState.PasswordSigningIn
        viewModelScope.launch {
            try {
                val token = client.signInWithPassword(
                    login,
                    password,
                    deviceId,
                    verificationCode?.takeIf { it.isNotBlank() },
                )
                if (token.isBlank()) throw IllegalStateException("Plex returned an empty token.")
                tokenStore.saveToken(token)
                _signIn.value = SignInState.Done
                loadServers()
            } catch (e: Exception) {
                _signIn.value = SignInState.Error(passwordSignInErrorMessage(e))
            }
        }
    }

    fun cancelSignIn() {
        pollJob?.cancel()
        currentPinId = null
        currentPinHost = null
        _signIn.value = SignInState.Idle
    }

    fun signOut() {
        pollJob?.cancel()
        currentPinId = null
        currentPinHost = null
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceTracks = emptyList()
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceLibraryLoaded = false
        tokenStore.clear()
        // 1.2.3: sign-out deletes the metadata cache, so a
        // different user never sees the previous user's library.
        metadataCache.clear()
        settings.serverId = null
        _library.value = LibraryState()
        _signIn.value = SignInState.Idle
        // MainActivity observes tokenStore.token and navigates to "signin"
        // when it becomes null, so sign-out lands on the QR screen instead
        // of stranding the user on Settings/Home with no token.
    }

    private fun isUnauthorized(e: Exception): Boolean =
        (e as? com.fourgeailabs.prismtune.data.PlexException)?.statusCode == 401 ||
            e.message?.contains(" 401 ") == true

    /**
     * A 401 means Plex rejected the stored token. The stale token must not
     * stay in place and must not be retried: clear it, drop the library,
     * and show a plain message on the Sign-in screen. MainActivity's
     * token observer then navigates there; no library load is re-attempted
     * with the rejected token.
     */
    private fun handleUnauthorized(call: String = "server list") {
        pollJob?.cancel()
        currentPinId = null
        currentPinHost = null
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceTracks = emptyList()
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceLibraryLoaded = false
        tokenStore.clear()
        settings.serverId = null
        _library.value = LibraryState()
        // 1.0.6: this text is ONLY for a 401 after a token exists
        // (resources/library loading), never for PIN creation.
        // The failing call is named so a server-list 401 is not
        // confused with a library-sections 401; the core meaning
        // (rejected token, sign in again) is unchanged.
        _signIn.value = SignInState.Error(
            "Plex rejected that sign-in, please sign in again (while loading $call)",
        )
    }

    /** Returns true when the error was a 401 and was handled above.
     *  1.2.3: only core auth calls (server list, library sections,
     *  all tracks) clear the token and sign the user out. A 401 on
     *  a detail, search, album, or playlist call must NOT sign the
     *  user out — that was wiping Moods, the library, and the
     *  sign-in itself on a single transient detail 401, leaving
     *  the user staring at "Sign in and load your library" with
     *  empty Moods. For non-core calls, return false so the
     *  caller shows its own detail error, and the token stays. */
    private fun handleLibraryFailure(e: Exception, call: String = "server list"): Boolean {
        if (!isUnauthorized(e)) return false
        if (call in listOf("server list", "library sections", "all tracks")) {
            handleUnauthorized(call)
            return true
        }
        return false
    }

    /**
     * 1.0.7: all-hosts server-list failure. Unlike a plain 401,
     * this error already names every host, status, and Plex body
     * that resources() tried, so showing only the generic
     * "Plex rejected" text would throw that detail away. Clear
     * the token and library exactly as handleUnauthorized does
     * (MainActivity's token observer then navigates to Sign-in),
     * but show the detailed combined message on the Sign-in
     * screen instead. DNS failures never reach here: they are
     * not 401s, so handleLibraryFailure returns false for them
     * and the plain-language network path is untouched. No token
     * is in the message: resources() messages carry only URLs,
     * statuses, and Plex bodies.
     */
    private fun handleServerListFailure(e: Exception) {
        pollJob?.cancel()
        currentPinId = null
        currentPinHost = null
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceTracks = emptyList()
        com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceLibraryLoaded = false
        tokenStore.clear()
        settings.serverId = null
        _library.value = LibraryState()
        _signIn.value = SignInState.Error(e.message.orEmpty())
    }

    private fun isServerListFailure(e: Exception): Boolean =
        e.message.orEmpty().startsWith("Server list failed:")

    fun loadServers() {
        val token = tokenStore.getToken() ?: return
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null)
            try {
                val servers = client.resources(token, deviceId)
                val saved = settings.serverId
                val selected = servers.firstOrNull { it.id == saved } ?: servers.firstOrNull()
                val base = selected?.let { ConnectionPicker.ordered(it.connections, settings.lastWorkingConnectionUri).firstOrNull()?.uri }
                _library.value = _library.value.copy(
                    servers = servers,
                    selectedServer = selected,
                    serverBase = base,
                    loading = false,
                )
                if (selected != null && base != null) {
                    settings.serverId = selected.id
                    loadHomeWithFallback(selected, selected.effectiveToken(token), token)
                }
            } catch (e: Exception) {
                // 1.0.7: a combined all-hosts server-list failure
                // shows its detailed message on Sign-in, not the
                // generic rejection; plain 401s keep the generic
                // text with the call named, as in 1.0.6.
                if (isServerListFailure(e) &&
                    !com.fourgeailabs.prismtune.data.PlexClient.isDnsFailure(e)
                ) {
                    handleServerListFailure(e)
                } else if (!handleLibraryFailure(e, "server list")) {
                    _library.value = _library.value.copy(loading = false, error = "Could not list servers: ${e.message}")
                }
            }
        }
    }

    fun selectServer(server: PlexServer) {
        val token = tokenStore.getToken() ?: return
        if (server.connections.isEmpty()) return
        settings.serverId = server.id
        _library.value = _library.value.copy(selectedServer = server)
        loadHomeWithFallback(server, server.effectiveToken(token), token)
    }

    /**
     * 1.1.1 cellular fallback: try the server's connections in
     * order (remote first, then local, then relay — the user's
     * correction: a car is usually away from home) with a
     * short-timeout sections probe. The first
     * connection that returns sections wins and the rest of the
     * library load runs on it; a timeout/unreachable failure on
     * one connection moves to the next. Only if ALL fail does
     * the load error, naming connection kinds only (never URIs
     * or IPs), and downloaded Offline Top Picks stay visible.
     * A 401 is not a connection failure: it is rethrown at once
     * (token handling, not fallback).
     */
    private fun loadHomeWithFallback(server: PlexServer, token: String, accountToken: String) {
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null, connectionStatus = null)
            val ordered = ConnectionPicker.ordered(server.connections, settings.lastWorkingConnectionUri)
            if (ordered.isEmpty()) {
                _library.value = _library.value.copy(loading = false, error = "Could not load library: this server has no connections to try.")
                return@launch
            }
            // Show progress as each connection is tried, without
            // ever putting a URI/IP in user-facing text.
            val probe: suspend (String) -> Pair<List<PlexSection>, String> = { base ->
                _library.value = _library.value.copy(connectionStatus = "Trying ${ConnectionPicker.kindOf(ordered.firstOrNull { it.uri == base } ?: ordered.first())} connection…")
                client.musicSectionsWithTokenFallbackFast(base, token, accountToken, deviceId)
            }
            try {
                val (probeResult, winningBase, _) = tryServerConnections(server.connections, settings.lastWorkingConnectionUri, probe)
                val (sections, workingTok) = probeResult
                settings.lastWorkingConnectionUri = winningBase
                _library.value = _library.value.copy(connectionStatus = null, serverBase = winningBase)
                loadHomeFromSections(winningBase, sections, workingTok)
            } catch (e: Exception) {
                _library.value = _library.value.copy(connectionStatus = null)
                if (e is AllConnectionsFailed) {
                    recordDiagnostic("library load", "/library/sections", e)
                    _library.value = _library.value.copy(
                        loading = false,
                        error = "Could not load library: tried ${e.triedKinds.joinToString(", ")} connections and none could be reached. Your downloaded Offline Top Picks are still available below.",
                    )
                } else if (!handleLibraryFailure(e, "library sections")) {
                    _library.value = _library.value.copy(loading = false, error = "Could not load library: ${e.message?.take(200)}")
                }
            }
        }
    }

    private fun loadHome(serverBase: String, token: String, accountToken: String = token) {
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null)
            try {
                // 1.0.7: if the server token gets a 401 here, the
                // account token gets one try, and the token that
                // works is used for the rest of this load.
                val (sections, workingTok) = client.musicSectionsWithTokenFallback(
                    serverBase, token, accountToken, deviceId,
                )
                loadHomeFromSections(serverBase, sections, workingTok)
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "library sections")) {
                    _library.value = _library.value.copy(loading = false, error = "Could not load library: ${e.message}")
                }
            }
        }
    }

    /** 1.1.1: rest of a library load once sections + working token are known (any base). */
    private suspend fun loadHomeFromSections(serverBase: String, sections: List<PlexSection>, workingTok: String) {
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null, serverBase = serverBase)
            try {
                workingToken = workingTok
                val workingToken = workingTok
                val first = sections.firstOrNull()
                val artists = first?.let { client.artists(serverBase, workingToken, deviceId, it.key) } ?: emptyList()
                val recent = first?.let { client.recentlyAdded(serverBase, workingToken, deviceId, it.key) } ?: emptyList()
                val recentAlbums = first?.let { runCatching { client.recentlyAddedAlbums(serverBase, workingToken, deviceId, it.key) }.getOrDefault(emptyList()) } ?: emptyList()
                val playlistsRaw = runCatching { client.playlists(serverBase, workingToken, deviceId) }.getOrDefault(emptyList())
                // 1.1.0 playlist counts: smart/dynamic playlists
                // report leafCount 0 on /playlists even with items.
                // For those, children totalSize (Size=0, no tracks
                // loaded) gives the true count; a failed count
                // check keeps the original 0 rather than inventing.
                val playlists = playlistsRaw.map { pl ->
                    if (pl.itemCount != 0) pl else {
                        val counted = runCatching { client.playlistItemCountFor(serverBase, workingToken, deviceId, pl) }.getOrDefault(-1)
                        pl.copy(itemCount = com.fourgeailabs.prismtune.data.correctedPlaylistCount(pl.itemCount, counted))
                    }
                }
                _library.value = _library.value.copy(
                    sections = sections,
                    artists = artists,
                    recent = recent,
                    recentAlbums = recentAlbums,
                    playlists = playlists,
                    loading = false,
                )
                // 1.2.0: snapshots for the car media browser.
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.playlists = playlists
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.recentTracks = recent
                // 1.2.3: soft resync — replace the cached snapshot
                // with this fresh server list. Replacement is the
                // prune: media no longer on the server is not in
                // the new snapshot, so it disappears from the app.
                // A failed load never reaches here, so a failed
                // resync keeps the cached snapshot on screen.
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val lib = _library.value
                    runCatching {
                        metadataCache.save(
                            com.fourgeailabs.prismtune.data.MetadataSnapshot(
                                allTracks = lib.allTracks,
                                artists = artists,
                                playlists = playlists,
                                recent = recent,
                                recentlyPlayed = _recentlyPlayed.value,
                                recentAlbums = recentAlbums,
                                sections = sections,
                                moodsGenreCounts = lib.moodsGenreCounts,
                                moodsMoodCounts = lib.moodsMoodCounts,
                                moodsVibeCounts = lib.moodsVibeCounts,
                                moodsLoadedCount = lib.moodsLoadedCount,
                                moodsTotalCount = lib.moodsTotalCount ?: -1,
                                moodsMappingVersion = com.fourgeailabs.prismtune.data.MOODS_MAPPING_VERSION,
                            )
                        )
                    }
                }
                // 1.1.5: requeue the most recent playlist/song
                // now that a server base is known, paused.
                tryAutoRestore()
                // 1.2.2: NO automatic Offline Top Picks refresh on
                // library load. That refresh loads all tracks and
                // expands top playlists (All Music alone is 46k
                // tracks) and downloads 100 tracks, which ran
                // ~30 seconds after cold boot and was a quit/OOM
                // suspect in the car. Offline Top Picks now refresh
                // only when the user taps "Refresh now" in Settings,
                // or enables Auto Offline there. Saved offline
                // tracks still load from disk at launch.
                // (autoOffline flag is kept for Settings, but no
                // longer triggers a launch-time refresh.)
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "library sections")) {
                    _library.value = _library.value.copy(loading = false, error = "Could not load library: ${e.message}")
                }
            }
        }
    }

    fun openArtist(artist: PlexArtist) {
        // Kept for compatibility; Library/Home/Search now use
        // openArtistDetail() for a dedicated view. Still uses the
        // working token and the artist's own key path.
        openArtistDetail(artist)
    }

    /** 1.1.0 dedicated artist view: this artist's albums only. */
    fun openArtistDetail(artist: PlexArtist) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        viewModelScope.launch {
            _library.value = _library.value.copy(
                detailArtist = artist, detailAlbum = null, detailPlaylist = null,
                detailAlbums = emptyList(), detailTracks = emptyList(),
                detailError = null, detailLoading = true,
            )
            try {
                val albums = client.albumsForArtistFor(base, token, deviceId, artist)
                _library.value = _library.value.copy(detailAlbums = albums, detailLoading = false)
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "artist albums")) {
                    _library.value = _library.value.copy(
                        detailLoading = false,
                        detailError = "Could not load albums for ${artist.title}: ${e.message?.take(200)}".also { recordDiagnostic("artist children", "/library/metadata/${artist.key}/children", e) },
                    )
                }
            }
        }
    }

    /** 1.1.0 dedicated album view: this album's tracks only. */
    fun openAlbumDetail(album: PlexAlbum) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        viewModelScope.launch {
            _library.value = _library.value.copy(
                detailAlbum = album, detailArtist = null, detailPlaylist = null,
                detailAlbums = emptyList(), detailTracks = emptyList(),
                detailError = null, detailLoading = true,
            )
            try {
                val tracks = client.tracksForAlbum(base, token, deviceId, album.key)
                _library.value = _library.value.copy(detailTracks = tracks, detailLoading = false)
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "album tracks")) {
                    _library.value = _library.value.copy(
                        detailLoading = false,
                        detailError = "Could not load tracks for ${album.title}: ${e.message?.take(200)}".also { recordDiagnostic("album tracks", "/library/metadata/${album.key}/children", e) },
                    )
                }
            }
        }
    }

    /** 1.1.0 dedicated playlist view: this playlist's tracks only. */
    fun openPlaylistDetail(playlist: PlexPlaylist) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        viewModelScope.launch {
            _library.value = _library.value.copy(
                detailPlaylist = playlist, detailArtist = null, detailAlbum = null,
                detailAlbums = emptyList(), detailTracks = emptyList(),
                detailError = null, detailLoading = true,
            )
            try {
                val tracks = client.tracksForPlaylistFor(base, token, deviceId, playlist)
                // 1.2.0: correct the Home playlist count to the
                // real loaded size, so playlists that Plex reported
                // as 0 no longer show a fake count.
                val updatedPlaylists = _library.value.playlists.map { p ->
                    if (p.key == playlist.key) p.copy(itemCount = tracks.size) else p
                }
                // 1.5.6: if Plex reported items for this playlist
                // (or the count check did) but every load path
                // returned none, say so plainly instead of
                // showing a silent "0 tracks" — and leave the
                // trail in diagnostics so the cause is findable
                // in Settings > Crash logs.
                if (tracks.isEmpty() && playlist.itemCount > 0) {
                    val msg = "Plex reported ${playlist.itemCount} tracks for ${playlist.title} but returned none. Check Settings > Crash logs for the detail."
                    _library.value = _library.value.copy(
                        detailTracks = emptyList(), detailLoading = false,
                        detailError = msg.also { recordDiagnostic("playlist tracks empty", com.fourgeailabs.prismtune.data.playlistItemsPath(playlist), Exception("Plex returned no items for ${playlist.title} despite reported count ${playlist.itemCount}")) },
                    )
                    return@launch
                }
                _library.value = _library.value.copy(
                    detailTracks = tracks, detailLoading = false, playlists = updatedPlaylists,
                )
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.playlists = updatedPlaylists
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "playlist tracks")) {
                    // 1.1.2: user-facing error names the playlist
                    // title only — never the full URL/IP/token
                    // that PlexException messages carry.
                    val status = (e as? com.fourgeailabs.prismtune.data.PlexException)?.statusCode
                    val detailError = if (status != null) "Could not load tracks for ${playlist.title}: failed: $status" else "Could not load tracks for ${playlist.title}"
                    _library.value = _library.value.copy(
                        detailLoading = false,
                        detailError = detailError.also { recordDiagnostic("playlist tracks", com.fourgeailabs.prismtune.data.playlistItemsPath(playlist), e) },
                    )
                }
            }
        }
    }

    fun openAlbum(album: PlexAlbum) { openAlbumDetail(album) }

    fun openPlaylist(playlist: PlexPlaylist) { openPlaylistDetail(playlist) }

    fun search(query: String) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        // 1.2.3: record the search query for Home recommendations,
        // most recent first, max 20, deduplicated.
        if (query.isNotBlank()) {
            val trimmed = query.trim()
            settings.searchHistory = (listOf(trimmed) + settings.searchHistory.filter { it != trimmed }).take(20)
        }
        if (query.isBlank()) {
            _library.value = lib.copy(searchResults = emptyList())
            return
        }
        viewModelScope.launch {
            try {
                val results = client.search(base, token, deviceId, query)
                _library.value = _library.value.copy(searchResults = results)
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "search")) {
                    _library.value = _library.value.copy(error = "Search failed: ${e.message}")
                }
            }
        }
    }

    /**
     * 1.1.0 playback: resolve the starting track's partId on
     * demand before playing — listings (recentlyAdded, /all)
     * often omit Media/Part, leaving partId=-1 and a dead
     * /library/parts/-1/file URL, which previously failed
     * silently. If the start track still has no part after a
     * metadata fetch, playback is NOT started on a -1 URL;
     * the plain error is surfaced via PlaybackManager. Other
     * queue items are resolved in the background (first 20)
     * and swapped in via updateTrackPartId. Also persists
     * the last-played track (metadata only) for the mini player.
     */
    fun playTracks(tracks: List<PlexTrack>, startIndex: Int) {
        val lib = _library.value
        if (tracks.isEmpty()) return
        val start = tracks.getOrNull(startIndex) ?: return
        // 1.1.1 offline play: a downloaded start track plays even
        // with no server base (server unreachable). Other tracks
        // still need a base/token; that path is unchanged below.
        val startDownloaded = offlineStore.isDownloaded(start.key)
        val base = lib.serverBase ?: if (startDownloaded) "" else return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: if (startDownloaded) "" else return
        playback.clearPlaybackError()
        viewModelScope.launch {
            var resolvedQueue = tracks
            var resolvedStart = start
            var effectiveStartIndex = startIndex
            try {
                if (start.partId < 0 && !startDownloaded) {
                    val full = client.trackMetadata(base, token, deviceId, start.key)
                    if (full != null && full.partId >= 0) {
                        resolvedStart = full
                        resolvedQueue = tracks.toMutableList().also { it[startIndex] = full }
                    }
                }
            } catch (_: Exception) { /* fall through: -1 handled below */ }
            if (resolvedStart.partId < 0 && !offlineStore.isDownloaded(resolvedStart.key)) {
                // Do not play a -1 part URL; surface via the queue
                // error channel by setting a queue with the track
                // and letting PlaybackManager report it? Instead,
                // keep the queue but do not start it, and record
                // the failure in library error (plain, no token).
                recordDiagnostic("play track", "/library/metadata/${resolvedStart.key}", Exception("No playable file for track"))
                _library.value = _library.value.copy(error = "Could not find a playable file for \"${resolvedStart.title}\" on your server.")
                return@launch
            }
            // 1.2.3: a single-track queue leaves Up Next empty
            // and Next as a dead button. Expand a single track to
            // its album's tracks when possible, so there is
            // actually something up next.
            if (resolvedQueue.size == 1) {
                val albumKey = resolvedStart.albumKey
                if (albumKey != null && base.isNotBlank()) {
                    val albumTracks = runCatching { client.tracksForAlbum(base, token, deviceId, albumKey) }.getOrNull()
                    if (!albumTracks.isNullOrEmpty()) {
                        val idx = albumTracks.indexOfFirst { it.key == resolvedStart.key }
                        if (idx >= 0) {
                            resolvedQueue = albumTracks
                            resolvedStart = albumTracks[idx]
                            effectiveStartIndex = idx
                        }
                    }
                }
            }
            // 1.1.5: record where this queue came from, so it
            // can be requeued after close/crash. Detail screens
            // set detailPlaylist/detailAlbum/detailArtist, and
            // playFeel sets moodsPlayingFeel + lastFeelKind.
            lastQueueSource = when {
                resolvedQueue.size > 1 && tracks.size == 1 && resolvedStart.albumKey != null ->
                    QueueSource("Album", resolvedStart.albumTitle, resolvedStart.albumKey)
                lib.detailPlaylist != null && tracks == lib.detailTracks ->
                    QueueSource("Playlist", lib.detailPlaylist.title, lib.detailPlaylist.key)
                lib.detailAlbum != null && tracks == lib.detailTracks ->
                    QueueSource("Album", lib.detailAlbum.title, lib.detailAlbum.key)
                lib.detailArtist != null && tracks == lib.detailTracks ->
                    QueueSource("Artist", lib.detailArtist.title, lib.detailArtist.key)
                lib.moodsPlayingFeel != null ->
                    QueueSource(lastFeelKind ?: "Mood", lib.moodsPlayingFeel, null)
                tracks == _offline.value.tracks ->
                    QueueSource("Offline Top Picks", "Offline Top Picks", null)
                else -> QueueSource("Library", "Library", null)
            }
            _queueSource.value = lastQueueSource
            // 1.3.7: EVERY play fills Up Next to 100, not just
            // single-track album expansion. 2026-10-26
            // 23:50: "Only 1 song gets loaded in to the next
            // up queue and the app doesn't add more." That was
            // playTracks with a 1-track list (Recently Played,
            // search, a mood with 1 match) — queue size 1, Up
            // Next = "End of queue". Now, if fewer than 100
            // tracks follow the current one, fill from the
            // full library after the current track, in library
            // order, deduplicated. If the library is not loaded
            // yet, the queue stays as-is and nextTrack() still
            // re-fetches the source, so this never invents a
            // track — it only extends from real library tracks.
            val expandedQueue = expandQueueToNext100(resolvedQueue, effectiveStartIndex, lib.allTracks)
            // 1.1.0 offline preference: swap in downloaded files
            // where they exist, so playback works offline.
            val withLocal = expandedQueue.map { t ->
                val f = offlineStore.fileFor(t.key)
                if (f.exists() && f.length() > 0) t.copy(localPath = f.absolutePath) else t
            }
            playback.setQueue(withLocal, effectiveStartIndex, base, token, _audioQuality.value)
            // 1.3.7: buffer the next 10 immediately on a manual
            // play, so skipping is seamless. startBufferNext10
            // is also triggered by index changes, but with a
            // 10 s boot delay — a manual play should not wait
            // that long. The buffer job itself still paces
            // downloads 2 s apart.
            runCatching { startBufferNext10() }
            settings.recordPlay(resolvedStart.key)
            saveQueueSnapshot()
            _lastPlayed.value = resolvedStart
            settings.lastPlayedTrack = com.fourgeailabs.prismtune.data.LastPlayedTrack(
                key = resolvedStart.key, title = resolvedStart.title,
                artistTitle = resolvedStart.artistTitle, albumTitle = resolvedStart.albumTitle,
                thumb = resolvedStart.thumb, durationMs = resolvedStart.durationMs,
            )
            _recentlyPlayed.value = (listOf(resolvedStart) + _recentlyPlayed.value)
                .distinctBy { it.key }.take(20)
            // Background: resolve the next queue items' parts.
            val end = minOf(expandedQueue.size, effectiveStartIndex + 21)
            for (i in (effectiveStartIndex + 1) until end) {
                val t = expandedQueue[i]
                if (t.partId >= 0) continue
                try {
                    val full = client.trackMetadata(base, token, deviceId, t.key)
                    if (full != null && full.partId >= 0) playback.updateTrackPartId(i, full)
                } catch (_: Exception) { /* that item will error visibly when reached */ }
            }
        }
    }

    /**
     * 1.1.0 Moods incremental load (crash fix): the Moods tab
     * previously called ensureAllTracksLoaded(), one unpaginated
     * 45k-track JSON parse + list, which crashed on a phone.
     * This loads 500-track pages, aggregates mood/vibe/genre
     * counts per chunk, appends tracks incrementally so cards
     * render as chunks arrive, and on a chunk failure keeps
     * everything already loaded with a plain moodsError — never
     * a crash, never a blank tab. Safe to call repeatedly.
     */
    private var moodsJob: Job? = null

    fun cancelMoodsLoad() {
        moodsJob?.cancel()
        moodsJob = null
        if (_library.value.moodsLoading) {
            _library.value = _library.value.copy(moodsLoading = false)
        }
    }

    fun loadMoodsIncrementally() {
        val lib = _library.value
        if (lib.moodsLoading) return
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        val section = lib.sections.firstOrNull() ?: return
        // 1.5.4 SERVER MOOD LIST, fetched independently of
        // the track scan: two small calls, so the Moods
        // list appears from Plex's own index even before
        // (or instead of) the 45k scan finishing. Attempted
        // once per session state; an empty/failed probe
        // simply leaves server terms empty and the scan
        // terms carry the page, as before.
        if (!lib.serverTagsLoaded) {
            viewModelScope.launch {
                val lists = runCatching {
                    client.serverTagLists(base, token, deviceId, section.key)
                }.getOrNull()
                _library.value = _library.value.copy(
                    serverMoods = lists?.moods ?: emptyList(),
                    serverVibes = lists?.styles ?: emptyList(),
                    serverTagsLoaded = true,
                )
            }
        }
        if (lib.allTracksLoaded && lib.allTracks.isNotEmpty() &&
            (lib.moodsGenreCounts.isNotEmpty() || lib.moodsMoodCounts.isNotEmpty() || lib.moodsVibeCounts.isNotEmpty())
        ) {
            // Already fully loaded with counts: nothing to do, no refetch, no recompute.
            return
        }
        if (lib.allTracksLoaded && lib.allTracks.isNotEmpty()) {
            // Already fully loaded (e.g. voice/Feels earlier): aggregate
            // counts once from the existing list, no refetch. Cached
            // taxonomy mapping makes this a cache-hit pass, not a
            // fresh 193-term scan per track.
            // 1.3.2: if aggregation gives EMPTY counts, do NOT
            // return with a blank Moods screen — cached tracks
            // may lack tags or be stale. Fall through to a full
            // server rescan below, so Moods actually loads.
            val (g, m, v) = com.fourgeailabs.prismtune.data.feelsCounts(lib.allTracks)
            if (g.isNotEmpty() || m.isNotEmpty() || v.isNotEmpty()) {
                _library.value = lib.copy(
                    moodsGenreCounts = g, moodsMoodCounts = m, moodsVibeCounts = v,
                    moodsLoadedCount = lib.allTracks.size, moodsTotalCount = lib.allTracks.size,
                    moodsLoading = false,
                )
                return
            }
            // Empty counts: clear the loaded flag so the chunk
            // loop below rescans from the server, instead of
            // showing an empty Moods tab forever.
            _library.value = lib.copy(allTracksLoaded = false, allTracks = emptyList())
            settings.moodsLoadedCount = 0
            settings.moodsTotalCount = 0
            settings.moodsCountsJson = null
        }
        moodsJob = viewModelScope.launch {
            // 1.1.3/1.1.4 RESUME, never restart from 0: if a previous
            // run already loaded N tracks, keep them and continue
            // from N. 1.1.4 adds disk persistence: a crash kills
            // the process and wipes in-memory tracks, so counts +
            // offset are saved to disk after every chunk, and a
            // reopen after a crash restores them and continues
            // from the saved offset instead of track 0.
            val alreadyLoaded = _library.value.allTracks
            val acc = com.fourgeailabs.prismtune.data.MoodsChunkAccumulator()
            if (alreadyLoaded.isNotEmpty()) {
                acc.addChunk(alreadyLoaded)
                _library.value = _library.value.copy(
                    moodsLoading = true, moodsError = null,
                    moodsGenreCounts = acc.genreCounts, moodsMoodCounts = acc.moodCounts,
                    moodsVibeCounts = acc.vibeCounts, moodsLoadedCount = acc.loadedCount,
                )
            } else if (settings.moodsLoadedCount > 0) {
                // 1.2.1: validate the saved offset before trusting
                // it — a stale count past the total, or empty counts
                // with a non-zero offset, meant Moods "persisted"
                // on paper but showed nothing and re-scanned.
                val savedTotal = settings.moodsTotalCount
                val savedCount = settings.moodsLoadedCount
                val (g, m, v) = com.fourgeailabs.prismtune.data.decodeMoodsCounts(settings.moodsCountsJson)
                if ((savedTotal > 0 && savedCount > savedTotal) ||
                    (g.isEmpty() && m.isEmpty() && v.isEmpty())) {
                    settings.moodsLoadedCount = 0
                    settings.moodsTotalCount = 0
                    settings.moodsCountsJson = null
                    _library.value = _library.value.copy(
                        moodsLoading = true, moodsError = null, allTracks = emptyList(),
                        moodsGenreCounts = emptyMap(), moodsMoodCounts = emptyMap(),
                        moodsVibeCounts = emptyMap(), moodsLoadedCount = 0,
                    )
                } else {
                    acc.restore(g, m, v, savedCount)
                    _library.value = _library.value.copy(
                        moodsLoading = true, moodsError = null, allTracks = emptyList(),
                        moodsGenreCounts = g, moodsMoodCounts = m, moodsVibeCounts = v,
                        moodsLoadedCount = savedCount,
                        moodsTotalCount = savedTotal.takeIf { it >= 0 },
                    )
                }
            } else {
                _library.value = _library.value.copy(
                    moodsLoading = true, moodsError = null, allTracks = emptyList(),
                    moodsGenreCounts = emptyMap(), moodsMoodCounts = emptyMap(),
                    moodsVibeCounts = emptyMap(), moodsLoadedCount = 0,
                )
            }
            var start = _library.value.moodsLoadedCount
            var total: Int? = _library.value.moodsTotalCount
            var failed = false
            while (true) {
                try {
                    val (page, pageTotal) = client.trackPage(base, token, deviceId, section.key, start, 500)
                    if (pageTotal != null) total = pageTotal
                    if (page.isEmpty()) break
                    acc.addChunk(page)
                    val current = _library.value
                    _library.value = current.copy(
                        allTracks = current.allTracks + page,
                        moodsGenreCounts = acc.genreCounts, moodsMoodCounts = acc.moodCounts,
                        moodsVibeCounts = acc.vibeCounts, moodsLoadedCount = acc.loadedCount,
                        moodsTotalCount = total,
                    )
                    // 1.1.4: persist progress to disk after every
                    // chunk, so a crash does not lose it.
                    settings.moodsLoadedCount = acc.loadedCount
                    if (total != null) settings.moodsTotalCount = total
                    settings.moodsCountsJson = com.fourgeailabs.prismtune.data.encodeMoodsCounts(
                        acc.genreCounts, acc.moodCounts, acc.vibeCounts,
                    )
                    // Yield so the UI thread can render progress
                    // between chunks instead of choking at 10k+.
                    delay(16)
                    start += page.size
                    if (page.size < 500) break
                    if (total != null && start >= total) break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    acc.addFailure(e.message?.take(200) ?: "Chunk failed")
                    _library.value = _library.value.copy(moodsError = "Some moods could not be loaded: ${acc.chunkError}")
                    failed = true
                    break
                }
            }
            // Voice snapshot once, at completion, never per chunk.
            if (!failed) {
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceTracks = _library.value.allTracks
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.voiceLibraryLoaded = true
            }
            _library.value = _library.value.copy(
                moodsLoading = false,
                allTracksLoaded = !failed && (total == null || _library.value.allTracks.size >= total),
                loadingAllTracks = false,
            )
            // 1.2.3: persist the completed Moods scan to the
            // metadata cache, so a reopen shows Moods instantly
            // without re-scanning 45k tracks.
            if (!failed) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val lib = _library.value
                    runCatching {
                        metadataCache.save(
                            com.fourgeailabs.prismtune.data.MetadataSnapshot(
                                allTracks = lib.allTracks,
                                artists = lib.artists,
                                playlists = lib.playlists,
                                recent = lib.recent,
                                recentlyPlayed = _recentlyPlayed.value,
                                recentAlbums = lib.recentAlbums,
                                sections = lib.sections,
                                moodsGenreCounts = lib.moodsGenreCounts,
                                moodsMoodCounts = lib.moodsMoodCounts,
                                moodsVibeCounts = lib.moodsVibeCounts,
                                moodsLoadedCount = lib.moodsLoadedCount,
                                moodsTotalCount = lib.moodsTotalCount ?: -1,
                                moodsMappingVersion = com.fourgeailabs.prismtune.data.MOODS_MAPPING_VERSION,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 1.1.4: tapping a mood/vibe/genre card PLAYS it immediately,
     * it does not just tick a checkbox and show a list. Fast
     * path: if the full library is already in memory, filter it
     * on a background thread and play. Slow path: scan the
     * section chunk by chunk for that one feel, collecting up
     * to 2000 matches, then play them in library order. No fake
     * tracks, empty = no playback and a plain error, never a
     * random track.
     */
    fun playFeel(kind: String, name: String, onStarted: () -> Unit) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        val section = lib.sections.firstOrNull() ?: return
        lastFeelKind = kind.replaceFirstChar { it.uppercase() }
        viewModelScope.launch {
            _library.value = _library.value.copy(moodsPlayingFeel = name)
            try {
                // 1.3.8: PLEX NATIVE first, local taxonomy second.
                // Ask Plex itself for tracks tagged with this
                // mood/style/genre, using tracksForMood() above.
                // That returns the server's own mood selection,
                // across the whole library, not just the first
                // 100 songs in server order. Only if Plex returns
                // 0 (taxonomy term not a real Plex tag) do we fall
                // back to the local filter below.
                val serverMatches = runCatching {
                    var found: List<com.fourgeailabs.prismtune.data.PlexTrack> = emptyList()
                    for (k in listOf(kind, "mood", "vibe", "genre").distinct()) {
                        found = client.tracksForMood(base, token, deviceId, section.key, k, name)
                        if (found.isNotEmpty()) break
                    }
                    found
                }.getOrNull() ?: emptyList()
                if (serverMatches.isNotEmpty()) {
                    _library.value = _library.value.copy(moodsPlayingFeel = null)
                    playTracks(serverMatches, 0)
                    onStarted()
                    return@launch
                }
                // 1.3.6: try the requested kind FIRST, then the
                // other two, before giving up. Home mood buttons
                // guess kind from the taxonomy (moodKindFor), so
                // a genre name that is also a mood term, or a
                // mood name from a genre, was misclassified and
                // returned empty — or, worse, played a wrong
                // first-artist fallback elsewhere. A name that
                // matches as a genre IS a valid play, even if
                // it was tapped as a "mood" on Home.
                fun hitFor(track: com.fourgeailabs.prismtune.data.PlexTrack, k: String): Boolean {
                    val feels = com.fourgeailabs.prismtune.data.mapTrackFeelsCached(track)
                    return when (k) {
                        "mood" -> name in feels.moods
                        "vibe" -> name in feels.vibes
                        else -> name in feels.genres
                    }
                }
                val kindsToTry = listOf(kind, "mood", "vibe", "genre").distinct()
                val matches: List<com.fourgeailabs.prismtune.data.PlexTrack> =
                    if (lib.allTracksLoaded && lib.allTracks.isNotEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            var found: List<com.fourgeailabs.prismtune.data.PlexTrack> = emptyList()
                            for (k in kindsToTry) {
                                found = lib.allTracks.filter { hitFor(it, k) }
                                if (found.isNotEmpty()) break
                            }
                            found
                        }
                    } else {
                        val out = mutableListOf<com.fourgeailabs.prismtune.data.PlexTrack>()
                        var start = 0
                        var foundKind: String? = null
                        while (out.size < 2000) {
                            val (page, pageTotal) = client.trackPage(base, token, deviceId, section.key, start, 500)
                            if (page.isEmpty()) break
                            // On the first page, work out which kind
                            // actually matches this name, then stick
                            // to it for the rest of the scan.
                            if (foundKind == null) {
                                foundKind = kindsToTry.firstOrNull { k -> page.any { hitFor(it, k) } }
                                if (foundKind == null) {
                                    start += page.size
                                    if (page.size < 500) break
                                    if (pageTotal != null && start >= pageTotal) break
                                    continue
                                }
                            }
                            for (track in page) {
                                if (hitFor(track, foundKind!!)) out.add(track)
                                if (out.size >= 2000) break
                            }
                            start += page.size
                            if (page.size < 500) break
                            if (pageTotal != null && start >= pageTotal) break
                        }
                        out
                    }
                _library.value = _library.value.copy(moodsPlayingFeel = null)
                if (matches.isEmpty()) {
                    _library.value = _library.value.copy(
                        moodsError = "No tracks found for $name on your Plex server, either as a Plex mood/style/genre tag or in your loaded library.",
                    )
                    return@launch
                }
                playTracks(matches, 0)
                onStarted()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _library.value = _library.value.copy(
                    moodsPlayingFeel = null,
                    moodsError = "Could not play $name: ${e.message?.take(200)}",
                )
            }
        }
    }

    /** 1.1.0 server-side search: query only, no full-library load. */
    fun serverSearch(query: String) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        if (query.isBlank()) {
            _library.value = lib.copy(serverSearchResults = null, searching = false, searchError = null)
            return
        }
        viewModelScope.launch {
            _library.value = _library.value.copy(searching = true, searchError = null)
            try {
                val results = client.searchServer(base, token, deviceId, query)
                _library.value = _library.value.copy(
                    serverSearchResults = results, searching = false,
                    searchAlbums = emptyList(), searchTracks = emptyList(),
                )
            } catch (e: Exception) {
                recordDiagnostic("server search", "/library/search", e)
                if (!handleLibraryFailure(e, "search")) {
                    _library.value = _library.value.copy(searching = false, searchError = "Search failed: ${e.message}")
                }
            }
        }
    }

    /** 1.1.0: tapping a search artist loads ONLY that artist's albums (paginated). */
    fun searchArtistAlbums(artist: PlexArtist) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        viewModelScope.launch {
            try {
                val albums = client.albumsForArtist(base, token, deviceId, artist.key)
                _library.value = _library.value.copy(searchAlbums = albums, searchTracks = emptyList())
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "artist albums")) {
                    _library.value = _library.value.copy(searchError = "Could not load albums: ${e.message}")
                }
            }
        }
    }

    /** 1.1.0: tapping a search album loads ONLY its tracks (paginated). */
    fun searchAlbumTracks(album: PlexAlbum) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        viewModelScope.launch {
            try {
                val tracks = client.tracksForAlbum(base, token, deviceId, album.key)
                _library.value = _library.value.copy(searchTracks = tracks)
            } catch (e: Exception) {
                if (!handleLibraryFailure(e, "album tracks")) {
                    _library.value = _library.value.copy(searchError = "Could not load tracks: ${e.message}")
                }
            }
        }
    }

    fun setSafeAreaFraction(fraction: Float) {
        settings.safeAreaFraction = fraction
        _safeAreaFraction.value = settings.safeAreaFraction
    }

    fun setAudioQuality(quality: String) {
        settings.audioQuality = quality
        _audioQuality.value = quality
    }

    // 1.1.0: persist only what the player actually did — if the
    // player call failed (e.g. released player), the stored setting
    // stays in sync with the player's real shuffle state.
    fun setShuffle(enabled: Boolean) {
        val applied = playback.setShuffle(enabled)
        settings.shuffle = if (applied) enabled else playback.shuffle.value
        if (!applied) recordDiagnostic("shuffle", "/player/shuffle", Exception("Shuffle could not be changed"))
    }

    fun cycleRepeat(): Int {
        val mode = playback.cycleRepeat()
        settings.repeatMode = mode
        return mode
    }

    /** 1.2.3: Next, with a fallback when the current queue has
     *  no next track. First try the player's Next (which now
     *  also rebuilds from the queue flow). If that returns false,
     *  the queue is truly at its end, so continue from the
     *  queue's source: re-fetch the playlist/album/artist, or
     *  the mood matches, or the library, and play the track
     *  after the current one. Never a silent no-op when there
     *  is music available to continue with. */
    fun nextTrack() {
        if (playback.next()) return
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        val current = playback.currentTrack ?: return
        viewModelScope.launch {
            try {
                val source = lastQueueSource
                val candidates: List<com.fourgeailabs.prismtune.data.PlexTrack> = when {
                    source?.kind == "Playlist" && source.key != null -> {
                        val pl = lib.playlists.firstOrNull { it.key == source.key }
                            ?: com.fourgeailabs.prismtune.data.PlexPlaylist(key = source.key, title = source.name)
                        runCatching { client.tracksForPlaylistFor(base, token, deviceId, pl) }.getOrNull()
                    }
                    source?.kind == "Album" && source.key != null ->
                        runCatching { client.tracksForAlbum(base, token, deviceId, source.key) }.getOrNull()
                    source?.kind == "Artist" ->
                        if (lib.detailTracks.isNotEmpty()) lib.detailTracks
                        else lib.allTracks.filter { it.artistTitle == source.name }
                    source != null && (source.kind == "Mood" || source.kind == "Vibe" || source.kind == "Genre") -> {
                        val kind = when (source.kind) { "Mood" -> "mood"; "Vibe" -> "vibe"; else -> "genre" }
                        if (lib.allTracksLoaded && lib.allTracks.isNotEmpty()) {
                            withContext(kotlinx.coroutines.Dispatchers.Default) {
                                lib.allTracks.filter { t ->
                                    val f = com.fourgeailabs.prismtune.data.mapTrackFeelsCached(t)
                                    when (kind) { "mood" -> source.name in f.moods; "vibe" -> source.name in f.vibes; else -> source.name in f.genres }
                                }
                            }
                        } else null
                    }
                    else -> null
                } ?: lib.allTracks
                val effective = if (candidates.isEmpty()) lib.allTracks else candidates
                if (effective.isEmpty()) return@launch
                val idx = effective.indexOfFirst { it.key == current.key }
                val nextIdx = if (idx >= 0) idx + 1 else 0
                if (nextIdx in effective.indices) {
                    playTracks(effective, nextIdx)
                }
            } catch (_: Exception) { /* Next stays a no-op only if no source can be loaded */ }
        }
    }

    fun artworkUrl(thumb: String?): String? {
        val lib = _library.value
        val base = lib.serverBase ?: return null
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return null
        return PlexUrls.thumbUrl(base, thumb, token)
    }


    // 1.1.0 resume: restore the saved queue at its saved position
    // and start playing it. Degrades gracefully: no saved queue,
    // no server, or an empty restored queue means "nothing to
    // resume" — the mini player's last-played track still shows,
    // and nothing crashes.
    /** 1.5.7 COLD-BOOT PLAY FIX, part 2 — the single play
     *  entry point for the mini-player play button and the
     *  Now Playing play button. The old path called
     *  playback.togglePlayPause() directly, which is a
     *  silent no-op when the player has no items yet (the
     *  cold-boot metadata-only restore) and the library has
     *  not loaded yet (no serverBase). Now: already playing
     *  pauses; player items present toggles; a restored
     *  queue with a known server resumes through
     *  resumeSavedQueue (which builds player items and
     *  plays); a restored queue with the library still
     *  loading records a 30 s pending play and kicks off
     *  the library load, so the tap is honoured when the
     *  load lands instead of dying silently. No restored
     *  queue at all falls through to the plain toggle,
     *  which is honestly a no-op with nothing to play. */
    fun playFromMiniPlayer() {
        if (playback.isPlaying.value) {
            playback.togglePlayPause()
            return
        }
        if (playback.hasPlayerItems()) {
            playback.togglePlayPause()
            return
        }
        if (_restoredQueue.value != null) {
            if (_library.value.serverBase != null) {
                resumeSavedQueue()
            } else {
                pendingPlayAtMs = System.currentTimeMillis()
                loadServers()
            }
            return
        }
        playback.togglePlayPause()
    }

    fun resumeSavedQueue() {
        val saved = _restoredQueue.value ?: return
        // 1.4.0: a metadata-only restored queue (safe launch)
        // is NOT a live player queue — its player has no items
        // yet. Only a queue with real player items blocks a
        // resume; otherwise tapping the mini player must play
        // the restored queue, not silently do nothing.
        if (playback.hasPlayerItems()) return
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        if (token.isBlank()) return
        val restored = saved.tracks.map { t ->
            val f = offlineStore.fileFor(t.key)
            if (f.exists() && f.length() > 0) t.copy(localPath = f.absolutePath) else t
        }
        val restoredIndex = saved.index.coerceIn(0, restored.size - 1)
        // 1.5.1 UP NEXT FIX — 2026-10-27 02:18:
        // "The up next side panel also no longer has the
        // 100 queue." Cause: this resume path restored the
        // SAVED window only, without expanding it to a
        // full next-100, unlike tryAutoRestore() and
        // playTracks() which both expand. So after a cold
        // open + tap-to-play, if the current track was near
        // the end of the saved window, Up Next showed few
        // or zero tracks. Now it expands from the loaded
        // library first, so Up Next is a full 100 whenever
        // the library has 100 tracks after the current one.
        val tracks = expandQueueToNext100(restored, restoredIndex, _library.value.allTracks)
        val index = restoredIndex.coerceIn(0, tracks.size - 1)
        lastQueueSource = saved.sourceKind?.let { QueueSource(it, saved.sourceName ?: it, saved.sourceKey) }
        _queueSource.value = lastQueueSource
        playback.restoreQueue(tracks, index, saved.positionMs, base, token, _audioQuality.value)
        playback.togglePlayPause()
    }

    fun setAutoOffline(enabled: Boolean) {
        settings.autoOffline = enabled
        _autoOffline.value = enabled
        if (enabled) refreshOfflineTopPicks()
    }

    fun clearOfflineFiles() {
        // 1.5.8: pass the playing track's STEM, not its key
        // — the store compares stems, so a hashed key
        // passed as a key would slip past the protection
        // and delete the song currently playing.
        val playingStem = playback.currentTrack?.let { offlineStore.stemFor(it.key) }
        offlineStore.clearAll(playingStem)
        _offline.value = _offline.value.copy(
            downloadedCount = 0, storageBytes = offlineStore.storageBytes(),
        )
    }

    /**
     * 1.1.0 Offline Top Picks refresh + download. Runs in the
     * background, never blocks the rest of 1.1.0: if downloads
     * fail, the playlist still exists as streaming tracks and the
     * failure is reported plainly in Settings. No Wi-Fi gate —
     * downloads may run on mobile data too (the user's correction).
     * Pruning never deletes the currently playing track.
     */
    fun refreshOfflineTopPicks() {
        if (offlineRefreshing) return
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = libraryToken()
        val section = lib.sections.firstOrNull() ?: return
        if (token.isBlank()) return
        offlineRefreshing = true
        _offline.value = _offline.value.copy(downloading = true, error = null)
        viewModelScope.launch {
            try {
                val all = client.allTracks(base, token, deviceId, section.key)
                val mostPlayed = all.filter { it.viewCount > 0 }
                val highlyRated = all.filter { (it.userRating ?: 0f) >= 8f }
                val history = settings.playHistory
                val localMost = _recentlyPlayed.value
                    .sortedByDescending { history[it.key] ?: 0 }
                    .filter { (history[it.key] ?: 0) > 0 }
                val albumTracks = ArrayList<PlexTrack>()
                for (albumKey in topAlbumKeys(all, 10)) {
                    runCatching { client.tracksForAlbum(base, token, deviceId, albumKey) }
                        .onSuccess { albumTracks.addAll(it) }
                }
                val playlistTracks = ArrayList<PlexTrack>()
                // 1.2.2: cap playlist expansion — a playlist with
                // thousands of tracks (All Music, 46k) must never
                // be fully expanded just to pick 100 offline tracks.
                for (pl in lib.playlists.sortedByDescending { it.itemCount }.take(3)) {
                    if (pl.itemCount > 2000) continue
                    runCatching { client.tracksForPlaylistFor(base, token, deviceId, pl) }
                        .onSuccess { playlistTracks.addAll(it) }
                }
                val result = buildOfflineTopPicks(
                    mostPlayed, highlyRated, localMost,
                    _recentlyPlayed.value, albumTracks, playlistTracks, limit = 100,
                )
                settings.savedOfflineTracksJson = encodeOfflineTracks(result.tracks)
                _offline.value = _offline.value.copy(
                    tracks = result.tracks, emptyReason = result.reason,
                    storageBytes = offlineStore.storageBytes(),
                )
                com.fourgeailabs.prismtune.playback.PrismTuneAppState.offlineTracks = result.tracks
                // Download missing tracks, one at a time, with progress.
                var failures = 0
                val total = result.tracks.size
                for ((i, track) in result.tracks.withIndex()) {
                    if (offlineStore.isDownloaded(track.key)) continue
                    _offline.value = _offline.value.copy(downloadIndex = i + 1, downloadTotal = total)
                    val ok = downloadTrack(base, token, track)
                    if (!ok) failures++
                }
                // 1.2.3: prune keeps Offline Top Picks, the current
                // track, AND the next 10 buffered tracks, so a Top
                // Picks refresh does not delete the buffer.
                val keepKeys = result.tracks.map { it.key }.toSet() +
                    playback.queue.value.drop(playback.currentIndex.value + 1).take(10).map { it.key }.toSet()
                offlineStore.prune(keepKeys, playback.currentTrack?.key)
                _offline.value = _offline.value.copy(
                    downloading = false,
                    downloadedCount = result.tracks.count { offlineStore.isDownloaded(it.key) },
                    storageBytes = offlineStore.storageBytes(),
                    error = if (failures > 0) "$failures of ${result.tracks.size} tracks could not be downloaded; they still stream when online." else null,
                )
            } catch (e: Exception) {
                recordDiagnostic("offline refresh", "/library/sections", e)
                _offline.value = _offline.value.copy(
                    downloading = false,
                    error = "Offline Top Picks could not be refreshed: ${e.message?.take(200)}",
                )
            } finally {
                offlineRefreshing = false
            }
        }
    }

    /** Download one track's direct-play file; false on any failure. */
    private suspend fun downloadTrack(serverBase: String, token: String, track: PlexTrack): Boolean {
        return try {
            var partId = track.partId
            if (partId < 0) {
                partId = client.trackMetadata(serverBase, token, deviceId, track.key)?.partId ?: -1
            }
            if (partId < 0) return false
            val url = PlexUrls.streamUrl(serverBase, partId, token)
            val request = okhttp3.Request.Builder().url(url).build()
            val http = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val tmp = java.io.File(offlineStore.fileFor(track.key).absolutePath + ".tmp")
                body.byteStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.length() > 0) tmp.renameTo(offlineStore.fileFor(track.key)) else tmp.delete()
                tmp.exists().not() && offlineStore.isDownloaded(track.key)
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onCleared() {
        // The player intentionally outlives the ViewModel (MediaSession owns it
        // for the car's lifetime); do not release it here.
        super.onCleared()
    }
}
