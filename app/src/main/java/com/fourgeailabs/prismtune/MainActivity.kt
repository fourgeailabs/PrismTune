package com.fourgeailabs.prismtune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.fourgeailabs.prismtune.ui.HomeScreen
import com.fourgeailabs.prismtune.ui.LibraryScreen
import com.fourgeailabs.prismtune.ui.PrismPaletteState
import com.fourgeailabs.prismtune.ui.waveformBarColor
import com.fourgeailabs.prismtune.ui.paletteByName
import com.fourgeailabs.prismtune.ui.decodePalette
import com.fourgeailabs.prismtune.ui.KEPT_PALETTE_NAME
import com.fourgeailabs.prismtune.ui.NowPlayingScreen
import com.fourgeailabs.prismtune.ui.PrismAmber
import com.fourgeailabs.prismtune.ui.PrismAccentVisible
import com.fourgeailabs.prismtune.ui.PrismLogo
import com.fourgeailabs.prismtune.ui.PrismOnSurface
import com.fourgeailabs.prismtune.ui.PrismSecondary
import com.fourgeailabs.prismtune.ui.PrismSurface
import com.fourgeailabs.prismtune.ui.PrismTuneTheme
import com.fourgeailabs.prismtune.ui.QueueScreen
import com.fourgeailabs.prismtune.ui.SafeDrivingLayout
import com.fourgeailabs.prismtune.ui.SearchScreen
import com.fourgeailabs.prismtune.ui.SettingsScreen
import com.fourgeailabs.prismtune.ui.SignInScreen
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel
import com.fourgeailabs.prismtune.viewmodel.SignInState

/**
 * Android Automotive entry point.
 *
 * The whole UI runs through [SafeDrivingLayout]: navigation, lists, playback
 * controls, sliders and the sign-in retry button all live in the left
 * (interactive) zone. The right strip shows only the current track's artwork
 * and title — no buttons, so nothing interactive can land in the Equinox
 * EV's top-right corner.
 *
 * Audio-only by design: no video surface, no video playback code anywhere.
 */
