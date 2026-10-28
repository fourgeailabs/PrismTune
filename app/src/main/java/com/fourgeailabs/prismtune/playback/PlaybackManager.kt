package com.fourgeailabs.prismtune.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
/**
 * 1.1.0 shuffle safety, pure and unit-testable. The shuffle button
 * previously called player.shuffleModeEnabled directly, with no
 * guard for a released player and no consistency between the UI
 * state and what the player actually did — a failure left the UI
 * showing shuffle on while the player had it off (or crashed).
 * Rules: a released player never gets a player call and the UI
 * state does not change; an empty queue stores the preference
 * and applies it when a queue is set; a failed player call keeps
 * the previous UI state and surfaces a plain error instead.
 */
fun shouldCallPlayerForShuffle(queueEmpty: Boolean, released: Boolean): Boolean =
    !released && !queueEmpty

fun shuffleStateAfterAttempt(current: Boolean, requested: Boolean, playerSucceeded: Boolean): Boolean =
    if (playerSucceeded) requested else current

/**
 * 1.3.9: Up Next order, pure and unit-testable. QueueScreen and the
 * right panel used to show queue.drop(current + 1) — the natural
 * order — even when shuffle was on, so the song shown as "next"
 * was NOT the song ExoPlayer would actually play next. With
 * shuffle off this is just the natural order. With shuffle on,
 * nextFn is the player's timeline walk (getNextWindowIndex),
 * which returns the next item in the player's shuffled sequence.
 * Visited indices stop the walk, so repeat-all does not loop
 * the list forever in the display.
 */
fun upNextOrderFrom(current: Int, queueSize: Int, shuffle: Boolean, nextFn: (Int) -> Int, limit: Int = 100): List<Int> {
    if (queueSize <= 0 || current < 0 || current >= queueSize) return emptyList()
    if (!shuffle) return (current + 1..minOf(current + limit, queueSize - 1)).toList()
    val visited = mutableSetOf(current)
    val out = mutableListOf<Int>()
    var idx = current
    repeat(limit) {
        val n = try { nextFn(idx) } catch (_: Exception) { C.INDEX_UNSET }
        if (n < 0 || n >= queueSize || n in visited) return out
        visited.add(n)
        out.add(n)
        idx = n
    }
    return out
}


/**
 * 1.1.4: car system mute -> actually stop playing, not just
 * silence it. The car mute button sets STREAM_MUSIC volume to
 * 0 while ExoPlayer keeps advancing the track, so unmuting
 * lands mid-song or on a later track. Pause only on a
 * transition to 0 from a non-zero volume while playing.
 * 1.2.0: mute is now a toggle — unmuting resumes ONLY if the
 * mute itself paused playback, and a manual pause while muted
 * cancels that resume. Pure, unit-testable.
 */
fun shouldPauseOnMute(isPlaying: Boolean, volume: Int, previousVolume: Int): Boolean =
    isPlaying && volume == 0 && previousVolume > 0

fun shouldResumeOnUnmute(mutePaused: Boolean, volume: Int, previousVolume: Int): Boolean =
    mutePaused && volume > 0 && previousVolume == 0

/** 1.2.0 auto-skip: skip to the next track on an unplayable
 *  song, up to 5 consecutive failures, then stop with an
 *  error instead of looping forever. Pure, unit-testable. */
fun shouldAutoSkip(consecutiveFailures: Int, hasNext: Boolean): Boolean =
    hasNext && consecutiveFailures < 5

