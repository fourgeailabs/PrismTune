package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourgeailabs.prismtune.data.FeelsScope
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.data.filterTracks
import com.fourgeailabs.prismtune.data.glossColorsFor
import com.fourgeailabs.prismtune.data.groupByAlbum
import com.fourgeailabs.prismtune.data.groupByArtist
import com.fourgeailabs.prismtune.data.groupByGenre
import com.fourgeailabs.prismtune.data.pickLucky
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel

/**
 * GlossyCard (1.1.4) — the ONE card finish used for Genre, Mood, Vibe,
 * Artist, Track, and Album cards alike. Eric, 2026-10-26: the old
 * gradient was muted and watercolory. New finish: a MATTE base
 * (flat, darkened palette colour, no gradient wash) with a
 * SPARKLE / glisten layer on top — deterministic white and gold
 * glints, plus a narrow top gloss band. Same name = same matte
 * colour and same sparkle pattern, neighbours usually differ.
 * Rounded 24dp corners, >=76dp touch target.
 */
@Composable
fun GlossyCard(
    name: String,
    subtitle: String = "",
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (topLong, bottomLong) = glossColorsFor(name)
    val accent = Color(bottomLong)
    val top = Color(topLong)
    // Matte base: palette colour darkened to a flat matte, no gradient.
    val matte = Color(
        red = top.red * 0.38f, green = top.green * 0.38f, blue = top.blue * 0.38f, alpha = 1f,
    )
    // 1.5.0 MATTE CARDS: Eric, 2026-10-27 — the
    // accented border + sparkle + gloss band looked
    // old-fashioned and ugly. Now: flat matte, NO border
    // (selected = a thin white underline via text colour
    // change only, handled below by the ▶ prefix), NO
    // sparkle, NO gloss, NO shadow, small 12dp radius.
    // Height is a third taller than before (min ~100dp
    // vs 76dp MinTouch) and width is half (4 per row in
    // FeelCardGrid below), so more cards fit per page.
    val shape = RoundedCornerShape(12.dp)
    val textColor = Color.White
    Box(
        modifier = modifier
            .heightIn(min = 100.dp)
            .clip(shape)
            .clickable { onClick() }
            .background(matte),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (selected) "▶ $name" else name,
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 16.sp, color = textColor.copy(alpha = 0.85f))
            }
        }
    }
}

private data class FeelCard(val name: String, val count: Int?, val kind: String)

