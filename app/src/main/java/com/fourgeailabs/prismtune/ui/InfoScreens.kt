package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel

/**
 * 1.5.6 CRASH LOGS SCREEN — Eric, 2026-10-27 05:42:
 * crash logs must NOT sit free-falling through the
 * bottom of Settings. Settings now has a single
 * "Open crash logs" button; this screen holds the
 * full history, diagnostics, and Copy/Share/Clear.
 * Logs stay on this device unless you tap Share.
 */
@Composable
fun CrashLogsScreen(vm: PrismViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var crashHistory by remember {
        mutableStateOf<List<com.fourgeailabs.prismtune.data.CrashLog.CrashEntry>>(
            com.fourgeailabs.prismtune.data.CrashLog.readHistory(context)
        )
    }
    val diags by vm.diagnostics.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Crash logs", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Crash logs stay on this device unless you share them. No automatic upload. Up to the last 20 crashes are kept, so a future crash never erases an earlier one.",
                fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (crashHistory.isEmpty()) {
            item {
                Text(
                    "No crashes recorded yet. If the app crashes, reopen it and come back here — the stack trace will be here.",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(crashHistory) { entry ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(entry.title, fontSize = 15.sp, color = PrismOnSurface)
                    Text(entry.body.take(1200), fontSize = 14.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(entry.fullText)) },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                        ) { Text("Copy this crash", fontSize = 16.sp, color = PrismOnSurface) }
                        OutlinedButton(
                            onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, entry.fullText)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "PrismTune crash log")
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Share crash log"))
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                        ) { Text("Share this crash", fontSize = 16.sp, color = PrismOnSurface) }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(crashHistory.joinToString("\n===== CRASH =====\n") { it.fullText })) },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Copy all crashes", fontSize = 16.sp, color = PrismOnSurface) }
                    OutlinedButton(
                        onClick = {
                            com.fourgeailabs.prismtune.data.CrashLog.clear(context)
                            crashHistory = emptyList()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Clear crash history", fontSize = 16.sp, color = PrismOnSurface) }
                }
            }
        }
        item { Text("App errors", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.padding(top = 16.dp)) }
        item {
            Text(
                "The last few app errors, with no tokens, passwords, or PINs. Copy and paste one instead of screenshotting a red banner.",
                fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (diags.isEmpty()) {
            item { Text("No errors recorded yet.", fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp)) }
        } else {
            items(diags) { d ->
                Text(
                    "${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(d.timeMs))} — ${d.call} ${d.endpointPath} status=${d.status ?: "?"}: ${d.message}",
                    fontSize = 15.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(com.fourgeailabs.prismtune.data.diagnosticsCopyText(diags))) },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Copy diagnostics", fontSize = 16.sp, color = PrismOnSurface) }
                    OutlinedButton(
                        onClick = { vm.clearDiagnostics() },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Clear", fontSize = 16.sp, color = PrismOnSurface) }
                }
            }
        }
    }
}

/** One What's New entry: version, date, and what changed, in plain language. */
private data class WhatsNewEntry(val version: String, val date: String, val body: String)

