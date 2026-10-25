package com.fourgeailabs.prismtune.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.PlexUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio-only playback, backed by Media3 ExoPlayer.
 *
 * The player is owned here and handed to PrismTuneMediaService's MediaSession,
 * so steering-wheel / car-system play-pause / next / previous controls drive
 * the same queue the on-screen Now Playing screen shows. No video surface is
 * ever created: this is a music-only app, by design.
 */
class PlaybackManager(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var positionJob: Job? = null

    private val _queue = MutableStateFlow<List<PlexTrack>>(emptyList())
    val queue: StateFlow<List<PlexTrack>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** 0 = off, 1 = all, 2 = one. */
    private val _repeatMode = MutableStateFlow(0)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    val currentTrack: PlexTrack?
        get() = _queue.value.getOrNull(_currentIndex.value)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentIndex.value = player.currentMediaItemIndex.takeIf { it >= 0 } ?: _currentIndex.value
                _durationMs.value = player.duration.coerceAtLeast(0)
            }
        })
        positionJob = scope.launch {
            while (isActive) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0)
                _durationMs.value = player.duration.coerceAtLeast(0)
                delay(500)
            }
        }
    }

    fun setQueue(tracks: List<PlexTrack>, startIndex: Int, serverBase: String, token: String, quality: String) {
        _queue.value = tracks
        val items = tracks.map { track -> track.toMediaItem(serverBase, token, quality) }
        player.setMediaItems(items, startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), 0)
        _currentIndex.value = startIndex
        player.prepare()
        player.play()
    }

    fun playIndex(index: Int) {
        if (index in _queue.value.indices) {
            player.seekTo(index, 0)
            player.play()
            _currentIndex.value = index
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
        else player.seekTo(0)
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0, player.duration.coerceAtLeast(0)))
    }

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        player.shuffleModeEnabled = enabled
    }

    fun cycleRepeat(): Int {
        val next = (_repeatMode.value + 1) % 3
        _repeatMode.value = next
        player.repeatMode = when (next) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        return next
    }

    fun release() {
        positionJob?.cancel()
        player.release()
    }

    private fun PlexTrack.toMediaItem(serverBase: String, token: String, quality: String): MediaItem {
        val url = if (quality == "Original" || partId < 0) {
            PlexUrls.streamUrl(serverBase, partId, token)
        } else {
            val kbps = quality.toIntOrNull() ?: 320
            PlexUrls.transcodeUrl(serverBase, partId, token, kbps)
        }
        val artwork = PlexUrls.thumbUrl(serverBase, thumb, token)
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artistTitle)
                    .setAlbumTitle(albumTitle)
                    .setArtworkUri(artwork?.let { android.net.Uri.parse(it) })
                    .build(),
            )
            .build()
    }
}