@Composable
private fun FeelCardGrid(
    cards: List<FeelCard>,
    selected: Set<String>,
    onToggle: (FeelCard) -> Unit,
) {
    if (cards.isEmpty()) return
    // 1.5.4 PLEX AMP GRID, PRISMTUNE THEME — Eric,
    // 2026-10-27 04:52, with Plex Amp's Moods page as the
    // model: a plain scrolling grid, NO pagination, NO
    // Prev/Next pages. Cards are the existing matte
    // PrismTune cards (flat, borderless, name + track
    // count — SONGS, not Plex Amp's albums), just laid
    // out like Plex Amp: 2 columns on a narrow phone
    // screen, 3 columns on the car's wide screen, and
    // you scroll down through the rows. The outer
    // LazyColumn does the scrolling, so this grid renders
    // ALL cards in rows, not one page at a time.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 600.dp) 2 else 3
        Column {
            cards.chunked(columns).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach { card ->
                        GlossyCard(
                            name = card.name,
                            // 1.5.4: a server-list term with no
                            // scan count yet shows no count at
                            // all — never "0 tracks", which
                            // would be a lie about a real mood.
                            subtitle = card.count?.let { "$it tracks" } ?: "Tap to play",
                            selected = card.name in selected,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            onClick = { onToggle(card) },
                        )
                    }
                    repeat((columns - row.size).coerceAtLeast(0)) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun FeelsScreen(
    vm: PrismViewModel,
    onNowPlaying: () -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val lib by vm.library.collectAsState()
    // 1.3.2 MOODS FIX: the old LaunchedEffect(Unit) ran exactly
    // once, on first composition. If Moods was opened before
    // the library finished loading, serverBase was still null,
    // loadMoodsIncrementally returned silently, and the screen
    // NEVER retried — blank Moods forever, even after Home
    // loaded. Now the key is the server base + section, so
    // the load retries automatically when the library becomes
    // ready, and a manual Retry button covers any other stall.
    LaunchedEffect(lib.serverBase, lib.sections.firstOrNull()?.key) {
        if (!lib.moodsLoading) vm.loadMoodsIncrementally()
    }
    DisposableEffect(Unit) { onDispose { vm.cancelMoodsLoad() } }
    val tracks = lib.allTracks

    var selectedGenres by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedMoods by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedVibes by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 1.5.4 MOODS SEARCH — Eric, 2026-10-27 04:52: the Moods
    // tab needs a search bar for finding a mood, like Plex
    // Amp's list but searchable. Typing filters the mood
    // cards below (and the vibe/genre cards too, same
    // query) by case-insensitive substring, as you type —
    // no search button, no separate results page.
    var moodSearch by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(FeelsScope.TRACK) }
    var onlyShow by remember { mutableStateOf(true) }

    // 1.1.3: NEVER recompute counts over the whole growing list per
    // chunk on the main thread — that quadratic recompute froze
    // then crashed Moods at 10k+ tracks. Use the ViewModel's
    // per-chunk accumulator counts, which are O(chunk) per chunk.
    val counts = Triple(lib.moodsGenreCounts, lib.moodsMoodCounts, lib.moodsVibeCounts)
    // 1.1.3: filtering runs on Dispatchers.Default, not during
    // composition on the main thread. No selection = the list
    // itself, no copy, no scan at all.
    var filtered by remember { mutableStateOf<List<PlexTrack>>(tracks) }
    LaunchedEffect(tracks, selectedGenres, selectedMoods, selectedVibes, onlyShow) {
        filtered = if (selectedGenres.isEmpty() && selectedMoods.isEmpty() && selectedVibes.isEmpty()) {
            tracks
        } else {
            withContext(Dispatchers.Default) {
                filterTracks(tracks, selectedGenres, selectedMoods, selectedVibes, onlyShow)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("Moods", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
            Text(
                "Plex's own mood list for your library — counts are tracks, not albums, and no mood is guessed from genre. Tap a mood and it starts playing right away.",
                fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
            )
            androidx.compose.material3.OutlinedTextField(
                value = moodSearch,
                onValueChange = { moodSearch = it },
                label = { Text("Search moods") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                trailingIcon = {
                    if (moodSearch.isNotBlank()) {
                        androidx.compose.material3.TextButton(onClick = { moodSearch = "" }) { Text("Clear") }
                    }
                },
            )
            lib.moodsPlayingFeel?.let {
                Text(
                    "Loading $it…",
                    fontSize = 20.sp, color = PrismAmber, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (lib.moodsLoading) {
            item {
                LoadingVinyl(size = 120.dp)
                Text(
                    if (lib.moodsTotalCount != null) "Loading moods… ${lib.moodsLoadedCount} of ${lib.moodsTotalCount} tracks"
                    else "Loading moods… ${lib.moodsLoadedCount} tracks so far",
                    fontSize = 20.sp, color = PrismAmber,
                )
            }
        }
        // Non-fatal chunk error: cards already loaded stay on screen.
        lib.moodsError?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        // 1.2.3: do NOT hide restored Moods counts just because
        // allTracks is empty in memory. After a background quit,
        // tracks are wiped but counts are on disk and restored
        // by loadMoodsIncrementally — the old early return showed
        // "Sign in and load your library" even with counts ready,
        // and tapping a card plays via the slow path without
        // allTracks. Only show the sign-in message when there
        // are truly no counts at all.
        val countsEmpty = counts.first.isEmpty() && counts.second.isEmpty() && counts.third.isEmpty() &&
            lib.serverMoods.isEmpty() && lib.serverVibes.isEmpty()
        // 1.3.6: Moods cards MUST render from counts even when
        // tracks is empty in memory. Eric, 2026-10-26 23:28:
        // Moods page was blank — header only, no cards, no
        // loading, no retry — while a track was playing on the
        // right. That is exactly this state: counts were
        // restored from disk, tracks were empty (background
        // quit wipes them), so the early return below was
        // skipped (counts not empty), AND the card section
        // below was skipped (tracks empty). Header only.
        // Fix: cards render from counts regardless of tracks;
        // only the Results / Lucky / scope section needs tracks.
        if (!lib.moodsLoading && tracks.isEmpty() && countsEmpty) {
            item {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Text(
                        if (lib.serverBase == null) "Loading your library… Moods will appear once it finishes."
                        else "Moods didn't load yet.",
                        fontSize = 20.sp, color = PrismOnSurface,
                    )
                    if (lib.serverBase != null) {
                        androidx.compose.material3.Button(
                            onClick = { vm.loadMoodsIncrementally() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 8.dp),
                        ) {
                            Text("Retry loading moods", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            return@LazyColumn
        }
        // 1.3.6: cards from counts, even with no tracks in memory.
        // Tapping a card plays via playFeel's slow path, which
        // scans the server — it does not need allTracks.
        // 1.5.4: Plex Amp orders its Moods alphabetically, not
        // by count, and the search box filters by name — so
        // these grids are alphabetical too, and the search
        // query above filters all three sections.
        val searchLower = moodSearch.trim().lowercase()
        fun matchesSearch(name: String): Boolean =
            searchLower.isBlank() || name.lowercase().contains(searchLower)
        // 1.5.4 SERVER LIST UNION: Plex's own mood/style
        //  terms come first as the list source, scan counts
        //  fill in counts where the scan saw that term, and
        //  scan-only terms are kept too — so a failed server
        //  probe degrades to exactly the old page, and a
        //  successful probe can only ADD real terms, never
        //  hide a scan term. Count is null (no count shown)
        //  for a server term the scan hasn't counted yet.
        val moodCards = (lib.serverMoods + counts.second.keys)
            .distinctBy { it.lowercase() }
            .filter { matchesSearch(it) }
            .sortedBy { it.lowercase() }
            .map { FeelCard(it, counts.second[it], "mood") }
        val vibeCards = (lib.serverVibes + counts.third.keys)
            .distinctBy { it.lowercase() }
            .filter { matchesSearch(it) }
            .sortedBy { it.lowercase() }
            .map { FeelCard(it, counts.third[it], "vibe") }
        val genreCards = counts.first.entries
            .filter { matchesSearch(it.key) }
            .sortedBy { it.key.lowercase() }
            .map { FeelCard(it.key, it.value, "genre") }
        if (!lib.moodsLoading && moodCards.isEmpty() && vibeCards.isEmpty() && genreCards.isEmpty() && searchLower.isNotBlank()) {
            item {
                Text(
                    "No moods match \"$moodSearch\".",
                    fontSize = 20.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        if (!lib.moodsLoading && tracks.isNotEmpty() && counts.second.isEmpty() && counts.third.isEmpty() && lib.serverMoods.isEmpty() && lib.serverVibes.isEmpty()) {
            item {
                Text(
                    "No Plex mood or style tags were found on your loaded songs, so there are no mood cards to show. This is honest, not a bug — moods are no longer guessed from genre.",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        if (moodCards.isNotEmpty()) {
            item { Text("Moods", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            item {
                FeelCardGrid(moodCards, selectedMoods) { card ->
                    selectedMoods = setOf(card.name)
                    vm.playFeel("mood", card.name) { onNowPlaying() }
                }
            }
        }
        if (vibeCards.isNotEmpty()) {
            item { Text("Vibes", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            item {
                FeelCardGrid(vibeCards, selectedVibes) { card ->
                    selectedVibes = setOf(card.name)
                    vm.playFeel("vibe", card.name) { onNowPlaying() }
                }
            }
        }
        if (genreCards.isNotEmpty()) {
            item { Text("Genres", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            item {
                FeelCardGrid(genreCards, selectedGenres) { card ->
                    selectedGenres = setOf(card.name)
                    vm.playFeel("genre", card.name) { onNowPlaying() }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item {
                // I'm Feeling Lucky: only ever from the CURRENT filtered set.
                GlossyCard(
                    name = "I'm Feeling Lucky",
                    subtitle = if (filtered.isEmpty()) "No tracks match — try fewer moods"
                    else "${filtered.size} tracks to choose from",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    onClick = {
                        val lucky = pickLucky(filtered) ?: return@GlossyCard
                        vm.playTracks(filtered, filtered.indexOf(lucky)); onNowPlaying()
                    },
                )
            }
            item {
                // Only Show toggle, with a plain explanation of what it does.
                Button(
                    onClick = { onlyShow = !onlyShow },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (onlyShow) PrismAmber else PrismSurface,
                        contentColor = if (onlyShow) Color.Black else PrismOnSurface,
                    ),
                ) {
                    Text(
                        if (onlyShow) "Only show: ON — tracks in all selected moods"
                        else "Only show: OFF — tracks in any selected mood",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            item {
                // Result scope chips: Artist, Genre, Track, Album.
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    FeelsScope.entries.forEach { s ->
                        OutlinedButton(
                            onClick = { scope = s },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(horizontal = 2.dp),
                        ) {
                            Text(
                                s.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 16.sp,
                                fontWeight = if (scope == s) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            item { BigButton("Search your library") { onSearch() } }
            // 1.3.6: cards are already rendered above, from
            // counts, even when tracks is empty — do not
            // render them a second time here.
            item {
                Text(
                    "Results — ${filtered.size} tracks",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "No tracks match those moods. Try fewer cards, or turn Only show off.",
                        fontSize = 18.sp, color = PrismOnSurface, modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                when (scope) {
                    FeelsScope.TRACK -> item {
                        PagedList(filtered) { track ->
                            GlossyCard(
                                name = track.title,
                                subtitle = listOf(track.artistTitle, track.albumTitle).filter { it.isNotBlank() }.joinToString(" • "),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                onClick = { vm.playTracks(filtered, filtered.indexOf(track)); onNowPlaying() },
                            )
                        }
                    }
                    FeelsScope.ARTIST -> item {
                        val groups = groupByArtist(filtered).entries.sortedBy { it.key }
                        PagedList(groups) { (artist, groupTracks) ->
                            GlossyCard(
                                name = artist, subtitle = "${groupTracks.size} tracks",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                onClick = { vm.playTracks(groupTracks, 0); onNowPlaying() },
                            )
                        }
                    }
                    FeelsScope.GENRE -> item {
                        val groups = groupByGenre(filtered).entries.sortedBy { it.key }
                        PagedList(groups) { (genre, groupTracks) ->
                            GlossyCard(
                                name = genre, subtitle = "${groupTracks.size} tracks",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                onClick = { vm.playTracks(groupTracks, 0); onNowPlaying() },
                            )
                        }
                    }
                    FeelsScope.ALBUM -> item {
                        val groups = groupByAlbum(filtered).entries.sortedBy { it.key }
                        PagedList(groups) { (album, groupTracks) ->
                            GlossyCard(
                                name = album, subtitle = "${groupTracks.size} tracks",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                onClick = { vm.playTracks(groupTracks, 0); onNowPlaying() },
                            )
                        }
                    }
                }
            }
        }
    }
}
