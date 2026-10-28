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
 * 1.5.6 CRASH LOGS SCREEN — 2026-10-27 05:42:
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
        "Thumbs up and thumbs down now toggle. Tap a selected thumb again to unselect it and leave the song neutral. Un-liking doesn't pop the download prompt, and un-disliking lets a song back into Curated by Me! and Recommended.",
    ),
    WhatsNewEntry(
        "1.5.8", "Oct 27, 2026",
        "Downloads got reworked. Asking to download a song now opens a big pop-up in the middle of the screen — Yes please or Hell no — instead of a small row at the bottom. The Downloaded to car label only counts songs you actually asked for; songs that were only in the playback buffer no longer pretend to be downloads. Settings has a Manage downloads screen that lists every file, tagged Yours or Buffered, with a delete button per song and a delete-all (two taps, and the song currently playing is kept). New on Home: the Curated by Me! card, built from your thumbs up, with a thumbs-down button on each song to kick just that one out, and separate Reset liked songs / Reset disliked songs buttons in Settings.",
    ),
    WhatsNewEntry(
        "1.5.7", "Oct 27, 2026",
        "Fixed cold-boot play: pressing play on the mini player right after opening the app now plays the restored song instead of doing nothing. New in Settings: Keep current colours locks the colourway on screen as a fixed palette, so it survives restarts and track changes.",
    ),
    WhatsNewEntry(
        "1.5.6", "Oct 27, 2026",
        "Settings rebuilt as expandable sections, with crash logs and this What's New page behind their own buttons. The main player gained a Download button and thumbs up/down, smart playlists load properly instead of reporting empty, Recently Played now records every song the player lands on, and the Up Next list is in bigger type.",
    ),
    WhatsNewEntry(
        "1.5.5", "Oct 27, 2026",
        "Waveform played bars are now the same hue family as the screen background, not its complement — deep red screens get lighter red played bars, white unplayed bars unchanged, on Now Playing and the mini-player.",
    ),
    WhatsNewEntry(
        "1.5.4", "Oct 27, 2026",
        "Moods now come from your server's real Mood and Style tags, counted per song, with no genre guessing. Scrolling grid in the app's own theme, plus a search bar for moods.",
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
        "Now Playing fits on one screen, Up Next expands to 100 songs after resuming, and the mini-player waveform matches Now Playing, with tap-to-seek.",
    ),
    WhatsNewEntry(
        "1.5.0", "Oct 27, 2026",
        "Media-source listing fix, true dominant-colour palettes from artwork, exact-match mood counts, matte mood cards, and waveform fixes.",
    ),
    WhatsNewEntry(
        "1.4.2", "Oct 27, 2026",
        "Fixed the car launch loop. The app opens on the Equinox EV again, and the instrument cluster and steering-wheel controls work.",
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
 * 1.5.6 WHAT'S NEW PAGE — 2026-10-27 05:42:
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
 * 1.5.6 ABOUT LINKS — 2026-10-27 05:42: the About
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
 * 1.5.8 DOWNLOADS SCREEN — 2026-10-27 08:15:
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