class MainActivity : ComponentActivity() {
    private val vm: PrismViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.3.3: install crash capture FIRST, before anything
        // else, so a boot crash is still logged.
        runCatching { com.fourgeailabs.prismtune.data.CrashLog.install(this) }
        // 1.4.1 CRASH GATE: 1.4.0 safe launch did NOT break
        // the Equinox loop — after a play, launch dies in
        // under a quarter second, before the UI can even be
        // reached, so Diagnostics was still unreachable.
        // If a launch marker from a previous launch is still
        // present, that launch died before drawing. Do NOT
        // repeat the same startup: no ViewModel library load,
        // no player, no service, no Coil cache setup, no
        // permission request — just a minimal safe screen
        // with the crash history and clear buttons, drawn
        // from plain files and prefs. Only when the user taps
        // "Try normal launch" do we clear the marker and
        // restart the activity into the full app. This gate
        // is what makes a launch crash recoverable without
        // an uninstall, even when the root cause is still
        // unknown.
        val crashedBeforeDraw = runCatching {
            com.fourgeailabs.prismtune.data.CrashLog.previousLaunchCrashed(this)
        }.getOrDefault(false)
        if (crashedBeforeDraw && savedInstanceState == null) {
            setContent {
                PrismTuneTheme {
                    com.fourgeailabs.prismtune.ui.CrashGateScreen(
                        onTryNormalLaunch = {
                            com.fourgeailabs.prismtune.data.CrashLog.clearMarker(this)
                            // Clear the poisoned launch state too,
                            // but keep sign-in: saved queue only.
                            runCatching {
                                val s = com.fourgeailabs.prismtune.data.SettingsStore(this)
                                s.savedQueueJson = null
                            }
                            finish()
                            startActivity(intent)
                        },
                        onClearSavedQueue = {
                            runCatching {
                                val s = com.fourgeailabs.prismtune.data.SettingsStore(this)
                                s.savedQueueJson = null
                            }
                        },
                        onClearMetadataCache = {
                            runCatching {
                                com.fourgeailabs.prismtune.data.MetadataCache(this).clear()
                            }
                        },
                    )
                }
            }
            return
        }
        // This launch got past the gate: mark it started, and
        // delete the marker as soon as the first frame draws
        // (see the LaunchedEffect below, after setContent).
        runCatching { com.fourgeailabs.prismtune.data.CrashLog.markLaunchStarted(this) }
        // 1.2.3: persistent artwork cache — Coil's disk cache is
        // sized to 512MB here, so album cover art survives force
        // closes, cold boots, and phone restarts, and Home/Moods
        // show art instantly from disk instead of re-downloading
        // every cover on every launch.
        runCatching {
            coil.Coil.setImageLoader(
                coil.ImageLoader.Builder(this)
                    .diskCache {
                        coil.disk.DiskCache.Builder()
                            .directory(cacheDir.resolve("image_cache"))
                            .maxSizeBytes(512L * 1024 * 1024)
                            .build()
                    }
                    .build()
            )
        }
        // 1.2.3: ask for notification permission on Android 13+,
        // so the media notification that keeps background
        // playback alive can actually be shown.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
        // 1.4.0 SAFE LAUNCH: do NOT start the media service on
        // launch. On the Equinox, any play bricked every later
        // launch until reinstall, and launch-time service start
        // + foreground notification is a prime suspect (the
        // Pixel already proved a foreground timing crash in
        // 1.3.3, with a different call). The service now starts
        // only when the user actually taps Play, which is when
        // the system media card needs it. The car may still
        // treat the app as background before first play, but a
        // launch that works beats a media session that bricks
        // the app. startMediaServiceBackground() is kept for
        // that play-time path only.
        if (vm.isSignedIn) vm.loadServers()
        setContent {
            PrismTuneTheme {
                // 1.4.1: the UI drew — this launch is not a
                // launch crash. Clear the marker now, so a
                // LATER crash (e.g. on play) does not get
                // mistaken for this launch, and the next cold
                // launch goes straight to the full app.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.fourgeailabs.prismtune.data.CrashLog.markLaunchDrawn(this@MainActivity)
                }
                val safeArea by vm.safeAreaFraction.collectAsState()
                val signInState by vm.signIn.collectAsState()
                val isPlaying by vm.playback.isPlaying.collectAsState()
                val token by vm.tokenStore.token.collectAsState()
                val navController = rememberNavController()
                val start = if (vm.isSignedIn) "home" else "signin"
                // 1.2.0: palette picker + Now Playing dynamic mode.
                // Fixed = selected palette always. Now Playing =
                // accent follows the current track, surface and
                // text stay from the selected palette so it stays
                // readable. Applied via PrismPaletteState, which
                // recomposes the whole UI on change.
                val paletteName by vm.paletteName.collectAsState()
                val paletteMode by vm.paletteMode.collectAsState()
                val customPalette by vm.customPalette.collectAsState()
                // 1.2.1: currentTrack is a plain getter, not a
                // State, so reading it here never recomposed when
                // the track changed — Now Playing colours and
                // palette updates could look "not persisted".
                // Derive it from the queue + index flows instead.
                val paletteQueue by vm.playback.queue.collectAsState()
                val paletteIndex by vm.playback.currentIndex.collectAsState()
                val currentTrackForPalette = paletteQueue.getOrNull(paletteIndex)
                // 1.2.2: Now Playing colours come from the current
                // album artwork's own palette, not from a song
                // title hash. Load the artwork bitmap with Coil,
                // sample its pixels for the dominant vibrant colour,
                // and tint the accent and surface with it. If the
                // artwork cannot be loaded, fall back to the base
                // palette — never a hash colour, and never invented.
                LaunchedEffect(paletteName, paletteMode, customPalette, currentTrackForPalette?.key) {
                    // 1.5.7: a kept palette is the snapshot
                    // itself, used as the fixed base — never
                    // re-derived from artwork. A corrupt or
                    // missing snapshot falls back to Amber,
                    // never a blank or crashed screen.
                    val base = if (paletteName == KEPT_PALETTE_NAME) {
                        decodePalette(customPalette) ?: paletteByName("Amber")
                    } else {
                        paletteByName(paletteName)
                    }
                    if (paletteMode != "nowplaying" || currentTrackForPalette == null) {
                        PrismPaletteState.current = base
                    } else {
                        val artUrl = currentTrackForPalette.let { vm.artworkUrl(it.thumb) }
                        if (artUrl == null) {
                            PrismPaletteState.current = base
                        } else {
                            val accent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    val loader = coil.Coil.imageLoader(this@MainActivity)
                                    val request = coil.request.ImageRequest.Builder(this@MainActivity)
                                        .data(artUrl).allowHardware(false).build()
                                    val result = loader.execute(request)
                                    if (result is coil.request.SuccessResult) {
                                        val bitmap = runCatching {
                                            result.drawable.toBitmap()
                                        }.getOrNull()
                                        if (bitmap != null) {
                                            // 1.3.1: scale down first — a full-size
                                            // cover's pixel array could OOM on a
                                            // car head unit on boot. 200px is
                                            // plenty for a dominant colour.
                                            val scaled = if (bitmap.width > 200 || bitmap.height > 200) {
                                                android.graphics.Bitmap.createScaledBitmap(bitmap, 200, 200, true)
                                            } else bitmap
                                            val pixels = IntArray(scaled.width * scaled.height)
                                            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                                            // Sample every 4th pixel for speed on a large cover.
                                            val sampled = pixels.filterIndexed { i, _ -> i % 4 == 0 }.toIntArray()
                                            com.fourgeailabs.prismtune.data.dominantColorFromPixels(sampled)
                                        } else null
                                    } else null
                                }.getOrNull()
                            }
                            // 1.5.1 BACKGROUND FIX — Eric, 2026-10-27
                            // 02:18: "The background doesn't change
                            // colors at all. Still a reddish brown."
                            // Two causes, both real: (1) the DEFAULT
                            // palette mode is "fixed", and in fixed
                            // mode the background is the chosen
                            // palette by design — artwork never
                            // touches it. (2) Even in "nowplaying"
                            // mode, only the surface was tinted 20%
                            // toward the accent, and the Amber base
                            // surface 0xFF1E1913 is itself dark
                            // reddish-brown, so 80% brown survived
                            // every tint. Fix: in nowplaying mode
                            // the BACKGROUND itself now follows the
                            // artwork's dominant colour, darkened
                            // to 45% so white/black text stays
                            // readable, and the surface is a mix
                            // of that darkened artwork colour (65%)
                            // with the base surface (35%), so the
                            // hue is the artwork's, not Amber's
                            // brown. In fixed mode the background
                            // stays the chosen palette — that is
                            // the user's explicit choice — so to
                            // get artwork backgrounds, pick "Now
                            // Playing colours" in Settings.
                            PrismPaletteState.current = if (accent != null) {
                                val darkBg = com.fourgeailabs.prismtune.data.darkenColor(accent, 0.45f)
                                base.copy(
                                    accent = accent,
                                    accentDeep = com.fourgeailabs.prismtune.data.darkenColor(accent),
                                    background = if (base.isLight) base.background else darkBg,
                                    // 1.5.2: surface must be the SAME hue family as the
                                    // background — a slightly lightened darkBg — not a
                                    // mix with Amber's brown surface, which is what made
                                    // cards float as grey/brown boxes on a teal background
                                    // in Eric's 02:54 Home photo. Secondary is light
                                    // warm grey on dark, so body text reads white-ish,
                                    // not mid-teal/grey.
                                    surface = if (base.isLight) base.surface else com.fourgeailabs.prismtune.data.lightenColor(darkBg, 1.35f),
                                    onSurface = if (base.isLight) base.onSurface else androidx.compose.ui.graphics.Color.White,
                                    secondary = if (base.isLight) base.secondary else androidx.compose.ui.graphics.Color(0xFFE8E0D0),
                                )
                            } else {
                                base
                            }
                        }
                    }
                }
                // 1.1.4: track the current route so the waveform
                // mini player disappears completely when the main
                // player is on screen.
                val backStackEntry by navController.currentBackStackEntryAsState()
                val isNowPlaying = backStackEntry?.destination?.route == "nowplaying"

                // Sign-out fix: NavHost's startDestination is fixed at
                // composition, so signOut() alone left the user stranded on
                // Settings/Home with no token. When the stored token becomes
                // null (sign-out, or a 401 clearing it), navigate to the
                // Sign-in screen and clear the signed-in back stack. Guarded
                // so the initial signed-out launch (already on signin) and
                // the signed-in Done -> home flow are not disturbed.
                LaunchedEffect(token) {
                    if (token == null && navController.currentDestination?.route != "signin") {
                        navController.navigate("signin") {
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    }
                }

                SafeDrivingLayout(
                    safeAreaFraction = safeArea,
                    interactiveContent = {
                        // 1.1.0 persistent mini player: the NavHost
                        // takes the remaining height, the mini player
                        // sits at the bottom of the interactive zone
                        // (never in the gauge quadrant), and returns
                        // early when there is no track to show.
                        Column(modifier = Modifier.fillMaxSize()) {
                        // 1.2.2: persistent top nav bar, across ALL
                        // menus, not just Home. It sits above the
                        // NavHost, so Settings, Library, Queue, Moods,
                        // Search, and Now Playing all show it. Hidden
                        // on the sign-in screen, where navigating to
                        // Home without a token would strand the user.
                        if (token != null) {
                            // 1.3.7: tapping a top menu option ALWAYS
                            // goes to that section's MAIN screen, fresh.
                            // Eric, 2026-10-26 23:50: from Moods -> Now
                            // Playing, tapping Moods did not bring back
                            // the Moods home page, and so on. The old
                            // goTo used restoreState=true, which could
                            // restore a saved detail / player state
                            // instead of the section root, and
                            // popUpTo(startDestination) which is not
                            // always "home". Now: pop everything above
                            // home, do NOT restore saved state, and
                            // always create the section root fresh.
                            // Home itself pops to itself, inclusive,
                            // so a detail stack is cleared too.
                            val goTo: (String) -> Unit = { route ->
                                navController.navigate(route) {
                                    if (route == "home") {
                                        popUpTo("home") { inclusive = true }
                                    } else {
                                        popUpTo("home") { inclusive = false }
                                    }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            }
                            com.fourgeailabs.prismtune.ui.NavRow(
                                onHome = { goTo("home") },
                                onLibrary = { goTo("library") },
                                onSearch = { goTo("search") },
                                onQueue = { goTo("queue") },
                                onSettings = { goTo("settings") },
                                onFeels = { goTo("feels") },
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                        // 1.1.0 stale-banner fix: changing screens clears
                        // the previous screen's error, so a red banner
                        // never follows you onto an unrelated screen.
                        androidx.compose.runtime.DisposableEffect(navController) {
                            val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, _, _ ->
                                vm.clearScreenErrors()
                            }
                            navController.addOnDestinationChangedListener(listener)
                            onDispose { navController.removeOnDestinationChangedListener(listener) }
                        }
                        NavHost(navController = navController, startDestination = start) {
                            composable("signin") {
                                SignInScreen(vm, signInState) {
                                    navController.navigate("home") {
                                        popUpTo("signin") { inclusive = true }
                                    }
                                }
                            }
                            composable("home") {
                                HomeScreen(
                                    vm = vm,
                                    onLibrary = { navController.navigate("library") },
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onQueue = { navController.navigate("queue") },
                                    onSearch = { navController.navigate("search") },
                                    onSettings = { navController.navigate("settings") },
                                    onFeels = { navController.navigate("feels") },
                                    onPlaylistDetail = { navController.navigate("playlistDetail") },
                                    onAlbumDetail = { navController.navigate("albumDetail") },
                                )
                            }
                            composable("feels") {
                                com.fourgeailabs.prismtune.ui.FeelsScreen(
                                    vm = vm,
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onSearch = { navController.navigate("search") },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable("library") {
                                LibraryScreen(
                                    vm = vm,
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onBack = { navController.popBackStack() },
                                    onArtistDetail = { navController.navigate("artistDetail") },
                                    onAlbumDetail = { navController.navigate("albumDetail") },
                                )
                            }
                            composable("artistDetail") {
                                com.fourgeailabs.prismtune.ui.ArtistDetailScreen(
                                    vm = vm,
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onBack = { navController.popBackStack() },
                                    onAlbumDetail = { navController.navigate("albumDetail") },
                                )
                            }
                            composable("albumDetail") {
                                com.fourgeailabs.prismtune.ui.AlbumDetailScreen(
                                    vm = vm,
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable("playlistDetail") {
                                com.fourgeailabs.prismtune.ui.PlaylistDetailScreen(
                                    vm = vm,
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable("search") {
                                SearchScreen(
                                    vm = vm,
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                    onBack = { navController.popBackStack() },
                                    onArtistDetail = { navController.navigate("artistDetail") },
                                    onAlbumDetail = { navController.navigate("albumDetail") },
                                )
                            }
                            composable("nowplaying") {
                                NowPlayingScreen(vm = vm, onBack = { navController.popBackStack() })
                            }
                            composable("queue") {
                                QueueScreen(
                                    vm = vm,
                                    onBack = { navController.popBackStack() },
                                    onNowPlaying = { navController.navigate("nowplaying") },
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    vm = vm,
                                    onBack = { navController.popBackStack() },
                                    onCrashLogs = { navController.navigate("crashlogs") },
                                    onWhatsNew = { navController.navigate("whatsnew") },
                                    onDownloads = { navController.navigate("downloads") },
                                )
                            }
                            composable("downloads") {
                                com.fourgeailabs.prismtune.ui.DownloadsScreen(vm = vm)
                            }
                            composable("crashlogs") {
                                com.fourgeailabs.prismtune.ui.CrashLogsScreen(vm = vm)
                            }
                            composable("whatsnew") {
                                com.fourgeailabs.prismtune.ui.WhatsNewScreen()
                            }
                        }
                        }
                        // 1.1.4: NO mini player bar here any more.
                        // The waveform panel on the right IS the mini
                        // player (see ambientContent below). When
                        // Now Playing is on screen it goes away
                        // completely, so there are never two players.
                        }
                    },
                    ambientContent = {
                        // 1.1.4: the waveform panel IS the mini player.
                        // Tapping the waveform opens Now Playing, and
                        // play/pause + next sit below it. When Now
                        // Playing is on screen, those controls go
                        // away completely and this panel reverts to
                        // artwork + title + waveform display only,
                        // so there are never two players on screen.
                        // Controls sit below the artwork, never in
                        // the top-right temperature-gauge quadrant.
                        val track = vm.playback.currentTrack
                        val queue by vm.playback.queue.collectAsState()
                        val currentIndex by vm.playback.currentIndex.collectAsState()
                        val shufflePanel by vm.playback.shuffle.collectAsState()
                        // 1.2.0 right panel, two modes:
                        // - Large player DOWN: current song + large
                        //   centre-line waveform + play/next.
                        // - Large player UP: NEXT song in queue,
                        //   NO waveform, tapping it opens the Queue
                        //   screen (next 100 songs).
                        // 1.3.9: with shuffle on, the next song is
                        // the player's shuffled next, not
                        // queue[currentIndex + 1]. shufflePanel is
                        // collected so this recomposes on toggle.
                        val nextTrack = if (shufflePanel) {
                            vm.playback.upNextIndices(1).firstOrNull()?.let { queue.getOrNull(it) }
                        } else {
                            queue.getOrNull(currentIndex + 1)
                        }
                        val panelTrack = if (isNowPlaying) nextTrack else track
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val panelModifier = Modifier.padding(16.dp).let {
                                if (panelTrack != null) {
                                    it.clickable {
                                        if (isNowPlaying) {
                                            navController.navigate("queue")
                                        } else {
                                            if (vm.playback.queue.value.isEmpty()) vm.resumeSavedQueue()
                                            navController.navigate("nowplaying")
                                        }
                                    }
                                } else it
                            }
                            // 1.5.1 PANEL FILL — Eric, 2026-10-27
                            // 02:25, photo of the Up Next panel:
                            // a small centred cluster (header +
                            // artwork + one line) floating in a
                            // huge red panel, with dead space
                            // above and below. That reads as
                            // unfinished. Fix: the panel column
                            // now fills the panel height and
                            // spreads its content top-to-bottom
                            // (header top, artwork + next track
                            // upper-middle, following list
                            // filling the middle, footer at the
                            // bottom), and, when Now Playing is
                            // up, it lists the FOLLOWING tracks
                            // after the next one, so the panel
                            // is a real Up Next preview, not one
                            // card in empty space. Tapping
                            // anywhere on the panel still opens
                            // the full Queue (next 100) — the
                            // following rows are display-only,
                            // so no new interactive controls land
                            // in the right-side panel.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (isNowPlaying) androidx.compose.foundation.layout.Arrangement.SpaceBetween else androidx.compose.foundation.layout.Arrangement.Center,
                                modifier = panelModifier.fillMaxSize(),
                            ) {
                                if (isNowPlaying) {
                                    Text("Up Next", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismAmber)
                                }
                                val art = panelTrack?.let { vm.artworkUrl(it.thumb) }
                                if (art != null) {
                                    AsyncImage(model = art, contentDescription = null, modifier = Modifier.size(180.dp))
                                } else {
                                    PrismLogo(size = 160.dp)
                                }
                                if (panelTrack != null) {
                                    Text(panelTrack.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                                    Text(panelTrack.artistTitle, fontSize = 18.sp, color = PrismAmber)
                                    if (isNowPlaying) {
                                        // Following tracks after the next
                                        // one, filling the panel middle.
                                        // upNextIndices is shuffle-aware
                                        // (1.3.9), so this list is in
                                        // actual playback order.
                                        val following = vm.playback.upNextIndices(11).drop(1)
                                            .mapNotNull { queue.getOrNull(it) }
                                        // 1.5.6 — Eric, 2026-10-27 05:56, photo of
                                        // this panel: the Following rows were
                                        // 14sp small print. Now 20sp, matching
                                        // track titles elsewhere, so the list
                                        // reads at a glance in the car.
                                        if (following.isNotEmpty()) {
                                            Text("Following", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismSecondary, modifier = Modifier.padding(top = 12.dp))
                                            following.forEach { ft ->
                                                Text(
                                                    "${ft.title} • ${ft.artistTitle}",
                                                    fontSize = 20.sp, color = PrismOnSurface.copy(alpha = 0.85f),
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Text("Tap to see the next 100 songs", fontSize = 18.sp, color = PrismSecondary, modifier = Modifier.padding(top = 12.dp))
                                    } else {
                                        Text(if (isPlaying) "▶ Playing" else "❚❚ Paused", fontSize = 16.sp, color = PrismSecondary)
                                        val queueSource by vm.queueSource.collectAsState()
                                        queueSource?.let { qs ->
                                            Text("From ${qs.kind}: ${qs.name}", fontSize = 14.sp, color = PrismSecondary)
                                        }
                                    }
                                } else if (isNowPlaying) {
                                    // 1.2.3: end of queue is not a dead
                                    // end — Next continues from the
                                    // queue's source or the library,
                                    // so offer that here instead of
                                    // only saying nothing is up next.
                                    // 1.5.1: this was the worst dead
                                    // space in Eric's photo — vinyl +
                                    // two lines in a huge panel. Fill
                                    // it with the queue context: what
                                    // is playing now, where it came
                                    // from, and what Next will do.
                                    Text("End of queue", fontSize = 20.sp, color = PrismOnSurface)
                                    Text("Tap Next to continue playing.", fontSize = 16.sp, color = PrismSecondary)
                                    if (track != null) {
                                        Text("Now playing", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrismSecondary, modifier = Modifier.padding(top = 16.dp))
                                        Text(track.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                                        Text(track.artistTitle, fontSize = 16.sp, color = PrismAmber)
                                    }
                                    val queueSourceEnd by vm.queueSource.collectAsState()
                                    queueSourceEnd?.let { qs ->
                                        Text("From ${qs.kind}: ${qs.name}", fontSize = 14.sp, color = PrismSecondary, modifier = Modifier.padding(top = 8.dp))
                                    }
                                    Text("${queue.size} songs in queue", fontSize = 14.sp, color = PrismSecondary)
                                } else {
                                    Text("PrismTune", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                                    Text("Your Plex music, in the car.", fontSize = 16.sp, color = PrismSecondary)
                                }
                                // 1.2.0 large centre-line waveform,
                                // left-to-right, only when the large
                                // player is DOWN. Real PCM envelope,
                                // flat when paused / no track.
                                if (!isNowPlaying) {
                                    val waveform by vm.playback.waveformLevels.collectAsState()
                                    val panelPosition by vm.playback.positionMs.collectAsState()
                                    val panelDuration by vm.playback.durationMs.collectAsState()
                                    // 1.5.1 MINI-PLAYER WAVEFORM — Eric,
                                    // 2026-10-27 02:23: "We need the same
                                    // exact progress bar/wave form for
                                    // the mini player on the right as
                                    // well. The right side still has
                                    // the old messy looking one."
                                    // He is right: this panel still had
                                    // the OLD drawing — one accent
                                    // colour for every bar, stroke
                                    // 4.5% of panel width per bar so
                                    // bars merged into a slab, and a
                                    // flat grey line when idle. Now it
                                    // is the SAME drawing as Now
                                    // Playing, bar for bar: height =
                                    // live spectrum, colour = progress
                                    // (white on dark / black on light
                                    // unplayed, blending toward the
                                    // background's complement as the
                                    // song progresses), fixed 3dp
                                    // stroke, no fake floor, no flat
                                    // grey line — silence is a flat
                                    // base-colour line at true zero
                                    // height, honestly. Tap-to-seek
                                    // matches Now Playing too: tapping
                                    // the panel waveform seeks.
                                    androidx.compose.foundation.Canvas(
                                        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp)
                                            .pointerInput(panelDuration) {
                                                detectTapGestures { offset ->
                                                    if (panelDuration > 0) {
                                                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                                        vm.playback.seekTo((panelDuration * frac).toLong())
                                                    }
                                                }
                                            },
                                    ) {
                                        val progressFrac = if (panelDuration > 0) panelPosition.toFloat() / panelDuration.toFloat() else 0f
                                        // 1.5.5: mini-player waveform uses the
                                        // same actual palette background as
                                        // Now Playing — never a fixed charcoal
                                        // whose complement clashed with it.
                                        val bg = PrismPaletteState.current.background
                                        val isLight = PrismPaletteState.current.isLight
                                        waveform.forEachIndexed { i, frac ->
                                            val x = size.width * (0.02f + i * (0.96f / (waveform.size - 1).coerceAtLeast(1)))
                                            val h = size.height * 0.48f * frac.coerceIn(0f, 1f)
                                            drawLine(
                                                color = waveformBarColor(i.toFloat() / waveform.size.coerceAtLeast(1), progressFrac, isLight, bg),
                                                start = Offset(x, size.height / 2f - h),
                                                end = Offset(x, size.height / 2f + h),
                                                strokeWidth = 3.dp.toPx(),
                                            )
                                        }
                                    }
                                    if (track != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                            Box(
                                                modifier = Modifier.size(76.dp)
                                                    .background(PrismAccentVisible, CircleShape)
                                                    .clickable { vm.playFromMiniPlayer() },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                                    // 1.5.6 BUG FIX — Eric, 2026-10-27 05:42,
                                                    // car photo: the mini-player play/
                                                    // pause was "just a straight-up
                                                    // white dot". Cause: this tint was
                                                    // INVERTED — a white disc got a
                                                    // white icon, invisible on it. A
                                                    // white disc needs a BLACK icon;
                                                    // a dark disc needs a white one.
                                                    tint = if (PrismAccentVisible == Color.White) Color.Black else Color.White, modifier = Modifier.size(56.dp),
                                                )
                                            }
                                            Box(
                                                modifier = Modifier.size(76.dp)
                                                    .padding(start = 12.dp)
                                                    .background(com.fourgeailabs.prismtune.data.lightenColor(PrismSurface, 1.3f), CircleShape)
                                                    .clickable { vm.nextTrack() },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Filled.SkipNext,
                                                    contentDescription = "Next song",
                                                    tint = PrismAccentVisible, modifier = Modifier.size(48.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                if (signInState is SignInState.Waiting) {
                                    Text("Waiting for phone approval…", fontSize = 14.sp, color = PrismSecondary)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
