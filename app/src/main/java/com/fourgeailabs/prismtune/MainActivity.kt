package com.fourgeailabs.prismtune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.fourgeailabs.prismtune.ui.HomeScreen
import com.fourgeailabs.prismtune.ui.LibraryScreen
import com.fourgeailabs.prismtune.ui.NowPlayingScreen
import com.fourgeailabs.prismtune.ui.PrismAmber
import com.fourgeailabs.prismtune.ui.PrismLogo
import com.fourgeailabs.prismtune.ui.PrismOnSurface
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
        if (vm.isSignedIn) vm.loadServers()
        setContent {
            PrismTuneTheme {
                val safeArea by vm.safeAreaFraction.collectAsState()
                val signInState by vm.signIn.collectAsState()
                val isPlaying by vm.playback.isPlaying.collectAsState()
                val navController = rememberNavController()
                val start = if (vm.isSignedIn) "home" else "signin"

                SafeDrivingLayout(
                    safeAreaFraction = safeArea,
                    interactiveContent = {
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
                                )
                            }
                            composable("library") {
                                LibraryScreen(
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
                                SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                            }
                        }
                    },
                    ambientContent = {
                        // DISPLAY ONLY. Deliberately no clickable / button / slider here.
                        val track = vm.playback.currentTrack
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                // Kept deliberately compact: the usable area is only
                                // 4.875 in tall, and this strip must NOT scroll
                                // (it is display-only, so no gestures live here).
                                val art = track?.let { vm.artworkUrl(it.thumb) }
                                if (art != null) {
                                    AsyncImage(model = art, contentDescription = null, modifier = Modifier.size(180.dp))
                                } else {
                                    PrismLogo(size = 160.dp)
                                }
                                if (track != null) {
                                    Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                                    Text(track.artistTitle, fontSize = 18.sp, color = PrismAmber)
                                    Text(if (isPlaying) "▶ Playing" else "❚❚ Paused", fontSize = 16.sp, color = Color(0xFFD8CFC0))
                                } else {
                                    Text("PrismTune", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                                    Text("Your Plex music, in the car.", fontSize = 16.sp, color = Color(0xFFD8CFC0))
                                }
                                // Decorative equalizer glow (not a control).
                                Canvas(modifier = Modifier.size(180.dp, 40.dp)) {
                                    val heights = listOf(0.5f, 0.9f, 0.35f, 0.75f, 0.55f, 0.95f, 0.4f)
                                    heights.forEachIndexed { i, frac ->
                                        val x = size.width * (0.07f + i * 0.14f)
                                        drawLine(
                                            color = PrismAmber,
                                            start = Offset(x, size.height),
                                            end = Offset(x, size.height * (1f - frac)),
                                            strokeWidth = size.width * 0.06f,
                                        )
                                    }
                                }
                                if (signInState is SignInState.Waiting) {
                                    Text("Waiting for phone approval…", fontSize = 14.sp, color = Color(0xFFD8CFC0))
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
