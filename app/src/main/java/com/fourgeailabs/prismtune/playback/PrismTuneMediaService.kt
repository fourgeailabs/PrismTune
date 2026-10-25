package com.fourgeailabs.prismtune.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Exposes the shared ExoPlayer through a MediaSession so the car's own
 * controls (steering wheel, system media card, voice) control PrismTune.
 * This service creates no UI and no video surface.
 */
class PrismTuneMediaService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val manager = PrismTuneAppState.playbackManager
            ?: PlaybackManager(applicationContext).also { PrismTuneAppState.playbackManager = it }
        mediaSession = MediaSession.Builder(this, manager.player).build()
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }
}

/** Process-wide holder so the Activity UI and the MediaSession share one player. */
object PrismTuneAppState {
    var playbackManager: PlaybackManager? = null
}
