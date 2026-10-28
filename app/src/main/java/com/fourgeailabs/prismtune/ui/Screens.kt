package com.fourgeailabs.prismtune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fourgeailabs.prismtune.data.PlexAlbum
import com.fourgeailabs.prismtune.data.PlexArtist
import com.fourgeailabs.prismtune.data.formatDiagnostic
import com.fourgeailabs.prismtune.data.SignInBrowser
import com.fourgeailabs.prismtune.data.PlexPlaylist
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel
import com.fourgeailabs.prismtune.viewmodel.SignInState

/** Minimum touch target for every driving-safe control in this app. */
val MinTouch = 76.dp

/**
 * Inline back control, in-content only — NOT a top header strip. There is
 * deliberately no TopBar in this app: no safe-boundary badge, no clock,
 * no temperature/weather readout, no version banner, no cockpit/CI/Play
 * Store header, and no display-info strip at the top of any screen.
 * Navigation lives in the content itself (NavRow on Home, and this back
 * button only on screens that actually have a back action).
 */
@Composable
/**
 * 1.5.2 RETIRED — Eric, 2026-10-27: "Ditch all back
 * buttons. They are all redundant." The persistent top
 * NavRow (Home / Library / Moods / Search / Queue /
 * Settings) already navigates everywhere, so every
 * BackRow call site has been removed. This function is
 * kept only so old call sites still compile if one is
 * missed; it must not be used for new screens.
 */
fun BackRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouch)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PrismAmber)
        }
    }
}

@Composable
fun NavRow(onHome: () -> Unit, onLibrary: () -> Unit, onSearch: () -> Unit, onQueue: () -> Unit, onSettings: () -> Unit, onFeels: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavButton("Home", Icons.Filled.Home, onHome)
        NavButton("Library", Icons.Filled.LibraryMusic, onLibrary)
        NavButton("Moods", Icons.Filled.Star, onFeels)
        NavButton("Search", Icons.Filled.Search, onSearch)
        NavButton("Queue", Icons.Filled.QueueMusic, onQueue)
        NavButton("Settings", Icons.Filled.Settings, onSettings)
    }
}

@Composable
private fun NavButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    // 1.2.2: car-sized touch target, at least 76dp tall.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(12.dp).heightIn(min = MinTouch)) {
        Icon(icon, contentDescription = label, tint = PrismAccentVisible, modifier = Modifier.size(48.dp))
        Text(label, fontSize = 16.sp, color = PrismOnSurface)
    }
}

@Composable
fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrismAmber, contentColor = Color.Black),
    ) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TrackRow(track: PlexTrack, artworkUrl: String?, onClick: () -> Unit, playing: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (playing) Color(0xFF3A2A00) else PrismSurface),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artworkUrl != null) {
                AsyncImage(model = artworkUrl, contentDescription = null, modifier = Modifier.size(56.dp))
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAccentVisible, modifier = Modifier.size(56.dp))
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text(
                    listOf(track.artistTitle, track.albumTitle).filter { it.isNotBlank() }.joinToString(" • "),
                    fontSize = 16.sp, color = PrismSecondary,
                )
            }
            Text(track.durationLabel, fontSize = 16.sp, color = PrismOnSurface)
        }
    }
}

/**
 * Driving-safe paging: at most 6 rows per page, with large Prev / Next
 * buttons, instead of an endless scrolling list a driver has to hunt through.
 */
