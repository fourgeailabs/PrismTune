package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fourgeailabs.prismtune.data.SafeAreaConfig
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel
import kotlin.math.roundToInt

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
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

    // Scrollable for the same 4.875-inch-tall reason as SignInScreen.
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp),
    ) {
        TopBar("Now Playing", onBack = onBack)
        if (track == null) {
            Text("Nothing playing yet. Pick something from Home or Library.", fontSize = 20.sp, color = PrismOnSurface)
            return
        }
        val art = vm.artworkUrl(track.thumb)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                AsyncImage(model = art, contentDescription = "Album artwork", modifier = Modifier.size(160.dp))
            } else {
                PrismLogo(size = 160.dp)
            }
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(track.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text(track.artistTitle, fontSize = 20.sp, color = PrismAmber)
                Text(track.albumTitle, fontSize = 18.sp, color = Color(0xFFD8CFC0))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Seek: large slider, inside the safe (interactive) zone only.
        Slider(
            value = position.toFloat(),
            onValueChange = { vm.playback.seekTo(it.toLong()) },
            valueRange = 0f..maxOf(1f, duration.toFloat()),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatMs(position), fontSize = 16.sp, color = PrismOnSurface)
            Spacer(modifier = Modifier.weight(1f))
            Text(formatMs(duration), fontSize = 16.sp, color = PrismOnSurface)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setShuffle(!shuffle) }, modifier = Modifier.size(MinTouch)) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (shuffle) PrismAmber else Color.Gray)
            }
            IconButton(onClick = { vm.playback.previous() }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = PrismAmber, modifier = Modifier.size(64.dp))
            }
            IconButton(
                onClick = { vm.playback.togglePlayPause() },
                modifier = Modifier.size(120.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(96.dp),
                )
            }
            IconButton(onClick = { vm.playback.next() }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = PrismAmber, modifier = Modifier.size(64.dp))
            }
            IconButton(onClick = { vm.cycleRepeat() }, modifier = Modifier.size(MinTouch)) {
                Icon(
                    if (repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeat == 0) Color.Gray else PrismAmber,
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
    }
}

@Composable
fun QueueScreen(vm: PrismViewModel, onBack: () -> Unit, onNowPlaying: () -> Unit) {
    val queue by vm.playback.queue.collectAsState()
    val current by vm.playback.currentIndex.collectAsState()
    val recent by vm.recentlyPlayed.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar("Up Next", onBack = onBack) }
        if (queue.isEmpty()) {
            item { Text("Queue is empty. Play something first.", fontSize = 20.sp, color = PrismOnSurface) }
        } else {
            itemsIndexed(queue) { index, track ->
                TrackRow(
                    track, vm.artworkUrl(track.thumb),
                    onClick = { vm.playback.playIndex(index); onNowPlaying() },
                    playing = index == current,
                )
            }
        }
        item {
            if (recent.isNotEmpty()) {
                Text("Recently played", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                recent.forEach { track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        vm.playTracks(listOf(track), 0); onNowPlaying()
                    })
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: PrismViewModel, onBack: () -> Unit) {
    val safeArea by vm.safeAreaFraction.collectAsState()
    val quality by vm.audioQuality.collectAsState()
    val lib by vm.library.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar("Settings", onBack = onBack) }

        item { Text("Server", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        itemsIndexed(lib.servers) { _, server ->
            OutlinedButton(
                onClick = { vm.selectServer(server) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
            ) {
                Text(
                    (if (server.id == lib.selectedServer?.id) "✓ " else "") + server.name,
                    fontSize = 18.sp,
                )
            }
        }
        item {
            Text(
                "Connection: ${lib.serverBase ?: "not connected"}",
                fontSize = 16.sp, color = Color(0xFFD8CFC0), modifier = Modifier.padding(vertical = 8.dp),
            )
        }

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
                fontSize = 15.sp, color = Color(0xFFD8CFC0), modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        item { Text("Equinox EV safe area", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Interactive controls use the left ${(safeArea * 100).roundToInt()}% of the screen. " +
                    "The rest is artwork only, so no button ever sits under the top-right " +
                    "temperature gauge. Default is 80% (photo-calibrated; " +
                    "temp reads at ~89% across, so this keeps a margin).",
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

        item { Text("Account", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Signed in with a Plex link token, stored encrypted on this car. " +
                    "Signing out wipes the token.",
                fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item { BigButton("Sign out") { vm.signOut() } }

        item { Text("About", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "PrismTune 1.0.0 — an unofficial, audio-only Plex music player for " +
                    "Android Automotive. Not affiliated with or endorsed by Plex Inc. " +
                    "No video playback, no password entry in the car, and no controls " +
                    "in the top-right gauge zone. Gapless playback is a best-effort " +
                    "ExoPlayer behaviour and depends on the server's files.",
                fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
