package com.fourgeailabs.prismtune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.VoicePlayOutcome
import com.fourgeailabs.prismtune.data.resolveVoiceSearch
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Exposes the shared ExoPlayer through a MediaLibrarySession so the
 * car's own controls (steering wheel, system media card, voice) control
 * PrismTune. This service creates no UI and no video surface.
 *
 * 1.0.4 voice search: the service class and manifest entry are
 * unchanged (same ".playback.PrismTuneMediaService"), but the session
 * is now a MediaLibrarySession, which is the Media3 hook a car
 * assistant uses for search ("Hey play Toby Keith on PrismTune"):
 * the assistant's MediaBrowser calls onSearch, then onGetSearchResult,
 * then adds the chosen items, which onAddMediaItems routes to the
 * existing ViewModel playback path. Everything runs on the loaded
 * library snapshot only: no match, or a library that is not loaded
 * yet, means zero results and playback stays stopped — never a
 * random track and never an invented result. No Gemini API key, no
 * cloud call, no account: this is local library search via the
 * media session, which is what the car assistant routes to.
 *
 * Honest limitation: whether the Equinox's Gemini/Assistant actually
 * routes an "on PrismTune" request to this session can only be proven
 * on real hardware — the sandbox cannot summon the car assistant.
 */
class PrismTuneMediaService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var lastSearchTracks: List<PlexTrack> = emptyList()
    private var lastSearchQuery: String = ""

    private fun trackToSearchItem(track: PlexTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artistTitle)
                    .setAlbumTitle(track.albumTitle)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun resolve(query: String): List<PlexTrack> {
        val tracks = PrismTuneAppState.voiceTracks
        return when (val outcome = resolveVoiceSearch(query, tracks, PrismTuneAppState.voiceLibraryLoaded)) {
            is VoicePlayOutcome.Played -> outcome.tracks
            else -> emptyList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 1.3.3: crash capture, in case the service starts
        // before the Activity (car media menu).
        runCatching { com.fourgeailabs.prismtune.data.CrashLog.install(this) }
        // 1.3.1: a service-creation failure must never crash
        // the app on boot — the notification provider is
        // best-effort, playback still works in-app without it.
        runCatching {
            setMediaNotificationProvider(
                androidx.media3.session.DefaultMediaNotificationProvider(this)
            )
        }
        // 1.3.2: if the session itself fails to build on a
        // particular head unit, the service must not crash the
        // app a few seconds after boot — it just runs without
        // a session that launch.
        val manager = runCatching {
            PrismTuneAppState.playbackManager
                ?: PlaybackManager(applicationContext).also { PrismTuneAppState.playbackManager = it }
        }.getOrNull() ?: return
        val sessionActivity = android.app.PendingIntent.getActivity(
            this,
            0,
            android.content.Intent(this, com.fourgeailabs.prismtune.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = runCatching {
            MediaLibrarySession.Builder(this, manager.player, object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(
                        // 1.4.2 ROOT CAUSE, PROVEN BY ERIC'S 2026-10-27
                        // 01:21:42 CAR CRASH LOG: Media3
                        // LibraryResult.verifyMediaItem throws
                        // IllegalArgumentException "mediaMetadata
                        // must specify isPlayable" when a
                        // MediaItem's metadata has isBrowsable
                        // set but isPlayable left unset. The
                        // car's media browser calls
                        // onGetLibraryRoot on bind — on the
                        // Equinox that is on tap / on cold
                        // open after a play — so the exception
                        // killed the whole process instantly,
                        // before sound, and bricked every later
                        // launch until the car gave up binding.
                        // The Pixel never binds as a media
                        // browser, which is why it never crashed
                        // there. A browsable folder is NOT
                        // playable: setIsPlayable(false).
                        MediaMetadata.Builder().setTitle("PrismTune").setIsBrowsable(true).setIsPlayable(false).build(),
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                // 1.2.0: browsable media source. Previously this
                // returned an empty list, so the car's music menu
                // had nothing to show for PrismTune and it never
                // appeared as a selectable source with content.
                fun browsable(id: String, title: String) = MediaItem.Builder()
                    .setMediaId(id)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build(),
                    ).build()
                fun playable(track: PlexTrack) = MediaItem.Builder()
                    .setMediaId(track.key)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title).setArtist(track.artistTitle)
                            .setAlbumTitle(track.albumTitle).setIsPlayable(true).build(),
                    ).build()
                val items: List<MediaItem> = when {
                    parentId == "root" -> listOf(
                        browsable("playlists", "Playlists"),
                        browsable("recent", "Recently Added"),
                        browsable("offline", "Offline Top Picks"),
                    )
                    parentId == "playlists" -> PrismTuneAppState.playlists.map { pl ->
                        MediaItem.Builder()
                            .setMediaId("playlist:${pl.key}")
                            .setMediaMetadata(
                                MediaMetadata.Builder().setTitle(pl.title).setIsPlayable(true).build(),
                            ).build()
                    }
                    parentId == "recent" -> PrismTuneAppState.recentTracks.map { playable(it) }
                    parentId == "offline" -> PrismTuneAppState.offlineTracks.map { playable(it) }
                    else -> emptyList()
                }
                val pageItems = if (pageSize > 0) items.drop(page * pageSize).take(pageSize) else items
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params),
                )
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<Void>> {
                lastSearchQuery = query
                lastSearchTracks = resolve(query)
                session.notifySearchResultChanged(browser, query, lastSearchTracks.size, params)
                return Futures.immediateFuture(LibraryResult.ofVoid(params))
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val tracks = if (query == lastSearchQuery) lastSearchTracks else resolve(query)
                val items = tracks.map { trackToSearchItem(it) }
                val pageItems = if (pageSize > 0) items.drop(page * pageSize).take(pageSize) else items
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params),
                )
            }

            override fun onAddMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
            ): ListenableFuture<List<MediaItem>> {
                // 1.2.0: playlist items from the car browser play
                // the whole playlist through the ViewModel path.
                val playlistItem = mediaItems.firstOrNull { it.mediaId.startsWith("playlist:") }
                if (playlistItem != null) {
                    val key = playlistItem.mediaId.removePrefix("playlist:")
                    val handler = PrismTuneAppState.playPlaylistByKey
                    if (handler != null) {
                        handler(key)
                        return Futures.immediateFuture(emptyList())
                    }
                }
                val tracksByKey = (PrismTuneAppState.voiceTracks + PrismTuneAppState.recentTracks + PrismTuneAppState.offlineTracks)
                    .associateBy { it.key }
                val mapped = mediaItems.mapNotNull { tracksByKey[it.mediaId] }
                val handler = PrismTuneAppState.voicePlayTracks
                return if (mapped.isNotEmpty() && handler != null) {
                    // Play through the existing ViewModel path on the
                    // shared player, instead of handing the session
                    // URI-less search items it cannot stream. Returning
                    // an empty list stops the session from also trying
                    // to play those items.
                    handler(mapped, 0)
                    Futures.immediateFuture(emptyList())
                } else {
                    Futures.immediateFuture(mediaItems)
                }
            }
        }).setSessionActivity(sessionActivity).build()
        }.getOrNull()
        if (mediaSession == null) return
    }

    /**
     * 1.3.7: system-level media, like Apple Music in the user's
     * 2026-10-26 23:47 screenshot — NOT a swipeable notification.
     * That screenshot is Android's media-controls panel, which
     * is populated from an ACTIVE MediaSession with metadata,
     * plus a MediaStyle notification tied to that session's
     * platform token, marked ongoing (so it cannot be swiped
     * away). The 1.3.6 basic foreground notification was a
     * regular notification, not a media session notification,
     * so the system did not show PrismTune as a media source.
     * This override foregrounds with a MediaStyle notification
     * built from the session's platform token and the current
     * track's title/artist, with play/pause, previous, and
     * next actions that send commands back to this service.
     * Actions are handled in onStartCommand below: PLAY_PAUSE
     * toggles the shared player, NEXT/PREVIOUS seek the player.
     * The notification is ongoing, so it stays until playback
     * stops, exactly like Apple Music's card.
     */
    private fun mediaNotification(): android.app.Notification? {
        val session = mediaSession ?: return null
        return runCatching {
            val channelId = "prismtune_playback"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val manager = getSystemService(android.app.NotificationManager::class.java)
                if (manager?.getNotificationChannel(channelId) == null) {
                    manager?.createNotificationChannel(
                        android.app.NotificationChannel(
                            channelId, "PrismTune playback",
                            android.app.NotificationManager.IMPORTANCE_LOW,
                        ).apply { description = "Shows while PrismTune is playing, so the car and system know it is active media." }
                    )
                }
            }
            val player = session.player
            val meta = player.currentMediaItem?.mediaMetadata
            val title = meta?.title?.toString() ?: "PrismTune"
            val artist = meta?.artist?.toString() ?: ""
            fun actionIntent(action: String): android.app.PendingIntent {
                val intent = android.content.Intent(this, PrismTuneMediaService::class.java).apply { this.action = action }
                return android.app.PendingIntent.getService(
                    this, action.hashCode(), intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val sessionActivity = android.app.PendingIntent.getActivity(
                this, 0,
                android.content.Intent(this, com.fourgeailabs.prismtune.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Framework MediaStyle, not the support-library one:
            // it takes the platform MediaSession.Token directly,
            // which is what Media3's session.platformToken is.
            // That is what makes Android treat this as a media
            // source in the system media panel, not a regular
            // notification.
            val builder = android.app.Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(sessionActivity)
                .setOngoing(true)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent("ACTION_PREVIOUS"))
                .addAction(
                    if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (player.isPlaying) "Pause" else "Play",
                    actionIntent("ACTION_PLAY_PAUSE"),
                )
                .addAction(android.R.drawable.ic_media_next, "Next", actionIntent("ACTION_NEXT"))
                .setStyle(
                    android.app.Notification.MediaStyle()
                        .setMediaSession(session.platformToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            builder.build()
        }.getOrNull()
    }

    private fun refreshMediaNotification() {
        val notification = mediaNotification() ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // Media action intents from the system media notification.
        when (intent?.action) {
            "ACTION_PLAY_PAUSE" -> {
                val player = mediaSession?.player
                if (player != null) {
                    if (player.isPlaying) player.pause() else player.play()
                }
                refreshMediaNotification()
                return START_STICKY
            }
            "ACTION_NEXT" -> {
                mediaSession?.player?.seekToNextMediaItem()
                refreshMediaNotification()
                return START_STICKY
            }
            "ACTION_PREVIOUS" -> {
                mediaSession?.player?.seekToPreviousMediaItem()
                refreshMediaNotification()
                return START_STICKY
            }
        }
        // Foreground immediately, with the media notification if
        // the session is ready, otherwise a placeholder that
        // refreshMediaNotification replaces once the session
        // and track are known. This is what keeps background
        // playback alive and what the system media panel reads.
        runCatching { refreshMediaNotification() }
        val result = super.onStartCommand(intent, flags, startId)
        // Keep the media notification current as tracks change.
        mediaSession?.player?.let { player ->
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int,
                ) { refreshMediaNotification() }
                override fun onIsPlayingChanged(isPlaying: Boolean) { refreshMediaNotification() }
            })
        }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }
}