@Composable
fun <T> PagedList(
    items: List<T>,
    pageSize: Int = 6,
    emptyText: String = "Nothing here yet.",
    externalPage: Int? = null,
    onPageChange: ((Int) -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    var page by remember(items.size) { mutableStateOf(0) }
    val pages = maxOf(1, (items.size + pageSize - 1) / pageSize)
    // 1.1.0 A-Z jump: when the caller owns the page (Artists),
    // Prev/Next report back instead of using local state.
    val safePage = (externalPage ?: page).coerceIn(0, pages - 1)
    if (items.isEmpty()) {
        Text(emptyText, fontSize = 18.sp, color = PrismOnSurface, modifier = Modifier.padding(16.dp))
        return
    }
    val pageItems = items.drop(safePage * pageSize).take(pageSize)
    Column {
        pageItems.forEach { row(it) }
        if (pages > 1) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { if (onPageChange != null) onPageChange((safePage - 1).coerceAtLeast(0)) else page = (safePage - 1).coerceAtLeast(0) },
                    enabled = safePage > 0,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) { Text("← Prev", fontSize = 18.sp) }
                Text("Page ${safePage + 1} of $pages", fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(top = 18.dp))
                OutlinedButton(
                    onClick = { if (onPageChange != null) onPageChange((safePage + 1).coerceAtMost(pages - 1)) else page = (safePage + 1).coerceAtMost(pages - 1) },
                    enabled = safePage < pages - 1,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) { Text("Next →", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
fun SignInScreen(vm: PrismViewModel, state: SignInState, onDone: () -> Unit) {
    // Hoisted so they survive branch changes and recomposition: the
    // car-browser button lives in the Waiting branch, but its message
    // state must not reset just because the branch re-enters.
    val signInContext = LocalContext.current
    var browserMessage by remember { mutableStateOf<String?>(null) }
    // 1.0.8: username/password fields, hoisted so they survive
    // branch changes (e.g. an error returning to Idle/Error does
    // not wipe what was typed). The password lives only in this
    // composable state: it is passed to the ViewModel on tap,
    // never stored, never logged, and never shown in an error.
    var passwordLogin by remember { mutableStateOf("") }
    var passwordValue by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    // 1.0.5 connection diagnostic, collected here so the Idle/Error
    // branch can show it without touching any other screen.
    val connectionTest by vm.connectionTest.collectAsState()
    val connectionTesting by vm.connectionTesting.collectAsState()
    // Scrollable: on the Equinox EV's 4.875-inch-tall usable area, the code
    // + QR + logo stack does not fit in one fixed viewport, so this screen
    // scrolls inside the interactive pane instead of clipping.
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PrismLogo(size = 120.dp)
        Text("Your Plex music, in the car.", fontSize = 22.sp, color = PrismOnSurface)
        Spacer(modifier = Modifier.height(16.dp))
        when (state) {
            is SignInState.Idle, is SignInState.Error -> {
                if (state is SignInState.Error) {
                    Text(state.message, fontSize = 18.sp, color = Color(0xFFFF6B6B))
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    "Sign in the way you would on a TV: get a code here, " +
                        "then approve it on your phone. No password is ever typed in the car.",
                    fontSize = 18.sp, color = PrismOnSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                BigButton("Get link code") { vm.startSignIn() }
                Spacer(modifier = Modifier.height(12.dp))
                // 1.0.5: small diagnostic, not a sign-in attempt. It
                // runs DNS lookup, a plain plex.tv GET, and the real
                // PIN POST separately and shows which step fails, so
                // a PrismTune-only network failure can be pinned
                // down instead of reading as a rejected code. The
                // diagnostic PIN it creates is discarded, never shown.
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = !connectionTesting,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) {
                    Text(
                        if (connectionTesting) "Testing connection…" else "Test connection",
                        fontSize = 18.sp,
                    )
                }
                if (connectionTest != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    for (line in formatDiagnostic(connectionTest!!)) {
                        Text(line, fontSize = 16.sp, color = PrismOnSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                // 1.0.8: username/password sign-in, additional to
                // the PIN code flow above, which PIN tokens on
                // this account were rejected with 401 code 1001
                // on every server-list host. The password is
                // masked, is never stored, and only the returned
                // token is saved (encrypted, via TokenStore).
                // If Plex needs two-factor, it says so in its
                // own error message, and the optional code field
                // below is what to fill in before tapping again.
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Or sign in with your Plex username and password:",
                    fontSize = 18.sp, color = PrismOnSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = passwordLogin,
                    onValueChange = { passwordLogin = it },
                    label = { Text("Plex username or email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = passwordValue,
                    onValueChange = { passwordValue = it },
                    label = { Text("Plex password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = verificationCode,
                    onValueChange = { verificationCode = it },
                    label = { Text("Plex verification code (2FA, if enabled)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BigButton("Sign in with username and password") {
                    vm.signInWithPassword(passwordLogin, passwordValue, verificationCode)
                }
                Text(
                    "Your password is sent only to Plex and is never stored.",
                    fontSize = 14.sp, color = PrismOnSurface,
                )
            }
            is SignInState.PasswordSigningIn -> {
                LoadingVinyl(size = 160.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Signing in…", fontSize = 20.sp, color = PrismAmber)
            }
            is SignInState.Creating -> {
                // Loading state only: the vinyl spins clockwise here, and
                // stops the moment a real code (or an error) replaces it.
                LoadingVinyl(size = 160.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Getting your link code…", fontSize = 20.sp, color = PrismOnSurface)
            }
            is SignInState.Waiting -> {
                // Polling lives in the ViewModel, but if this screen
                // comes back (e.g. from the car browser) and polling
                // stopped, resume it for the SAME pin — never a new PIN
                // or code. If polling is still running this is a no-op.
                LaunchedEffect(state.code) { vm.resumeSignInPolling() }
                val context = signInContext
                Text("On your phone, open:", fontSize = 20.sp, color = PrismOnSurface)
                Text("plex.tv/link", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PrismAmber)
                Spacer(modifier = Modifier.height(12.dp))
                Text("…or scan this code with your phone camera:", fontSize = 20.sp, color = PrismOnSurface)
                Spacer(modifier = Modifier.height(12.dp))
                QrImage(state.qrBitmap)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Then enter this code:", fontSize = 18.sp, color = PrismOnSurface)
                Text(state.code, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = PrismAmber)
                Text("Waiting for approval… (up to 15 minutes)", fontSize = 16.sp, color = PrismOnSurface)
                Spacer(modifier = Modifier.height(12.dp))
                BigButton("Open sign-in in car browser") {
                    // Extra option, not a replacement for the phone QR.
                    // Opens plain plex.tv/link, preferring Chrome if it
                    // resolves on the car, otherwise any browser. If no
                    // browser resolves, show a plain message and leave
                    // the phone path working — never crash.
                    browserMessage = null
                    try {
                        val target = SignInBrowser.targetUrl()
                        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                            setPackage(SignInBrowser.CHROME_PACKAGE)
                        }
                        val resolved = if (chromeIntent.resolveActivity(context.packageManager) != null) {
                            chromeIntent
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(target))
                        }
                        if (resolved.resolveActivity(context.packageManager) != null) {
                            context.startActivity(resolved)
                        } else {
                            browserMessage = "No browser found in the car, use the phone QR instead."
                        }
                    } catch (e: Exception) {
                        browserMessage = "No browser found in the car, use the phone QR instead."
                    }
                }
                if (browserMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(browserMessage!!, fontSize = 16.sp, color = Color(0xFFFF6B6B))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.cancelSignIn() }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text("Cancel", fontSize = 18.sp)
                }
            }
            is SignInState.Done -> {
                Text("Signed in! Loading your music…", fontSize = 20.sp, color = PrismAmber)
                androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Unofficial app. Not affiliated with or endorsed by Plex Inc. " +
                "PrismTune is a separate product with its own name and logo.",
            fontSize = 13.sp, color = Color(0xFF9A8F80),
        )
    }
}

@Composable
private fun QrImage(bitmap: Bitmap?) {
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR code for Plex sign-in", modifier = Modifier.size(280.dp))
    } else {
        Text("QR code unavailable — tap Get link code for a fresh code.", fontSize = 16.sp, color = PrismOnSurface)
    }
}

@Composable
fun HomeScreen(
    vm: PrismViewModel,
    onLibrary: () -> Unit,
    onNowPlaying: () -> Unit,
    onQueue: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onFeels: () -> Unit = {},
    onPlaylistDetail: () -> Unit = {},
    onAlbumDetail: () -> Unit = {},
) {
    val lib by vm.library.collectAsState()
    val recentPlayed by vm.recentlyPlayed.collectAsState()
    val lastPlayedHome by vm.lastPlayed.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 1.2.2: NavRow is now persistent in MainActivity, above
        // the NavHost, so Home no longer draws its own copy —
        // otherwise Home would show two nav bars.
        item {
            Text(
                "Server: ${lib.selectedServer?.name ?: "—"}",
                fontSize = 18.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (lib.loading) {
            item {
                // Loading state only: spinning vinyl, replaced by the real
                // library (or an honest error) once loading finishes.
                LoadingVinyl(size = 120.dp)
                Text("Loading your music…", fontSize = 20.sp, color = PrismAmber)
            }
        }
        lib.connectionStatus?.let { item { Text(it, fontSize = 16.sp, color = PrismAmber) } }
        lib.error?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        // 1.2.0 Home order, at Eric's request: Recently Played
        // at the top, Playlists second, Recently Added at the
        // bottom — no more recently-added songs dominating the
        // top of the home screen.
        item { Text("Recently played", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        if (recentPlayed.isEmpty() && lastPlayedHome == null) {
            item { Text("Nothing played yet in PrismTune.", fontSize = 16.sp, color = PrismSecondary) }
        } else {
            items((recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }.take(6)) { track ->
                TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                    vm.playTracks((recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }, (recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }.indexOf(track)); onNowPlaying()
                })
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        // 1.2.1: Recently Played Moods, right below Recently
        // Played, then Recommended Moods and a Recommended
        // Playlist, all from real recent history only.
        item { Text("Recently played moods", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val recentList = (recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }
            val playedMoods = com.fourgeailabs.prismtune.data.recentlyPlayedMoods(recentList)
            if (playedMoods.isEmpty()) {
                Text("Play something first — moods from your recent tracks will appear here.", fontSize = 16.sp, color = PrismSecondary)
            } else {
                Column {
                    playedMoods.forEach { mood ->
                        MoodButton(mood) {
                            vm.playFeel(moodKindFor(mood), mood) { onNowPlaying() }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { Text("Recommended moods", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val recentList = (recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }
            val libraryTracks = (lib.allTracks + lib.tracks + lib.recent).distinctBy { it.key }
            val recMoods = com.fourgeailabs.prismtune.data.recommendedMoods(recentList, libraryTracks)
            if (recMoods.isEmpty()) {
                Text("Play some music first — recommendations based on your recent tracks, artists, and moods will appear here.", fontSize = 16.sp, color = PrismSecondary)
            } else {
                Column {
                    recMoods.forEach { mood ->
                        MoodButton(mood) {
                            vm.playFeel(moodKindFor(mood), mood) { onNowPlaying() }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { Text("Recommended playlist", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val recentList = (recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }
            val libraryTracks = (lib.allTracks + lib.tracks + lib.recent).distinctBy { it.key }
            // 1.5.8: liked songs count as "recent" signal for
            // recommendations — their artists and moods boost
            // similar tracks — and liked songs themselves are
            // excluded from this card, because they live in
            // "Curated by Me!" below instead of repeating.
            val likedKeysHome by vm.likedKeys.collectAsState()
            val likedOrdered = likedKeysHome.reversed().mapNotNull { k -> libraryTracks.firstOrNull { it.key == k } }
            val recTracks = com.fourgeailabs.prismtune.data.recommendedPlaylist((recentList + likedOrdered).distinctBy { it.key }, libraryTracks, disliked = vm.dislikedKeys.collectAsState().value)
            if (recTracks.isEmpty()) {
                Text("Play some music first — a playlist built from what you played recently.", fontSize = 16.sp, color = PrismSecondary)
            } else {
                Column {
                    Text("${recTracks.size} tracks, built from what you played recently.", fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp))
                    BigButton("Play recommended playlist") { vm.playTracks(recTracks, 0); onNowPlaying() }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { Text("Curated by Me!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val recentList = (recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }
            val libraryTracks = (lib.allTracks + lib.tracks + lib.recent).distinctBy { it.key }
            val likedKeysHome by vm.likedKeys.collectAsState()
            val dislikedHome by vm.dislikedKeys.collectAsState()
            // Newest liked first: the prefs order is oldest
            // first, so reverse it for the card's head.
            val likedOrdered = likedKeysHome.reversed().mapNotNull { k -> libraryTracks.firstOrNull { it.key == k } }
            val curated = com.fourgeailabs.prismtune.data.buildCuratedByMe(likedOrdered, libraryTracks, disliked = dislikedHome)
            if (curated.isEmpty()) {
                Text("Thumbs up songs you love and they'll build this playlist — the songs you actually want to hear all the time. Thumbs down keeps a song out, always.", fontSize = 16.sp, color = PrismSecondary)
            } else {
                Column {
                    Text("${curated.size} tracks, built from your thumbs up — your liked songs first, then songs that sound like them.", fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp))
                    BigButton("Play Curated by Me!") { vm.playTracks(curated, 0); onNowPlaying() }
                    // 1.5.8 PER-SONG REMOVAL, Eric
                    // 2026-10-27 08:44: every song in the
                    // card gets its own thumbs-down
                    // button, so one song can be kicked
                    // out without resetting anything.
                    // thumbDown removes a liked song
                    // from likes AND bans it, and bans
                    // a fill song — either way the card
                    // recomputes from the liked/disliked
                    // flows and the song leaves the list
                    // immediately. Tapping a row itself
                    // still just plays it.
                    Text("Tap 👎 on a song to take it out of this card.", fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp))
                    curated.forEach { track ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).clickable { vm.playTracks(curated, curated.indexOf(track)); onNowPlaying() }) {
                                Text(track.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                                Text(
                                    listOf(track.artistTitle, track.albumTitle).filter { it.isNotBlank() }.joinToString(" • "),
                                    fontSize = 16.sp, color = PrismSecondary,
                                )
                            }
                            IconButton(onClick = { vm.thumbDown(track) }, modifier = Modifier.size(MinTouch)) {
                                Icon(Icons.Filled.ThumbDown, contentDescription = "Remove ${track.title} from Curated by Me!", tint = PrismOffIcon, modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { Text("Playlists", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        items(lib.playlists.take(6)) { playlist ->
            PlaylistRow(playlist, vm.artworkUrl(playlist.thumb)) { vm.openPlaylist(playlist); onPlaylistDetail() }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { Text("Offline Top Picks", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val off by vm.offline.collectAsState()
            if (off.tracks.isEmpty()) {
                Text(
                    off.emptyReason ?: "Offline Top Picks will appear here once your library loads.",
                    fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Column {
                    Text(
                        "${off.tracks.size} tracks — ${off.downloadedCount} downloaded for offline playback",
                        fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
                    )
                    BigButton("Play Offline Top Picks") { vm.playTracks(off.tracks, 0); onNowPlaying() }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { BigButton("Browse all artists") { onLibrary() } }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        // 1.2.3: Recommended for you, ABOVE Recently Added, from
        // recently played tracks AND recently searched queries —
        // songs, albums, moods, and genres, so the app keeps
        // surfacing new things to play. Real library items only.
        item { Text("Recommended for you", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            val recentList = (recentPlayed + listOfNotNull(lastPlayedHome)).distinctBy { it.key }
            val libraryTracks = (lib.allTracks + lib.tracks + lib.recent).distinctBy { it.key }
            val searchHistory = vm.settings.searchHistory
            // 1.3.1: a recommendation bug must never crash Home
            // on boot — fall back to an empty recommendation.
            val rec = runCatching {
                com.fourgeailabs.prismtune.data.recommendedForYou(recentList, searchHistory, libraryTracks)
            }.getOrDefault(com.fourgeailabs.prismtune.data.RecommendedForYou())
            if (rec.songs.isEmpty() && rec.albums.isEmpty() && rec.moods.isEmpty() && rec.genres.isEmpty()) {
                Text("Play or search for some music first — recommendations based on what you played and searched will appear here.", fontSize = 16.sp, color = PrismSecondary)
            } else {
                Column {
                    if (rec.songs.isNotEmpty()) {
                        Text("Songs", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp))
                        rec.songs.take(6).forEach { track ->
                            TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                                vm.playTracks(rec.songs, rec.songs.indexOf(track)); onNowPlaying()
                            })
                        }
                    }
                    if (rec.albums.isNotEmpty()) {
                        Text("Albums", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp))
                        rec.albums.take(6).forEach { album ->
                            AlbumRow(album, vm.artworkUrl(album.thumb)) { vm.openAlbum(album); onAlbumDetail() }
                        }
                    }
                    if (rec.moods.isNotEmpty()) {
                        Text("Moods", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp))
                        rec.moods.forEach { mood ->
                            MoodButton(mood) { vm.playFeel(moodKindFor(mood), mood) { onNowPlaying() } }
                        }
                    }
                    if (rec.genres.isNotEmpty()) {
                        Text("Genres", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp))
                        rec.genres.forEach { genre ->
                            MoodButton(genre) { vm.playFeel("genre", genre) { onNowPlaying() } }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        // 1.5.6: Recently Added REMOVED from Home at Eric's
        // request, 2026-10-27 05:42 — "let's get rid of
        // Recently Added on the home screen". The albums
        // are still in the Library; they just no longer
        // take a whole Home section.
    }
}

/** 1.2.2: mood kind for tap-to-play — taxonomy moods first,
 *  then vibes, otherwise genre. Pure, unit-testable. */
fun moodKindFor(name: String): String = when {
    name in com.fourgeailabs.prismtune.data.FEEL_MOODS -> "mood"
    name in com.fourgeailabs.prismtune.data.FEEL_VIBES -> "vibe"
    // 1.5.4: with the dynamic real-Plex taxonomy, most names
    // are in neither old list — they are Plex Mood tags, so
    // try "mood" first. playFeel() tries mood, vibe, then
    // genre anyway, so a wrong first guess only costs one
    // empty server call, never a wrong queue.
    else -> "mood"
}

/** 1.2.2: a mood as a big car button, not a small text line.
 *  Full width, at least 76dp tall, 20sp text, tappable while
 *  driving — the photo showed plain bullet lines that could
 *  not be tapped at all. */
@Composable
fun MoodButton(name: String, onClick: () -> Unit) {
    // 1.5.2: explicit colours — the Material3 default button text was
    // dark on the dark artwork background (teal text on teal in Eric's
    // 02:54 Home photo), so it looked washed out. Text is now onSurface
    // (white on dark), border and play icon are the visible accent.
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 4.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = PrismOnSurface),
        border = androidx.compose.foundation.BorderStroke(2.dp, PrismAccentVisible),
    ) {
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = PrismOnSurface)
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play $name", tint = PrismAccentVisible)
    }
}

@Composable
fun PlaylistRow(playlist: PlexPlaylist, artworkUrl: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PrismSurface),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = null, modifier = Modifier.size(56.dp))
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAccentVisible, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                // 1.2.0: a 0 count from Plex is not a real count —
                // say so plainly instead of showing "0 tracks",
                // and the count is corrected to the real loaded
                // size after the playlist is opened once.
                Text(
                    if (playlist.itemCount > 0) "${playlist.itemCount} tracks" else "Tap to load tracks",
                    fontSize = 16.sp, color = PrismSecondary,
                )
            }
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = PrismAccentVisible)
        }
    }
}

@Composable
fun LibraryScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit, onArtistDetail: () -> Unit = {}, onAlbumDetail: () -> Unit = {}) {
    val lib by vm.library.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (lib.loading) {
            item {
                LoadingVinyl(size = 120.dp)
                Text("Loading…", fontSize = 20.sp, color = PrismAmber)
            }
        }
        lib.error?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        item { Text("Artists", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            // 1.1.0 A-Z jump: two rows of large letter buttons
            // (76dp tall, full-width weighted) that jump straight
            // to the first artist starting with that letter, so a
            // 63-page list never needs 62 Next taps in the car.
            var artistPage by remember(lib.artists.size) { mutableStateOf(0) }
            var jumpMessage by remember { mutableStateOf<String?>(null) }
            Column {
                listOf(
                    com.fourgeailabs.prismtune.data.ARTIST_JUMP_LETTERS.take(14),
                    com.fourgeailabs.prismtune.data.ARTIST_JUMP_LETTERS.drop(14),
                ).forEach { rowLetters ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowLetters.forEach { letter ->
                            OutlinedButton(
                                onClick = {
                                    val idx = com.fourgeailabs.prismtune.data.artistJumpIndex(
                                        lib.artists.map { it.title }, letter,
                                    )
                                    if (idx < 0) {
                                        jumpMessage = "No artists starting with $letter"
                                    } else {
                                        jumpMessage = null
                                        artistPage = com.fourgeailabs.prismtune.data.artistJumpPage(idx, 6)
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 76.dp).padding(1.dp),
                            ) { Text(letter.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                jumpMessage?.let { Text(it, fontSize = 18.sp, color = PrismAmber, modifier = Modifier.padding(vertical = 8.dp)) }
                PagedList(
                    lib.artists,
                    emptyText = "No artists found. Check your music library on the server.",
                    externalPage = artistPage,
                    onPageChange = { artistPage = it },
                ) { artist ->
                    ArtistRow(artist, vm.artworkUrl(artist.thumb)) { vm.openArtist(artist); onArtistDetail() }
                }
            }
        }
        if (lib.albums.isNotEmpty()) {
            item { Text("Albums", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            item {
                PagedList(lib.albums) { album ->
                    AlbumRow(album, vm.artworkUrl(album.thumb)) { vm.openAlbum(album); onAlbumDetail() }
                }
            }
        }
        if (lib.tracks.isNotEmpty()) {
            item { Text("Tracks — tap to play", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            item {
                PagedList(lib.tracks) { track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        vm.playTracks(lib.tracks, lib.tracks.indexOf(track)); onNowPlaying()
                    })
                }
            }
            item {
                BigButton("Play all tracks") { vm.playTracks(lib.tracks, 0); onNowPlaying() }
            }
        }
    }
}

@Composable
fun ArtistRow(artist: PlexArtist, artworkUrl: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PrismSurface),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = null, modifier = Modifier.size(56.dp))
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAccentVisible, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.size(12.dp))
            Text(artist.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = PrismAccentVisible)
        }
    }
}

@Composable
fun AlbumRow(album: PlexAlbum, artworkUrl: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PrismSurface),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = null, modifier = Modifier.size(56.dp))
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAccentVisible, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text(listOf(album.artistTitle, album.year?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 16.sp, color = PrismSecondary)
            }
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = PrismAccentVisible)
        }
    }
}

@Composable
fun SearchScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit, onArtistDetail: () -> Unit = {}, onAlbumDetail: () -> Unit = {}) {
    // 1.1.0: server-side search ONLY. The query goes to Plex and
    // only matching artists, albums, and tracks come back — no
    // full-library load on open, no "nothing available" while a
    // search is still running. "No matches" appears only after a
    // completed search returns 0 results. Tapping an artist loads
    // only that artist's albums; tapping an album loads only its
    // tracks; tapping a track plays it.
    var query by remember { mutableStateOf("") }
    val lib by vm.library.collectAsState()
    val results = lib.serverSearchResults
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "Tip: type while parked. While driving, use Home, Library, or Moods instead.",
                fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Artist, album, or song") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
            )
        }
        item {
            BigButton("Search") { vm.serverSearch(query) }
        }
        if (lib.searching) {
            item {
                LoadingVinyl(size = 120.dp)
                Text("Searching your server…", fontSize = 20.sp, color = PrismAmber)
            }
        }
        lib.searchError?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        if (!lib.searching && results == null) {
            item { Text("Type something above and tap Search.", fontSize = 18.sp, color = PrismOnSurface, modifier = Modifier.padding(16.dp)) }
        } else if (!lib.searching && results != null && results.isEmpty) {
            item { Text("No matches.", fontSize = 18.sp, color = PrismOnSurface, modifier = Modifier.padding(16.dp)) }
        } else if (results != null && !results.isEmpty) {
            if (results.artists.isNotEmpty()) {
                item { Text("Artists", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
                items(results.artists) { artist ->
                    ArtistRow(artist, vm.artworkUrl(artist.thumb)) { vm.openArtistDetail(artist); onArtistDetail() }
                }
            }
            if (lib.searchAlbums.isNotEmpty()) {
                item { Text("Albums by selected artist", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
                items(lib.searchAlbums) { album ->
                    AlbumRow(album, vm.artworkUrl(album.thumb)) { vm.openAlbumDetail(album); onAlbumDetail() }
                }
            }
            if (results.albums.isNotEmpty()) {
                item { Text("Albums", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
                items(results.albums) { album ->
                    AlbumRow(album, vm.artworkUrl(album.thumb)) { vm.openAlbumDetail(album); onAlbumDetail() }
                }
            }
            if (lib.searchTracks.isNotEmpty()) {
                item { Text("Tracks — tap to play", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
                itemsIndexed(lib.searchTracks) { index, track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        vm.playTracks(lib.searchTracks, index); onNowPlaying()
                    })
                }
            }
            if (results.tracks.isNotEmpty()) {
                item { Text("Tracks — tap to play", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
                itemsIndexed(results.tracks) { index, track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        vm.playTracks(results.tracks, index); onNowPlaying()
                    })
                }
            }
        }
    }
}
