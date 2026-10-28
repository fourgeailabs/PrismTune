package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.fourgeailabs.prismtune.data.SafeAreaConfig
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel
import kotlin.math.roundToInt

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * 1.1.0 player artwork: real art when it loads; when it cannot
 * load (or there is no thumb), the PrismTune vinyl icon spinning
 * like a record takes its place — LoadingVinyl, clockwise.
 * Used ONLY in the player / mini player, not in list rows.
 */
@Composable
fun PlayerArtwork(artworkUrl: String?, size: androidx.compose.ui.unit.Dp) {
    if (artworkUrl == null) {
        LoadingVinyl(size = size)
    } else {
        SubcomposeAsyncImage(
            model = artworkUrl,
            contentDescription = "Album artwork",
            modifier = Modifier.size(size),
            loading = { LoadingVinyl(size = size) },
            error = { LoadingVinyl(size = size) },
        )
    }
}

@Composable
fun NowPlayingScreen(vm: PrismViewModel, onBack: () -> Unit) {
    val playback = vm.playback
    val isPlaying by playback.isPlaying.collectAsState()
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    val shuffle by playback.shuffle.collectAsState()
    val repeat by playback.repeatMode.collectAsState()
    val track = playback.currentTrack

    // 1.5.0: NO BackRow on Now Playing — Eric,
    // 2026-10-27: the back button needs to go away, and
    // the top nav bar already navigates everywhere. Removing
    // it also moves art/title/controls up, which is part of
    // fitting everything on the 4.875-inch-tall Equinox
    // screen. onBack is kept in the signature for callers
    // but is intentionally unused here.
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (track == null) {
            Text("Nothing playing yet. Pick something from Home or Library.", fontSize = 20.sp, color = PrismOnSurface)
            return
        }
        val art = vm.artworkUrl(track.thumb)
        val playbackError by playback.playbackError.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerArtwork(art, 120.dp)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(track.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text(track.artistTitle, fontSize = 20.sp, color = PrismAmber)
                Text(track.albumTitle, fontSize = 18.sp, color = PrismSecondary)
            }
        }
        if (playbackError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(playbackError!!, fontSize = 16.sp, color = Color(0xFFFF6B6B))
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 1.1.0 full metadata for the song as it plays, where
        // Plex provides it — never invented, blank fields omitted.
        val metaLines = buildList {
            track.year?.let { add("Year: $it") }
            if (track.index > 0) add("Track ${track.index}" + if (track.discNumber > 0) " • Disc ${track.discNumber}" else "")
            if (track.genres.isNotEmpty()) add("Genres: " + track.genres.joinToString(", "))
            if (track.moods.isNotEmpty()) add("Moods: " + track.moods.joinToString(", "))
            if (track.styles.isNotEmpty()) add("Styles: " + track.styles.joinToString(", "))
            track.durationLabel.let { add("Duration: $it") }
        }
        metaLines.forEach { line ->
            Text(line, fontSize = 16.sp, color = PrismSecondary)
        }
        track.summary?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(summary, fontSize = 16.sp, color = PrismOnSurface)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 1.2.0 waveform progress bar: the progress bar IS a
        // smooth centre-line waveform, left-to-right, coloured
        // amber up to the current position and muted after it.
        // Tap it to seek to that point in the song. Bars are
        // the live PCM envelope when playing; when paused they
        // hold a minimum height so the bar still reads as a
        // waveform instead of vanishing to a flat line.
        val waveformLevels by vm.playback.waveformLevels.collectAsState()
        // 1.4.2: shrunk from 220dp to 96dp — Eric,
        // 2026-10-27: the 220dp waveform pushed the play /
        // pause / next controls off-screen, so they needed
        // a scroll to reach. 96dp still shows the full
        // spectrum shape, and the controls now fit on one
        // screen with no scrolling.
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxWidth().height(96.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (duration > 0) {
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            vm.playback.seekTo((duration * frac).toLong())
                        }
                    }
                },
        ) {
            // 1.5.0 WAVEFORM FIX, part 2 — Eric's video,
            // 2026-10-27 01:54, Skillet "Madness in Me":
            // the played side was a solid orange block and
            // the unplayed side a flat white tail, and the
            // block barely moved with the music. Two more
            // causes on top of the photo fixes above: (a)
            // the X axis was being read as PROGRESS, but a
            // spectrum's X axis is FREQUENCY — bar 0 is bass,
            // bar 95 is treble, all bars live at once, and
            // the playhead must never split them by colour;
            // (b) the FFT only read the first 1024 samples
            // of each buffer and the short-buffer fallback
            // set every bar to the same RMS, both of which
            // flatten the shape into a block. See
            // Waveform.kt rolling window + idleStep fallback.
            // 1.5.0 WAVEFORM FIX — Eric, 2026-10-27, "does
            // something look a little weird and off about the
            // waveform? I'm not just talking about color."
            // Yes, and it was not the spectrum data, it was
            // the DRAWING, three bugs stacked:
            // (1) PROGRESS SPLIT: bars left of the playhead
            // were painted amber, bars right of it grey, so
            // the waveform looked like a solid block that
            // suddenly dropped to a flat line at the playhead
            // — in Eric's photo, a thick blue slab up to
            // ~3:17, then a thin grey tail. That is not a
            // spectrum shape, that is a progress bar wearing
            // a spectrum costume. Fixed: every bar is the
            // same accent colour, at its own spectrum height.
            // Progress is already shown by the 3:17 / 4:14
            // times below, it must not also recolour the bars.
            // (2) FAT STROKE: strokeWidth was 2% of the full
            // screen width per bar, times 96 bars, so bars
            // merged into one solid slab with no gaps. Fixed:
            // a fixed 3dp stroke, so 96 bars read as 96 bars.
            // (3) FAKE FLOOR: maxOf(level, 0.02f) forced every
            // silent bar to 2% height, so quiet treble never
            // went truly flat. Fixed: level as-is, silence
            // is flat, loud is tall, honestly.
            // 1.5.1 — Eric, 2026-10-27 02:16, correcting
            // 1.5.0: "The wave form is supposed to be a
            // progress bar too. Not just wave form." He is
            // right, and 1.5.0 over-corrected by painting
            // every bar one accent colour. The fix is to
            // split the two jobs, not pick one: HEIGHT is
            // the live spectrum (FFT, bass left, treble
            // right, rolling window, no RMS block), COLOUR
            // is progress. Unplayed bars: white on dark
            // backgrounds, black on light backgrounds.
            // 1.5.5: played bars are the harmonious tint/
            // shade of the ACTUAL palette background (see
            // progressColor in Theme.kt), in full from the
            // first played bar — NOT the old complement,
            // and NOT coloured from a fixed charcoal, which
            // is what put light blue on a red screen.
            // Tap-to-seek is unchanged: tapping any bar
            // seeks to that fraction of the song.
            val bars = 96
            val progressFrac = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
            val bg = PrismPaletteState.current.background
            val isLight = PrismPaletteState.current.isLight
            for (i in 0 until bars) {
                val level = waveformLevels.getOrNull(i) ?: 0f
                val h = size.height * 0.48f * level.coerceIn(0f, 1f)
                val x = size.width * (0.02f + i * (0.96f / (bars - 1)))
                drawLine(
                    color = waveformBarColor(i.toFloat() / bars, progressFrac, isLight, bg),
                    start = androidx.compose.ui.geometry.Offset(x, size.height / 2f - h),
                    end = androidx.compose.ui.geometry.Offset(x, size.height / 2f + h),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatMs(position), fontSize = 16.sp, color = PrismOnSurface)
            Spacer(modifier = Modifier.weight(1f))
            Text(formatMs(duration), fontSize = 16.sp, color = PrismOnSurface)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 1.2.0: controls were cramped in a tight row on a huge
        // screen. Now they are spread across the full width with
        // breathing room between every button, and larger targets.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { vm.setShuffle(!shuffle) }, modifier = Modifier.size(80.dp)) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (shuffle) PrismAccentVisible else PrismOffIcon, modifier = Modifier.size(56.dp))
            }
            IconButton(onClick = { vm.playback.previous() }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = PrismAccentVisible, modifier = Modifier.size(80.dp))
            }
            // 1.1.4: play/pause was black on the dark-brown
            // surface and invisible. Now an amber disc with a
            // black icon on it — high contrast on any background.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(120.dp)
                    .background(PrismAmber, androidx.compose.foundation.shape.CircleShape)
                    .clickable { vm.playFromMiniPlayer() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(110.dp),
                )
            }
            IconButton(onClick = { vm.nextTrack() }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = PrismAccentVisible, modifier = Modifier.size(80.dp))
            }
            IconButton(onClick = { vm.cycleRepeat() }, modifier = Modifier.size(80.dp)) {
                Icon(
                    if (repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeat == 0) PrismOffIcon else PrismAccentVisible,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when (repeat) {
                1 -> "Repeat: all"
                2 -> "Repeat: one"
                else -> "Repeat: off"
            } + "  •  Shuffle: " + if (shuffle) "on" else "off",
            fontSize = 16.sp, color = PrismOnSurface,
        )
        // 1.5.6 — Eric, 2026-10-27 05:42: a download button
        // in the main player, and thumbs up / thumbs down
        // to curate the queue. Thumbs live on-device only
        // (never sent to Plex); a thumbs up asks whether to
        // also download that song; a thumbs down keeps the
        // track out of recommendations and queue fills.
        // Download text colour is explicit (PrismOnSurface),
        // NOT the OutlinedButton default — that default is
        // the theme primary, which in Now Playing colours
        // mode IS the artwork accent, and that is what made
        // Settings button text unreadable on red artwork.
        Spacer(modifier = Modifier.height(12.dp))
        val liked by vm.likedKeys.collectAsState()
        val disliked by vm.dislikedKeys.collectAsState()
        val promptKey by vm.downloadPromptKey.collectAsState()
        val downloadingKey by vm.downloadingKey.collectAsState()
        val failedKey by vm.downloadFailedKey.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { vm.thumbUp(track) }, modifier = Modifier.size(80.dp)) {
                Icon(Icons.Filled.ThumbUp, contentDescription = "Thumbs up", tint = if (track.key in liked) PrismAmber else PrismOffIcon, modifier = Modifier.size(56.dp))
            }
            IconButton(onClick = { vm.thumbDown(track) }, modifier = Modifier.size(80.dp)) {
                Icon(Icons.Filled.ThumbDown, contentDescription = "Thumbs down", tint = if (track.key in disliked) PrismAmber else PrismOffIcon, modifier = Modifier.size(56.dp))
            }
            val downloaded = vm.isUserDownloaded(track.key)
            OutlinedButton(
                onClick = { vm.requestDownload() },
                enabled = !downloaded && downloadingKey != track.key,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrismOnSurface),
            ) {
                Text(
                    when {
                        downloaded -> "Downloaded to car"
                        downloadingKey == track.key -> "Downloading…"
                        failedKey == track.key -> "Download failed — retry"
                        else -> "Download to car"
                    },
                    fontSize = 18.sp, color = PrismOnSurface,
                )
            }
        }
        // 1.5.8 DOWNLOAD POP-UP — Eric, 2026-10-27
        // 08:15 + 08:20: the download request must be a
        // pop-up that takes over the MIDDLE of the screen
        // at a good size, with BIG buttons saying "Yes
        // please" and "Hell no" — not a small thing down
        // at the bottom, and not the old inline Yes/No
        // row. So this is a custom centred Dialog, not a
        // stock AlertDialog (whose buttons are small
        // text buttons): a wide card in the centre of
        // the screen, 28sp title, 20sp body, and two
        // full-width 76dp buttons. It fires from two
        // places: the Download button (which now only
        // ASKS, it never downloads on a bare tap) and a
        // thumbs up on a song the user has not
        // downloaded yet. "Yes please" downloads via
        // downloadCurrentTrack; "Hell no" just closes
        // the pop-up, no download, no state changed.
        if (promptKey == track.key) {
            Dialog(onDismissRequest = { vm.dismissDownloadPrompt() }) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = CardDefaults.cardColors(containerColor = PrismSurface),
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Download this song?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Download \"${track.title}\" by ${track.artistTitle} to the car for offline playback?",
                                fontSize = 20.sp, color = PrismOnSurface,
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { vm.downloadCurrentTrack() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                                colors = ButtonDefaults.buttonColors(contentColor = Color.Black, containerColor = PrismAmber),
                            ) {
                                Text("Yes please", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { vm.dismissDownloadPrompt() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrismOnSurface),
                            ) {
                                Text("Hell no", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(vm: PrismViewModel, onBack: () -> Unit, onNowPlaying: () -> Unit) {
    val queue by vm.playback.queue.collectAsState()
    val current by vm.playback.currentIndex.collectAsState()
    val shuffle by vm.playback.shuffle.collectAsState()
    val recent by vm.recentlyPlayed.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text(if (shuffle) "Up Next • Shuffled" else "Up Next", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        if (queue.isEmpty()) {
            item { Text("Queue is empty. Play something first.", fontSize = 20.sp, color = PrismOnSurface) }
        } else {
            // 1.2.0: the next 100 songs after the current one,
            // not the whole queue from the start.
            // 1.3.9: in ACTUAL playback order — with shuffle on
            // that is the player's shuffled sequence, not the
            // natural queue order, and tapping a row plays that
            // row's real queue index, not its display position.
            val nextIndices = vm.playback.upNextIndices(100)
            val next100 = nextIndices.mapNotNull { queue.getOrNull(it) }
            if (next100.isEmpty()) {
                item { Text("Nothing up next — this is the last song in the queue.", fontSize = 20.sp, color = PrismOnSurface) }
            } else {
                itemsIndexed(next100) { offset, track ->
                    val index = nextIndices[offset]
                    TrackRow(
                        track, vm.artworkUrl(track.thumb),
                        onClick = { vm.playback.playIndex(index); onNowPlaying() },
                        playing = index == current,
                    )
                }
            }
        }
        item {
            if (recent.isNotEmpty()) {
                Text("Recently played", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                recent.forEach { track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        // 1.2.3: play from the whole recent list, not
                        // a single-track queue — a single track leaves
                        // Up Next empty and Next as a dead button.
                        vm.playTracks(recent, recent.indexOf(track)); onNowPlaying()
                    })
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: PrismViewModel, onBack: () -> Unit, onCrashLogs: () -> Unit = {}, onWhatsNew: () -> Unit = {}, onDownloads: () -> Unit = {}) {
    val safeArea by vm.safeAreaFraction.collectAsState()
    val quality by vm.audioQuality.collectAsState()
    val lib by vm.library.collectAsState()
    // 1.5.6 SETTINGS SECTIONS — Eric, 2026-10-27 05:42:
    // server, palette, audio and safe area must each be
    // their OWN section with a button that opens it, not
    // everything laid out at once. All four start
    // collapsed; the button always shows the current
    // choice, so nothing important is hidden.
    var serverOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var paletteOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var audioOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var safeAreaOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }

        item {
            BigButton("Server — ${lib.selectedServer?.name ?: "Choose server"}") { serverOpen = !serverOpen }
        }
        if (serverOpen) {
            item { Text("Server", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            itemsIndexed(lib.servers) { _, server ->
                OutlinedButton(
                    onClick = { vm.selectServer(server) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
                ) {
                    Text(
                        (if (server.id == lib.selectedServer?.id) "✓ " else "") + server.name,
                        fontSize = 18.sp,
                        // 1.5.6: button text MUST be explicit here.
                        // The Material default is the theme primary,
                        // which in Now Playing colours mode IS the
                        // artwork accent — red text on a red
                        // background, unreadable in Eric's photo.
                        color = if (server.id == lib.selectedServer?.id) PrismAmber else PrismOnSurface,
                    )
                }
            }
            item {
                Text(
                    "Connection: ${lib.serverBase ?: "not connected"}",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        item {
            val paletteName by vm.paletteName.collectAsState()
            val paletteMode by vm.paletteMode.collectAsState()
            BigButton("Colour palette — $paletteName • ${if (paletteMode == "nowplaying") "Now Playing colours" else "Fixed palette"}") { paletteOpen = !paletteOpen }
        }
        if (paletteOpen) {
        item { Text("Colour palette", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val paletteName by vm.paletteName.collectAsState()
            val paletteMode by vm.paletteMode.collectAsState()
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    com.fourgeailabs.prismtune.ui.PrismPalettes.forEach { p ->
                        OutlinedButton(
                            onClick = { vm.setPalette(p.name) },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                        ) {
                            Text(p.name, fontSize = 15.sp, color = if (p.name == paletteName) PrismAmber else PrismOnSurface)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.setPaletteMode("fixed") },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Fixed palette", fontSize = 16.sp, color = if (paletteMode == "fixed") PrismAmber else PrismOnSurface) }
                    OutlinedButton(
                        onClick = { vm.setPaletteMode("nowplaying") },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Now Playing colours", fontSize = 16.sp, color = if (paletteMode == "nowplaying") PrismAmber else PrismOnSurface) }
                }
                // 1.5.7 — Eric, 2026-10-27 07:28: the
                // colourway on screen right now is one he
                // wants to keep. "Keep current colours"
                // snapshots PrismPaletteState.current
                // exactly as it looks — including a Now
                // Playing artwork derivation — stores it,
                // and switches to it as a fixed palette
                // named "Kept", so the next track's artwork
                // can no longer change it, and it survives
                // restarts. Picking a built-in palette or
                // Now Playing colours again replaces it
                // only when he taps Keep again.
                OutlinedButton(
                    onClick = { vm.keepCurrentPalette(com.fourgeailabs.prismtune.ui.PrismPaletteState.current) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
                ) {
                    Text(
                        if (paletteName == "Kept") "✓ Kept colours — this is your saved colourway" else "Keep current colours",
                        fontSize = 16.sp,
                        color = if (paletteName == "Kept") PrismAmber else PrismOnSurface,
                    )
                }
                Text(
                    "Fixed = the palette above always, background never changes. Now Playing colours = the accent AND the background follow the current track's album art (darkened so text stays readable). If your background never changes, you are on Fixed — pick Now Playing colours here. Matte finish, no gradient wash.",
                    fontSize = 15.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        }

        item {
            BigButton("Audio quality — ${if (quality == "Original") "Original" else "$quality kbps"}") { audioOpen = !audioOpen }
        }
        if (audioOpen) {
        item { Text("Audio quality", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Original", "320", "192", "128").forEach { q ->
                    OutlinedButton(
                        onClick = { vm.setAudioQuality(q) },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) {
                        Text(if (q == "Original") "Original" else "$q kbps", fontSize = 15.sp, color = if (q == quality) PrismAmber else PrismOnSurface)
                    }
                }
            }
            Text(
                "Original = direct play from your server. Lower values transcode " +
                    "for weaker connections. Applies to the next track you start.",
                fontSize = 15.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        }

        item {
            BigButton("Equinox EV safe area — ${(safeArea * 100).roundToInt()}%") { safeAreaOpen = !safeAreaOpen }
        }
        if (safeAreaOpen) {
        item { Text("Equinox EV safe area", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Interactive controls use the left ${(safeArea * 100).roundToInt()}% of the screen. " +
                    "The rest is artwork only, so no button ever sits in the " +
                    "top-right corner of the display. Default is 80% " +
                    "(photo-calibrated, keeping a margin before that corner).",
                fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item {
            Slider(
                value = safeArea,
                onValueChange = { vm.setSafeAreaFraction(it) },
                valueRange = SafeAreaConfig.MIN_FRACTION..SafeAreaConfig.MAX_FRACTION,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            )
        }
        item {
            Row {
                Text("55%", fontSize = 16.sp, color = PrismOnSurface)
                Spacer(modifier = Modifier.weight(1f))
                Text("80% (default)", fontSize = 16.sp, color = PrismAmber)
                Spacer(modifier = Modifier.weight(1f))
                Text("85% (max)", fontSize = 16.sp, color = PrismOnSurface)
            }
        }
        }

        item { Text("Offline Top Picks", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val off by vm.offline.collectAsState()
            val autoOff by vm.autoOffline.collectAsState()
            Column {
                Text(
                    "Auto-builds a playlist of your most-played and highly rated songs, " +
                        "albums, and playlists, and downloads it for offline playback. " +
                        "Downloads may run on mobile data as well as Wi-Fi.",
                    fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(
                    onClick = { vm.setAutoOffline(!autoOff) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (autoOff) PrismAmber else PrismSurface,
                        contentColor = if (autoOff) Color.Black else PrismOnSurface,
                    ),
                ) { Text(if (autoOff) "Auto offline: ON" else "Auto offline: OFF", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Text(
                    "${off.tracks.size} tracks — ${off.downloadedCount} downloaded — " +
                        "${"%.1f".format(off.storageBytes / 1048576.0)} MB on device",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
                if (off.downloading) {
                    Text(
                        "Downloading… ${off.downloadIndex} of ${off.downloadTotal}",
                        fontSize = 16.sp, color = PrismAmber, modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                off.error?.let { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(vertical = 4.dp)) }
                off.emptyReason?.let { Text(it, fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 4.dp)) }
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.refreshOfflineTopPicks() },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Refresh now", fontSize = 16.sp, color = PrismOnSurface) }
                    OutlinedButton(
                        onClick = { onDownloads() },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Manage downloads", fontSize = 16.sp, color = PrismOnSurface) }
                }
            }
        }

        item { Text("Curated by Me!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            // 1.5.8 RESETS, Eric 2026-10-27 08:44: two
            // SEPARATE resets — likes and dislikes
            // each get their own button — not one
            // combined wipe. Each is two-tap confirm,
            // same pattern as Delete all downloads, so
            // a stray tap in the car cannot wipe it.
            // Resetting likes empties the card (fills
            // derive from likes); resetting dislikes
            // lets banned songs back in. Downloads,
            // queue and Recently Played untouched.
            var confirmLikes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            var confirmDislikes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val likedCount by vm.likedKeys.collectAsState()
            val dislikedCount by vm.dislikedKeys.collectAsState()
            Column {
                Text(
                    "Your thumbs up and thumbs down build the Curated by Me! card on Home and steer " +
                        "the Recommended playlist. Reset either side on its own — clearing likes empties " +
                        "the card until you thumb up more songs, clearing dislikes lets banned songs back in.",
                    fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    "${likedCount.size} liked — ${dislikedCount.size} disliked",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        if (confirmLikes) {
                            vm.resetLikedSongs()
                            confirmLikes = false
                        } else {
                            confirmLikes = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 6.dp),
                ) {
                    Text(
                        if (confirmLikes) "Tap again to reset liked songs" else "Reset liked songs",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (confirmLikes) Color(0xFFFF6B6B) else PrismOnSurface,
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (confirmDislikes) {
                            vm.resetDislikedSongs()
                            confirmDislikes = false
                        } else {
                            confirmDislikes = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 6.dp),
                ) {
                    Text(
                        if (confirmDislikes) "Tap again to reset disliked songs" else "Reset disliked songs",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (confirmDislikes) Color(0xFFFF6B6B) else PrismOnSurface,
                    )
                }
            }
        }

        item { Text("Diagnostics", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            // 1.4.0: no-reinstall recovery. If a saved queue is
            // what bricks launch after a play, this wipes it
            // WITHOUT signing out, so you do not lose your
            // Plex sign-in, server, or library cache.
            Column {
                Text("Saved queue recovery", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text("If the app ever crashes on launch after playing, tap this to clear the saved queue without signing out or reinstalling. Your sign-in stays.", fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(
                    onClick = { vm.clearSavedQueue() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
                ) { Text("Clear saved queue (keep sign-in)", fontSize = 16.sp, color = PrismOnSurface) }
            }
        }
        item {
            // 1.5.6: crash logs live behind a button now —
            // Eric, 2026-10-27 05:42: no more stack traces
            // free-falling through Settings. The full
            // history + diagnostics live on CrashLogsScreen.
            BigButton("Open crash logs") { onCrashLogs() }
        }
        item { Text("About", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "PrismTune 1.5.9 — an unofficial, audio-only Plex music player for " +
                    "Android Automotive. Not affiliated with or endorsed by Plex Inc. " +
                    "No video playback, no password entry in the car, and no controls " +
                    "in the top-right gauge zone. Gapless playback is a best-effort " +
                    "ExoPlayer behaviour and depends on the server's files.",
                fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item { AboutLinks() }

        item { Text("What's New", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item { BigButton("What's New") { onWhatsNew() } }

        // 1.5.6: Sign out is LAST in Settings, at Eric's
        // request — nothing sits below it to tap by mistake
        // after signing out.
        item { Text("Account", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Signed in with a Plex link token, stored encrypted on this car. " +
                    "Signing out wipes the token.",
                fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item { BigButton("Sign out") { vm.signOut() } }
    }
}
