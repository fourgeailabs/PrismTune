package com.fourgeailabs.prismtune.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fourgeailabs.prismtune.data.ConnectionPicker
import com.fourgeailabs.prismtune.data.PlexAlbum
import com.fourgeailabs.prismtune.data.PlexArtist
import com.fourgeailabs.prismtune.data.PlexClient
import com.fourgeailabs.prismtune.data.PlexPlaylist
import com.fourgeailabs.prismtune.data.PlexSection
import com.fourgeailabs.prismtune.data.PlexServer
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.PlexUrls
import com.fourgeailabs.prismtune.data.SettingsStore
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

sealed interface SignInState {
    data object Idle : SignInState
    data object Creating : SignInState
    data class Waiting(val code: String, val qrBitmap: Bitmap?, val linkUrl: String) : SignInState
    data class Error(val message: String) : SignInState
    data object Done : SignInState
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
    val searchResults: List<PlexTrack> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class PrismViewModel(application: Application) : AndroidViewModel(application) {
    private val client = PlexClient()
    val tokenStore = TokenStore(application)
    val settings = SettingsStore(application)
    val playback: PlaybackManager =
        PrismTuneAppState.playbackManager ?: PlaybackManager(application).also {
            PrismTuneAppState.playbackManager = it
        }

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _safeAreaFraction = MutableStateFlow(settings.safeAreaFraction)
    val safeAreaFraction: StateFlow<Float> = _safeAreaFraction.asStateFlow()

    private val _audioQuality = MutableStateFlow(settings.audioQuality)
    val audioQuality: StateFlow<String> = _audioQuality.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<PlexTrack>>(emptyList())
    val recentlyPlayed: StateFlow<List<PlexTrack>> = _recentlyPlayed.asStateFlow()

    private var pollJob: Job? = null

    val isSignedIn: Boolean get() = tokenStore.getToken() != null
    val deviceId: String get() = settings.deviceId

    fun startSignIn() {
        pollJob?.cancel()
        _signIn.value = SignInState.Creating
        viewModelScope.launch {
            try {
                val pin = client.createPin(deviceId)
                if (pin.code.isBlank()) throw IllegalStateException("Plex returned an empty link code.")
                val linkUrl = PlexUrls.linkUrlForCode(pin.code)
                _signIn.value = SignInState.Waiting(
                    code = pin.code,
                    qrBitmap = QrCode.render(linkUrl, 640),
                    linkUrl = linkUrl,
                )
                pollJob = launch {
                    val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(2000)
                        val result = runCatching { client.pollPin(pin.id, deviceId) }.getOrNull()
                        val token = result?.authToken
                        if (!token.isNullOrBlank()) {
                            tokenStore.saveToken(token)
                            _signIn.value = SignInState.Done
                            loadServers()
                            return@launch
                        }
                    }
                    _signIn.value = SignInState.Error("Link code expired. Tap Retry for a fresh code.")
                }
            } catch (e: Exception) {
                _signIn.value = SignInState.Error("Could not reach Plex: ${e.message}")
            }
        }
    }

    fun cancelSignIn() {
        pollJob?.cancel()
        _signIn.value = SignInState.Idle
    }

    fun signOut() {
        pollJob?.cancel()
        tokenStore.clear()
        settings.serverId = null
        _library.value = LibraryState()
        _signIn.value = SignInState.Idle
    }