private val whatsNewEntries = listOf(
    WhatsNewEntry(
        "1.5.9", "Oct 27, 2026",
        "Thumbs up and thumbs down now toggle. Tap an already-selected thumb a second time and it unselects, leaving the song neutral — neither liked nor disliked — so a changed mind doesn't need a reset. Un-liking never pops the download prompt, and un-disliking lets the song back into Curated by Me! and Recommended.",
    ),
    WhatsNewEntry(
        "1.5.8", "Oct 27, 2026",
        "Downloads, fixed properly. The download request is now a big pop-up in the middle of the screen — not a row at the bottom — with big Yes please and Hell no buttons; it appears from the Download button (which now only asks, never downloads on a bare tap) and from a thumbs up. The Downloaded to car label now means YOU asked for it: before, it read mere file existence, so songs the playback buffer or Offline Top Picks had silently downloaded showed as downloaded even though you never asked. Settings > Manage downloads is a new menu listing every song file on the car, tagged Yours or Buffered, with a Delete button per song and a Delete all button (second tap to confirm; the song currently playing is kept so playback never breaks). New Home card: Curated by Me! — your liked songs first, then songs that sound like them, with thumbs-down songs never in it; thumbs up and down now also steer the Recommended playlist. The old Clear downloads button is replaced by Manage downloads. Settings also gains two separate resets — Reset liked songs and Reset disliked songs, each two taps to confirm — so you can throw away one side of the curation without touching the other, and every song listed in the Curated by Me! card gets its own thumbs-down button to kick just that song out.",
    ),
    WhatsNewEntry(
        "1.5.7", "Oct 27, 2026",
        "Cold-boot play fixed: pressing play on the mini player (or Now Playing) right after opening the app now actually plays the restored song, instead of doing nothing. The cause was the metadata-only cold-boot restore never telling the player the server connection, so play on an empty player was a silent no-op; the connection is now recorded on restore, and a tap made before the library finishes loading is honoured when the load lands (within 30 seconds). New in Settings > Colour palette: Keep current colours snapshots the exact colourway on screen right now — including a Now Playing artwork colourway — and locks it in as a fixed palette called Kept, so the next track's artwork can no longer change it and it survives restarts.",
    ),
    WhatsNewEntry(
        "1.5.6", "Oct 27, 2026",
        "Settings rebuilt: server, colour palette, audio quality and Equinox EV safe area are now their own expandable sections, sign out is at the very bottom, crash logs live behind an Open crash logs button, and this What's New page plus GitHub and Play Store links are in About. Mini-player play/pause is visible again (its icon tint was inverted), Settings button text stays readable on artwork backgrounds, and Recently Added is gone from Home. Smart playlists (Tracks, Fresh, Recently Played) now try every load path before reporting empty, and Home's Recently Played records every song the player lands on, not just manual plays. Main player gains a Download to car button and thumbs up / thumbs down: a thumbs up asks whether to also download that song, a thumbs down keeps the track out of recommendations and queue fills. The Up Next panel's Following list is now 20sp instead of 14sp small print, so it reads at a glance in the car.",
    ),
    WhatsNewEntry(
        "1.5.5", "Oct 27, 2026",
        "Waveform played bars are now the same hue family as the screen background, not its complement — deep red screens get lighter red played bars, white unplayed bars unchanged, on Now Playing and the mini-player.",
    ),
    WhatsNewEntry(
        "1.5.4", "Oct 27, 2026",
        "Moods now come from real Plex tags only, counted in songs not albums, with no genre guessing. New scrolling Plex Amp-style grid (2 columns on phone, 3 in the car, alphabetical) plus a mood search bar, and Plex's own server mood list as the primary source with scan terms as fallback.",
    ),
    WhatsNewEntry(
        "1.5.3", "Oct 27, 2026",
        "Waveform played bars turn their full colour immediately and march right as a song progresses, instead of fading slowly from white.",
    ),
    WhatsNewEntry(
        "1.5.2", "Oct 27, 2026",
        "All back buttons removed — the top navigation row is the only navigation. Light clickables on dark backgrounds, so buttons and icons stay visible on artwork backgrounds.",
    ),
    WhatsNewEntry(
        "1.5.1", "Oct 27, 2026",
        "Now Playing fits without scrolling, artwork background follows the track in Now Playing colours mode, Up Next expands to 100 after a cold-open resume, and the mini-player waveform matches Now Playing with tap-to-seek.",
    ),
    WhatsNewEntry(
        "1.5.0", "Oct 27, 2026",
        "Media-source listing fix, Now Playing fit, true dominant-colour palettes, exact-match mood counts, matte borderless mood cards, and waveform spectrum fixes.",
    ),
    WhatsNewEntry(
        "1.4.2", "Oct 27, 2026",
        "Fixed the car launch loop: browsable media items now declare themselves correctly, so the Equinox media browser no longer kills the app on open. Instrument cluster and steering-wheel controls work.",
    ),
    WhatsNewEntry(
        "1.3.x", "Oct 26, 2026",
        "Persistent metadata cache, shuffle-aware Up Next, crash history kept on the device, media-style notification and system media controls, and Plex-native mood filtering.",
    ),
    WhatsNewEntry(
        "1.2.x", "Oct 26, 2026",
        "Artwork palettes, foreground playback service, mini-player panel, Home reorder, car mute pauses playback, and the amber play/pause disc.",
    ),
    WhatsNewEntry(
        "1.1.x", "Oct 25–26, 2026",
        "Full metadata, mini player, pagination, server search, Moods (formerly Feels), live spectrum waveform, and Offline Top Picks from real data only.",
    ),
    WhatsNewEntry(
        "1.0.x", "Oct 25, 2026",
        "First internal releases: Plex sign-in (password sign-in alongside PIN), remote-first connections, and the driving-safe Android Automotive player.",
    ),
)