/** Process-wide holder so the Activity UI and the MediaSession share one player. */
object PrismTuneAppState {
    var playbackManager: PlaybackManager? = null

    /** 1.0.4: snapshot of the loaded library for voice search. */
    var voiceTracks: List<PlexTrack> = emptyList()
    var voiceLibraryLoaded: Boolean = false

    /** 1.0.4: set by the ViewModel; plays a voice query through the existing playback path. */
    var voicePlayFromSearch: ((String) -> VoicePlayOutcome)? = null

    /** 1.0.4: set by the ViewModel; plays an explicit track list through the existing playback path. */
    var voicePlayTracks: ((List<PlexTrack>, Int) -> Unit)? = null

    /** 1.2.0: snapshots for the car media browser, so PrismTune
     *  shows up as a browsable media source, not just a session.
     *  Set by the ViewModel when the library loads / plays. */
    var playlists: List<com.fourgeailabs.prismtune.data.PlexPlaylist> = emptyList()
    var recentTracks: List<PlexTrack> = emptyList()
    var offlineTracks: List<PlexTrack> = emptyList()

    /** 1.2.0: set by the ViewModel; plays a playlist by its Plex
     *  key when the car browser taps a playlist item. */
    var playPlaylistByKey: ((String) -> Unit)? = null
}