class PlaybackManager(context: Context) {
    // 1.1.0 live waveform: an AudioProcessor tapped into the audio
    // sink observes the PCM actually being played (passthrough, no
    // audio change). Injecting it via the renderers factory also
    // keeps the player off the offload path, so frames reach it.
    private val waveformProcessor = LiveWaveformProcessor()
    private val renderersFactory =
        object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(waveformProcessor))
                    .build()
        }
    val player: ExoPlayer =
        ExoPlayer.Builder(context.applicationContext, renderersFactory).build().also {
            // 1.1.4: handle audio focus so a car system mute /
            // focus loss actually pauses, instead of playing on
            // silently. Volume-to-0 is watched below as well,
            // because car mute often sets volume to 0, not focus.
            it.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
        }

    private val appContext = context.applicationContext
    private val audioManager = appContext
        .getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    private var mutePaused = false
    private var queueDeviceId: String = ""

    fun setDeviceId(id: String) { queueDeviceId = id }

    /** 1.2.2: start the media service in the background at
     *  app launch, so the car has an active media session
     *  even before any track plays, and is less likely to quit
     *  the app after ~30 seconds. This is startService, not
     *  startForegroundService, so it does not require a
     *  foreground notification within 5 seconds. When playback
     *  starts, startMediaService() below promotes it. */
    fun startMediaServiceBackground() {
        try {
            val intent = android.content.Intent(appContext, PrismTuneMediaService::class.java)
            appContext.startService(intent)
        } catch (_: Exception) { /* session creation is best-effort */ }
    }

    /** 1.3.4: NEVER use startForegroundService again.
     *  PROVEN CRASH, from the user's 1.3.3 crash log, 2026-10-26
     *  23:06:52, Pixel 10 Pro XL, Android 17, main thread:
     *  ForegroundServiceDidNotStartInTimeException —
     *  Context.startForegroundService() did not then call
     *  Service.startForeground() for PrismTuneMediaService,
     *  called from startMediaService() (PlaybackManager.kt:135)
     *  from setQueue() from playTracks(). Media3's
     *  MediaSessionService only calls startForeground itself
     *  once the player is actually playing, and on this
     *  device that was not within Android's 5-second window
     *  after startForegroundService, so Android killed the
     *  app with a RemoteServiceException. The fix is to use
     *  plain startService here, exactly like
     *  startMediaServiceBackground() already does: there is
     *  NO 5-second foreground requirement for startService,
     *  and Media3 still promotes the service to foreground
     *  itself, with its own notification, once playback
     *  actually starts. Playback still survives leaving the
     *  app, because the service is foreground while playing —
     *  it just gets there via Media3, not via a timed call
     *  from the ViewModel. */
    fun startMediaService() {
        try {
            val intent = android.content.Intent(appContext, PrismTuneMediaService::class.java)
            appContext.startService(intent)
        } catch (_: Exception) { /* service start is best-effort; playback still works in-app */ }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var positionJob: Job? = null
    private var waveformJob: Job? = null
    private var muteWatchJob: Job? = null

    init {
        // 1.1.4/1.2.0: poll STREAM_MUSIC volume; mute pauses,
        // unmute resumes only if mute paused it.
        muteWatchJob = scope.launch {
            var previous = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
            while (isActive) {
                delay(500)
                val current = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
                if (current >= 0 && previous >= 0) {
                    if (shouldPauseOnMute(_isPlaying.value, current, previous)) {
                        try { player.pause() } catch (_: Exception) { }
                        mutePaused = true
                    } else if (shouldResumeOnUnmute(mutePaused, current, previous)) {
                        try { player.play() } catch (_: Exception) { }
                        mutePaused = false
                    }
                }
                if (current >= 0) previous = current
            }
        }
    }

    /** 1.2.0: resolve a -1 partId inside the manager itself, so
     *  steering-wheel / session next/previous works even when
     *  the Activity ViewModel is gone. Previously only the
     *  ViewModel could resolve, so a session skip to a -1 track
     *  loaded a dead URL and froze. */
    private var consecutivePlayFailures = 0

    private fun autoSkipOrError() {
        if (shouldAutoSkip(consecutivePlayFailures, player.hasNextMediaItem())) {
            try {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } catch (_: Exception) { }
        } else {
            _playbackError.value = "Could not play this track. Skipped to the next one failed too — check the file on your server."
        }
    }

    private fun resolvePartIdInternal(index: Int, track: PlexTrack) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val full = com.fourgeailabs.prismtune.data.PlexClient()
                    .trackMetadata(queueServerBase, queueToken, queueDeviceId, track.key)
                if (full != null && full.partId >= 0) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        updateTrackPartId(index, full)
                    }
                } else {
                    // Unresolvable: auto-skip, don't freeze here.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        consecutivePlayFailures++
                        autoSkipOrError()
                    }
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    consecutivePlayFailures++
                    autoSkipOrError()
                }
            }
        }
    }

    /** 1.2.1: queue metadata only, no player items. Used when
     *  a saved queue is restored at cold boot but the server
     *  is not reachable yet — Next Up and the mini player can
     *  still show the queue, instead of showing empty. When the
     *  library loads later, setQueue replaces it with real
     *  player items. Never starts a foreground service. */
    /**
     * 1.4.0: true only when the player actually holds media
     * items. A metadata-only queue (safe launch) returns
     * false, so resumeSavedQueue() can still play it.
     */
    fun hasPlayerItems(): Boolean = try { !released && player.mediaItemCount > 0 } catch (_: Exception) { false }

    fun setQueueMetadata(tracks: List<PlexTrack>, startIndex: Int) {
        // 1.4.0: a metadata queue with blank keys or an
        // out-of-range index is ignored, never restored.
        if (released || tracks.isEmpty() || tracks.any { it.key.isBlank() }) return
        _queue.value = tracks.toMutableList()
        _currentIndex.value = startIndex.coerceIn(0, tracks.size - 1)
    }

    /** 1.5.7: record the server connection for a metadata-
     *  only queue WITHOUT building player items, seeking,
     *  or starting the media service. This is what makes
     *  a cold-boot tap on play work: togglePlayPause's
     *  rebuild guard needs a non-blank queueServerBase,
     *  and the 1.4.0 metadata restore never set one. */
    fun setQueueConnection(serverBase: String, token: String, quality: String) {
        if (released) return
        queueServerBase = serverBase
        queueToken = token
        queueQuality = quality
    }

    /** 1.2.1 buffer next 10: resolve partIds for the next 10
     *  queue tracks in the background as the current one plays,
     *  so skipping needs no live buffer wait. ExoPlayer already
     *  buffers the next media item; resolved partIds make the
     *  next 10 URLs valid before they are reached.
     *  1.2.1 crash fix: one sequential prefetch job, cancelled
     *  on the next transition, instead of 10 concurrent fetches
     *  piling up on every skip — that pile-up was a skip-crash
     *  suspect on a large queue. */
    private var prefetchJob: Job? = null
    private fun prefetchNext10(currentIndex: Int) {
        if (queueServerBase.isBlank() || queueToken.isBlank()) return
        prefetchJob?.cancel()
        val queue = _queue.value
        val indices = upNextIndices(10)
        prefetchJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (i in indices) {
                if (!isActive) break
                val track = queue.getOrNull(i) ?: continue
                if (track.partId < 0 && track.localPath.isNullOrBlank()) {
                    try {
                        val full = com.fourgeailabs.prismtune.data.PlexClient()
                            .trackMetadata(queueServerBase, queueToken, queueDeviceId, track.key)
                        if (full != null && full.partId >= 0) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                updateTrackPartIdSilent(i, full)
                            }
                        }
                    } catch (_: Exception) { /* next track tries later; never a crash */ }
                }
            }
        }
    }

    /** 1.2.1: update a future track's partId without copying
     *  the whole queue list. The old updateTrackPartId copied
     *  a 46k list on every prefetch hit, which churned memory
     *  on the car and was a crash suspect. This only replaces
     *  the player's media item; the queue flow does not need
     *  to re-emit for an internal partId. */
    private fun updateTrackPartIdSilent(index: Int, resolved: PlexTrack) {
        try {
            val q = _queue.value
            if (index !in q.indices) return
            // Mutate the backing list in place, no copy, no emit.
            if (q is MutableList<PlexTrack>) q[index] = resolved
            player.replaceMediaItem(index, resolved.toMediaItem(queueServerBase, queueToken, queueQuality))
        } catch (_: Exception) { /* item will fail visibly via onPlayerError */ }
    }

    /**
     * 1.1.0: live amplitude envelope, sampled ~25 fps for the
     * ambient waveform. While playing this is the real PCM envelope;
     * while paused / no track it decays to flat (never fake bars).
     */
    private val _waveformLevels = MutableStateFlow(List(96) { 0f })
    val waveformLevels: StateFlow<List<Float>> = _waveformLevels.asStateFlow()

    init {
        // 1.3.2: 60ms instead of 40ms — two Canvases recomposing
        // at 25fps on a head unit was jank that could tip into
        // an ANR a few seconds after boot. 16fps is still smooth
        // for a waveform, and far cheaper.
        waveformJob = scope.launch {
            while (isActive) {
                if (!released) {
                    _waveformLevels.value =
                        if (_isPlaying.value) waveformProcessor.envelope.snapshot()
                        else waveformProcessor.envelope.idleStep()
                }
                delay(60)
            }
        }
    }

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

    @Volatile private var released = false
    val isReleased: Boolean get() = released

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** 0 = off, 1 = all, 2 = one. */
    private val _repeatMode = MutableStateFlow(0)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    /** 1.1.0: plain playback error surfaced on Now Playing / mini
     *  player, instead of a silent failure. Never contains a token
     *  or URL. Null = no error. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearPlaybackError() { _playbackError.value = null }

    // Context for the current queue, so an ExoPlayer error can
    // retry the failing item via universal transcode.
    private var queueServerBase: String = ""
    private var queueToken: String = ""
    private var queueQuality: String = "Original"
    private val transcodeFallbackTried = mutableSetOf<Int>()

    /**
     * 1.1.3 lazy partId resolution: the ViewModel sets this to
     * resolve a track's partId on demand. playTracks() only
     * resolves the first 21 items; without this, skipping past
     * item 21 handed ExoPlayer a /parts/-1/file URL, which failed
     * and then failed again via transcode with the same -1.
     */
    var onTrackNeedsPartId: ((Int, PlexTrack) -> Unit)? = null

    val currentTrack: PlexTrack?
        get() = _queue.value.getOrNull(_currentIndex.value)

    /**
     * 1.3.9: the indices Up Next must show, in actual playback
     * order. Shuffle off: natural order. Shuffle on: walk the
     * player's timeline, which is the shuffled sequence ExoPlayer
     * will actually play. Falls back to natural order whenever
     * the player has no items yet (metadata-only restore) or the
     * timeline does not match the queue, so this never crashes
     * and never shows an empty list the natural order could fill.
     */
    fun upNextIndices(limit: Int = 100): List<Int> {
        val q = _queue.value
        val cur = _currentIndex.value
        if (q.isEmpty()) return emptyList()
        if (!_shuffle.value) return upNextOrderFrom(cur, q.size, false, { C.INDEX_UNSET }, limit)
        return try {
            if (player.mediaItemCount != q.size) return upNextOrderFrom(cur, q.size, false, { C.INDEX_UNSET }, limit)
            val timeline = player.currentTimeline
            if (timeline.windowCount != q.size) return upNextOrderFrom(cur, q.size, false, { C.INDEX_UNSET }, limit)
            val start = player.currentMediaItemIndex.takeIf { it >= 0 } ?: cur
            upNextOrderFrom(start, q.size, true, { idx -> timeline.getNextWindowIndex(idx, player.repeatMode, player.shuffleModeEnabled) }, limit)
        } catch (_: Exception) {
            upNextOrderFrom(cur, q.size, false, { C.INDEX_UNSET }, limit)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentIndex.value = player.currentMediaItemIndex.takeIf { it >= 0 } ?: _currentIndex.value
                _durationMs.value = player.duration.coerceAtLeast(0)
                _playbackError.value = null
                consecutivePlayFailures = 0
                prefetchNext10(_currentIndex.value)
                // 1.1.3/1.2.0: if this track has no partId yet,
                // resolve it now instead of letting ExoPlayer
                // load a -1 URL. Internal resolution first (works
                // for session skips with no ViewModel), then the
                // ViewModel handler as a fallback.
                val idx = _currentIndex.value
                val track = _queue.value.getOrNull(idx)
                if (track != null && track.partId < 0 && track.localPath.isNullOrBlank()) {
                    _playbackError.value = "Resolving track…"
                    if (queueServerBase.isNotBlank() && queueToken.isNotBlank()) {
                        resolvePartIdInternal(idx, track)
                    } else {
                        onTrackNeedsPartId?.invoke(idx, track)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 1.1.0: previously there was no onPlayerError at
                // all, so a bad part URL (e.g. /parts/-1/file) failed
                // silently. First failure on a direct-play item
                // retries that item via universal transcode; any
                // further failure surfaces a plain error instead
                // of spinning forever.
                // 1.2.0: never retry transcode with a -1 partId —
                // that was the next/previous freeze. Resolve first.
                val idx = player.currentMediaItemIndex
                val track = _queue.value.getOrNull(idx)
                if (track != null && track.partId < 0 && track.localPath.isNullOrBlank()) {
                    _playbackError.value = "Resolving track…"
                    if (queueServerBase.isNotBlank() && queueToken.isNotBlank()) {
                        resolvePartIdInternal(idx, track)
                    } else {
                        onTrackNeedsPartId?.invoke(idx, track)
                    }
                    return
                }
                if (track != null && queueQuality == "Original" && idx !in transcodeFallbackTried) {
                    transcodeFallbackTried.add(idx)
                    _playbackError.value = "Direct play failed, trying transcode…"
                    try {
                        val item = track.toMediaItem(queueServerBase, queueToken, "320")
                        player.replaceMediaItem(idx, item)
                        player.prepare()
                        player.play()
                        return
                    } catch (_: Exception) { /* fall through to auto-skip below */ }
                }
                // 1.2.0: an unplayable song skips automatically
                // to the next song instead of freezing on it.
                consecutivePlayFailures++
                autoSkipOrError()
            }
        })
        positionJob = scope.launch {
            while (isActive) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0)
                _durationMs.value = player.duration.coerceAtLeast(0)
                delay(250)
            }
        }
    }

    fun setQueue(tracks: List<PlexTrack>, startIndex: Int, serverBase: String, token: String, quality: String, autoplay: Boolean = true) {
        // 1.2.1 crash fix: only start the foreground service
        // when playback actually starts. Starting it for a
        // paused restore left a foreground service with no
        // playing player, which Android kills as a crash
        // (ForegroundServiceDidNotStartInTime) — that matches
        // the "crashes even without music playing" report.
        if (autoplay) startMediaService()
        _queue.value = tracks.toMutableList()
        queueServerBase = serverBase
        queueToken = token
        queueQuality = quality
        transcodeFallbackTried.clear()
        _playbackError.value = null
        // 1.4.1: INSTANT-ON-TAP CAR CRASH GUARD. On the
        // Equinox, tapping any song killed the app instantly,
        // before sound — the tap path is setQueue() ->
        // setMediaItems / prepare / play, and every one of
        // those was unguarded. A single bad MediaItem, a
        // renderer failure surfaced synchronously, or a
        // Media3 foreground promotion failure on this head
        // unit would kill the whole process on tap. Now the
        // player calls are guarded: on failure the queue
        // stays visible, a plain playback error is shown,
        // and the app stays alive to report it — instead of
        // dying and leaving a saved queue that bricks the
        // next launch. This does not fix a genuinely
        // unplayable stream, it stops it from being fatal.
        val items = try {
            tracks.map { track -> track.toMediaItem(serverBase, token, quality) }
        } catch (_: Exception) {
            _playbackError.value = "Could not build this queue for playback on this device."
            return
        }
        if (released) return
        try {
            player.setMediaItems(items, startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), 0)
            try { player.shuffleModeEnabled = _shuffle.value } catch (_: Exception) { /* shuffle preference kept; player state unchanged */ }
            _currentIndex.value = startIndex
            player.prepare()
            if (autoplay) player.play()
        } catch (_: Exception) {
            _playbackError.value = "Playback could not start on this device for this track. Try another track, or check the file on your server."
        }
    }

    /**
     * 1.1.0 resume: restore a saved queue at a saved position
     * WITHOUT autoplaying, so tapping play / the mini player
     * resumes from that position instead of restarting at 0.
     */
    fun restoreQueue(tracks: List<PlexTrack>, startIndex: Int, positionMs: Long, serverBase: String, token: String, quality: String) {
        if (released || tracks.isEmpty()) return
        setQueue(tracks, startIndex.coerceIn(0, tracks.size - 1), serverBase, token, quality, autoplay = false)
        if (positionMs > 0) {
            try { player.seekTo(positionMs) } catch (_: Exception) { /* start at 0 rather than crash */ }
        }
    }

    /** 1.1.0: replace one queue item with its resolved partId
     *  (fetched on demand by the ViewModel). If it is the current
     *  item, playback restarts from it. No-op for bad indices. */
    fun updateTrackPartId(index: Int, resolved: PlexTrack) {
        val q = _queue.value
        if (index !in q.indices) return
        if (q is MutableList<PlexTrack>) q[index] = resolved else _queue.value = q.toMutableList().also { it[index] = resolved }
        try {
            player.replaceMediaItem(index, resolved.toMediaItem(queueServerBase, queueToken, queueQuality))
            if (index == player.currentMediaItemIndex) {
                player.prepare()
                player.play()
            }
        } catch (_: Exception) { /* item will fail visibly via onPlayerError */ }
    }

    fun playIndex(index: Int) {
        if (released) return
        if (index in _queue.value.indices) {
            startMediaService()
            player.seekTo(index, 0)
            player.play()
            _currentIndex.value = index
        }
    }

    fun togglePlayPause() {
        if (released) return
        // 1.2.0: any manual play/pause while muted cancels the
        // mute-resume, so unmuting does not restart music the
        // user deliberately controlled while muted.
        val volume = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        if (volume == 0) mutePaused = false
        try {
            if (player.isPlaying) {
                player.pause()
            } else {
                // 1.5.0 PLAY/PAUSE DEAD BUTTON FIX:
                // 2026-10-27 — mini-player and Now Playing
                // play/pause sometimes did nothing, then
                // started working after selecting a mood.
                // Cause: after a metadata-only restore, the
                // queue list is non-empty but the PLAYER has
                // no media items yet. player.play() on an
                // empty playlist is a silent no-op, so
                // isPlaying never flips and the button looks
                // dead. Selecting a mood rebuilt the player
                // via setQueue, which is why it then worked.
                // Now: if the player is empty but a queue
                // exists, rebuild player items from that
                // queue at the current index BEFORE playing.
                if (player.mediaItemCount == 0 && _queue.value.isNotEmpty() && queueServerBase.isNotBlank()) {
                    val items = _queue.value.map { it.toMediaItem(queueServerBase, queueToken, queueQuality) }
                    player.setMediaItems(items, _currentIndex.value.coerceIn(0, (_queue.value.size - 1).coerceAtLeast(0)), 0)
                    player.prepare()
                }
                startMediaService()
                player.play()
            }
        } catch (_: Exception) { /* released/unprepared: no crash */ }
    }

    /** 1.2.3: Next must never be a dead button when the queue
     *  flow has a next track but the player does not — that
     *  happens after a metadata-only cold-boot restore, where
     *  _queue is set but the player was never given media items.
     *  In that case, rebuild the player items from _queue and
     *  seek to the next index, instead of silently doing nothing.
     *  Returns true if a next track was started. */
    fun next(): Boolean {
        if (released) return false
        return try {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                true
            } else {
                val nextIndex = _currentIndex.value + 1
                if (nextIndex in _queue.value.indices && queueServerBase.isNotBlank()) {
                    val items = _queue.value.map { it.toMediaItem(queueServerBase, queueToken, queueQuality) }
                    // 1.3.9: with shuffle on, seeking to nextIndex
                    // plays the NATURAL next song, not the shuffled
                    // one. Rebuild at the current song and let the
                    // player pick its shuffled next instead.
                    val startIndex = if (_shuffle.value) _currentIndex.value else nextIndex
                    player.setMediaItems(items, startIndex, 0)
                    player.prepare()
                    player.play()
                    if (_shuffle.value) {
                        try { player.shuffleModeEnabled = true; player.seekToNextMediaItem() } catch (_: Exception) { }
                        _currentIndex.value = player.currentMediaItemIndex.takeIf { it >= 0 } ?: nextIndex
                    } else {
                        _currentIndex.value = nextIndex
                    }
                    true
                } else if (nextIndex in _queue.value.indices) {
                    // Local-only queue: player items from local files.
                    val items = _queue.value.map { it.toMediaItem("", "", queueQuality) }
                    val startIndex = if (_shuffle.value) _currentIndex.value else nextIndex
                    player.setMediaItems(items, startIndex, 0)
                    player.prepare()
                    player.play()
                    if (_shuffle.value) {
                        try { player.shuffleModeEnabled = true; player.seekToNextMediaItem() } catch (_: Exception) { }
                        _currentIndex.value = player.currentMediaItemIndex.takeIf { it >= 0 } ?: nextIndex
                    } else {
                        _currentIndex.value = nextIndex
                    }
                    true
                } else {
                    false
                }
            }
        } catch (_: Exception) { false }
    }

    /** 1.2.3: true if Next will do something — player next, or a
     *  queue-flow next that next() can rebuild the player from. */
    fun hasNextTrack(): Boolean {
        if (released) return false
        // 1.3.9: upNextIndices already accounts for shuffle, so
        // a shuffled last song is not reported as having a next
        // just because a natural-order song follows it.
        return try {
            upNextIndices(1).isNotEmpty() || player.hasNextMediaItem()
        } catch (_: Exception) { false }
    }

    fun previous() {
        if (released) return
        try {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
            else player.seekTo(0)
        } catch (_: Exception) { }
    }

    fun seekTo(ms: Long) {
        if (released) return
        try { player.seekTo(ms.coerceIn(0, player.duration.coerceAtLeast(0))) } catch (_: Exception) { }
    }

    /**
     * 1.1.0 defensive shuffle: returns true only if the requested
     * state is now in effect (player applied, or stored for an
     * empty queue). A released player or a player exception never
     * crashes and never changes the UI state.
     */
    fun setShuffle(enabled: Boolean): Boolean {
        if (released) return false
        if (!shouldCallPlayerForShuffle(_queue.value.isEmpty(), released)) {
            _shuffle.value = enabled
            return true
        }
        return try {
            player.shuffleModeEnabled = enabled
            _shuffle.value = enabled
            true
        } catch (e: Exception) {
            _playbackError.value = "Shuffle could not be changed. Try again."
            false
        }
    }

    fun cycleRepeat(): Int {
        if (released) return _repeatMode.value
        val next = (_repeatMode.value + 1) % 3
        _repeatMode.value = next
        try {
            player.repeatMode = when (next) {
                1 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        } catch (_: Exception) { /* released/unprepared: repeat preference kept */ }
        return next
    }

    fun release() {
        released = true
        positionJob?.cancel()
        waveformJob?.cancel()
        muteWatchJob?.cancel()
        try { player.release() } catch (_: Exception) { /* already released */ }
    }

    private fun PlexTrack.toMediaItem(serverBase: String, token: String, quality: String): MediaItem {
        // 1.1.0 offline preference: a downloaded file plays even
        // when the server is unreachable; streaming is the fallback.
        val local = localPath?.let { java.io.File(it) }?.takeIf { it.exists() && it.length() > 0 }
        if (local != null) {
            // 1.2.2: local files also carry artwork, so the car
            // system and instrument cluster show it offline too.
            val artwork = PlexUrls.thumbUrl(serverBase, thumb, token)
            return MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(local))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title).setArtist(artistTitle).setAlbumTitle(albumTitle)
                        .setArtworkUri(artwork?.let { android.net.Uri.parse(it) })
                        .build(),
                ).build()
        }
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