/**
 * 1.5.6 WHAT'S NEW PAGE — Eric, 2026-10-27 05:42:
 * an accordion-style update history, right here in the
 * app. Tap a version to expand it, tap again to close.
 */
@Composable
fun WhatsNewScreen() {
    var expanded by remember { mutableStateOf<String?>(whatsNewEntries.first().version) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("What's New", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Every PrismTune update, newest first. Tap a version to read what changed.",
                fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items(whatsNewEntries) { entry ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                OutlinedButton(
                    onClick = { expanded = if (expanded == entry.version) null else entry.version },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrismOnSurface),
                ) {
                    Text(
                        "${entry.version} — ${entry.date}  ${if (expanded == entry.version) "▲" else "▼"}",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface,
                    )
                }
                if (expanded == entry.version) {
                    Text(entry.body, fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

/**
 * 1.5.6 ABOUT LINKS — Eric, 2026-10-27 05:42: the About
 * section must link the GitHub repo, the Forge AI Labs
 * GitHub home, and the Play Store page, and name the
 * GitHub username. Used by SettingsScreen's About section.
 */
@Composable
fun AboutLinks() {
    val uriHandler = LocalUriHandler.current
    Column {
        Text("GitHub: fourgeailabs", fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedButton(
            onClick = { uriHandler.openUri("https://github.com/fourgeailabs/PrismTune") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
        ) { Text("Open GitHub repo", fontSize = 16.sp, color = PrismOnSurface) }
        OutlinedButton(
            onClick = { uriHandler.openUri("https://github.com/fourgeailabs") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
        ) { Text("Open Forge AI Labs on GitHub", fontSize = 16.sp, color = PrismOnSurface) }
        OutlinedButton(
            onClick = { uriHandler.openUri("https://play.google.com/store/apps/details?id=com.fourgeailabs.prismtune") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
        ) { Text("Open in Google Play Store", fontSize = 16.sp, color = PrismOnSurface) }
    }
}

/**
 * 1.5.8 DOWNLOADS SCREEN — Eric, 2026-10-27 08:15:
 * a menu in Settings to delete specific downloads, plus
 * a button that deletes all of them. Every file on disk
 * lists here, tagged "Yours" (the user asked for it via
 * the Download button or a thumbs-up "Yes please") or
 * "Buffered" (the app put it there itself — next-10
 * playback buffer or Offline Top Picks). Deleting one
 * removes just that file; Delete all removes every file
 * except the song currently playing, which is kept so
 * playback does not break mid-song, and the screen says
 * so. Delete all needs a second tap to confirm, so a
 * stray tap in the car cannot wipe the library.
 */
@Composable
fun DownloadsScreen(vm: PrismViewModel) {
    val downloads by vm.downloads.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshDownloads() }
    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1048576) "%.1f MB".format(bytes / 1048576.0)
        else "%.0f KB".format(bytes / 1024.0)
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Downloads", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            Text(
                "Every song file on this car. \"Yours\" means you asked for it; \"Buffered\" means the app put it there itself so it plays instantly. Deleting a file removes it from the car — a buffered song can come back the next time it plays, a song of yours stays gone until you download it again.",
                fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (downloads.isEmpty()) {
            item {
                Text(
                    "No downloads on this car yet. Download a song from the main player, or let the playback buffer and Offline Top Picks fill this in.",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            item {
                Text(
                    "${downloads.size} files — ${sizeLabel(downloads.sumOf { it.bytes })} on device",
                    fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item {
                BigButton(
                    if (confirmDeleteAll) "Tap again to delete all downloads" else "Delete all downloads",
                ) {
                    if (confirmDeleteAll) {
                        vm.deleteAllDownloads()
                        confirmDeleteAll = false
                    } else {
                        confirmDeleteAll = true
                    }
                }
            }
            item {
                Text(
                    "Delete all keeps the song currently playing, if any, so playback does not break mid-song.",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(downloads) { d ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                        Text(
                            "${d.artist} • ${if (d.isUserDownload) "Yours" else "Buffered"} • ${sizeLabel(d.bytes)}",
                            fontSize = 16.sp, color = PrismSecondary,
                        )
                    }
                    OutlinedButton(
                        onClick = { vm.deleteDownload(d.stem) },
                        modifier = Modifier.heightIn(min = 64.dp).padding(start = 8.dp),
                    ) { Text("Delete", fontSize = 16.sp, color = PrismOnSurface) }
                }
            }
        }
    }
}