    fun loadServers() {
        val token = tokenStore.getToken() ?: return
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null)
            try {
                val servers = client.resources(token, deviceId)
                val saved = settings.serverId
                val selected = servers.firstOrNull { it.id == saved } ?: servers.firstOrNull()
                val base = selected?.let { ConnectionPicker.pick(it.connections)?.uri }
                _library.value = _library.value.copy(
                    servers = servers,
                    selectedServer = selected,
                    serverBase = base,
                    loading = false,
                )
                if (selected != null && base != null) {
                    settings.serverId = selected.id
                    loadHome(base, selected.effectiveToken(token))
                }
            } catch (e: Exception) {
                _library.value = _library.value.copy(loading = false, error = "Could not list servers: ${e.message}")
            }
        }
    }

    fun selectServer(server: PlexServer) {
        val token = tokenStore.getToken() ?: return
        val base = ConnectionPicker.pick(server.connections)?.uri ?: return
        settings.serverId = server.id
        _library.value = _library.value.copy(selectedServer = server, serverBase = base)
        loadHome(base, server.effectiveToken(token))
    }

    private fun loadHome(serverBase: String, token: String) {
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null)
            try {
                val sections = client.musicSections(serverBase, token, deviceId)
                val first = sections.firstOrNull()
                val artists = first?.let { client.artists(serverBase, token, deviceId, it.key) } ?: emptyList()
                val recent = first?.let { client.recentlyAdded(serverBase, token, deviceId, it.key) } ?: emptyList()
                val playlists = runCatching { client.playlists(serverBase, token, deviceId) }.getOrDefault(emptyList())
                _library.value = _library.value.copy(
                    sections = sections,
                    artists = artists,
                    recent = recent,
                    playlists = playlists,
                    loading = false,
                )
            } catch (e: Exception) {
                _library.value = _library.value.copy(loading = false, error = "Could not load library: ${e.message}")
            }
        }
    }

    fun openArtist(artist: PlexArtist) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        viewModelScope.launch {
            _library.value = lib.copy(loading = true, error = null)
            try {
                val albums = client.albumsForArtist(base, token, deviceId, artist.key)
                _library.value = _library.value.copy(albums = albums, tracks = emptyList(), loading = false)
            } catch (e: Exception) {
                _library.value = _library.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun openAlbum(album: PlexAlbum) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        viewModelScope.launch {
            _library.value = lib.copy(loading = true, error = null)
            try {
                val tracks = client.tracksForAlbum(base, token, deviceId, album.key)
                _library.value = _library.value.copy(tracks = tracks, loading = false)
            } catch (e: Exception) {
                _library.value = _library.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun openPlaylist(playlist: PlexPlaylist) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        viewModelScope.launch {
            _library.value = lib.copy(loading = true, error = null)
            try {
                val tracks = client.tracksForPlaylist(base, token, deviceId, playlist.key)
                _library.value = _library.value.copy(tracks = tracks, loading = false)
            } catch (e: Exception) {
                _library.value = _library.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun search(query: String) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        if (query.isBlank()) {
            _library.value = lib.copy(searchResults = emptyList())
            return
        }
        viewModelScope.launch {
            try {
                val results = client.search(base, token, deviceId, query)
                _library.value = _library.value.copy(searchResults = results)
            } catch (e: Exception) {
                _library.value = _library.value.copy(error = "Search failed: ${e.message}")
            }
        }
    }

    fun playTracks(tracks: List<PlexTrack>, startIndex: Int) {
        val lib = _library.value
        val base = lib.serverBase ?: return
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return
        if (tracks.isEmpty()) return
        playback.setQueue(tracks, startIndex, base, token, _audioQuality.value)
        val track = tracks.getOrNull(startIndex)
        if (track != null) {
            _recentlyPlayed.value = (listOf(track) + _recentlyPlayed.value)
                .distinctBy { it.key }.take(20)
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

    fun setShuffle(enabled: Boolean) {
        settings.shuffle = enabled
        playback.setShuffle(enabled)
    }

    fun cycleRepeat(): Int {
        val mode = playback.cycleRepeat()
        settings.repeatMode = mode
        return mode
    }

    fun artworkUrl(thumb: String?): String? {
        val lib = _library.value
        val base = lib.serverBase ?: return null
        val token = lib.selectedServer?.effectiveToken(tokenStore.getToken().orEmpty()) ?: return null
        return PlexUrls.thumbUrl(base, thumb, token)
    }

    override fun onCleared() {
        // The player intentionally outlives the ViewModel (MediaSession owns it
        // for the car's lifetime); do not release it here.
        super.onCleared()
    }
}
