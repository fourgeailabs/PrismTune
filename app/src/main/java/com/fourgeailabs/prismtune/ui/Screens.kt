package com.fourgeailabs.prismtune.ui

import android.graphics.Bitmap
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fourgeailabs.prismtune.data.PlexAlbum
import com.fourgeailabs.prismtune.data.PlexArtist
import com.fourgeailabs.prismtune.data.PlexPlaylist
import com.fourgeailabs.prismtune.data.PlexTrack
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel
import com.fourgeailabs.prismtune.viewmodel.SignInState

/** Minimum touch target for every driving-safe control in this app. */
val MinTouch = 76.dp

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(MinTouch)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PrismAmber)
            }
        }
        PrismLogo(size = 44.dp)
        Spacer(modifier = Modifier.size(12.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
    }
}

@Composable
fun NavRow(onHome: () -> Unit, onLibrary: () -> Unit, onSearch: () -> Unit, onQueue: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavButton("Home", Icons.Filled.Home, onHome)
        NavButton("Library", Icons.Filled.LibraryMusic, onLibrary)
        NavButton("Search", Icons.Filled.Search, onSearch)
        NavButton("Queue", Icons.Filled.QueueMusic, onQueue)
        NavButton("Settings", Icons.Filled.Settings, onSettings)
    }
}

@Composable
private fun NavButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(8.dp)) {
        Icon(icon, contentDescription = label, tint = PrismAmber, modifier = Modifier.size(36.dp))
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
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAmber, modifier = Modifier.size(56.dp))
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text(
                    listOf(track.artistTitle, track.albumTitle).filter { it.isNotBlank() }.joinToString(" • "),
                    fontSize = 16.sp, color = Color(0xFFD8CFC0),
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
    row: @Composable (T) -> Unit,
) {
    var page by remember(items.size) { mutableStateOf(0) }
    val pages = maxOf(1, (items.size + pageSize - 1) / pageSize)
    val safePage = page.coerceIn(0, pages - 1)
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
                    onClick = { page = (safePage - 1).coerceAtLeast(0) },
                    enabled = safePage > 0,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) { Text("← Prev", fontSize = 18.sp) }
                Text("Page ${safePage + 1} of $pages", fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(top = 18.dp))
                OutlinedButton(
                    onClick = { page = (safePage + 1).coerceAtMost(pages - 1) },
                    enabled = safePage < pages - 1,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) { Text("Next →", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
fun SignInScreen(vm: PrismViewModel, state: SignInState, onDone: () -> Unit) {
    // Scrollable: on the Equinox EV's 4.875-inch-tall usable area, the code
    // + QR + logo stack does not fit in one fixed viewport, so this screen
    // scrolls inside the interactive pane instead of clipping.
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar("PrismTune")
        Spacer(modifier = Modifier.height(8.dp))
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
            }
            is SignInState.Creating -> {
                Text("Getting your link code…", fontSize = 20.sp, color = PrismOnSurface)
            }
            is SignInState.Waiting -> {
                Text("On your phone, open:", fontSize = 20.sp, color = PrismOnSurface)
                Text("plex.tv/link", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismAmber)
                Text("…or scan this code with your phone camera:", fontSize = 18.sp, color = PrismOnSurface)
                Spacer(modifier = Modifier.height(12.dp))
                QrImage(state.qrBitmap)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Then enter this code:", fontSize = 20.sp, color = PrismOnSurface)
                Text(state.code, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = PrismAmber)
                Text("Waiting for approval… (up to 15 minutes)", fontSize = 16.sp, color = PrismOnSurface)
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
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR code for plex.tv/link", modifier = Modifier.size(280.dp))
    } else {
        Text("QR code unavailable — open plex.tv/link on your phone instead.", fontSize = 16.sp, color = PrismOnSurface)
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
) {
    val lib by vm.library.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar("PrismTune") }
        item { NavRow(onHome = {}, onLibrary = onLibrary, onSearch = onSearch, onQueue = onQueue, onSettings = onSettings) }
        item {
            Text(
                "Server: ${lib.selectedServer?.name ?: "—"}",
                fontSize = 18.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (lib.loading) item { Text("Loading…", fontSize = 20.sp, color = PrismAmber) }
        lib.error?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        item { Text("Recently added", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        items(lib.recent.take(6)) { track ->
            TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                vm.playTracks(lib.recent, lib.recent.indexOf(track)); onNowPlaying()
            })
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { Text("Playlists", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        items(lib.playlists.take(6)) { playlist ->
            PlaylistRow(playlist, vm.artworkUrl(playlist.thumb)) { vm.openPlaylist(playlist); onLibrary() }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { BigButton("Browse all artists") { onLibrary() } }
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
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAmber, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text("${playlist.itemCount} tracks", fontSize = 16.sp, color = Color(0xFFD8CFC0))
            }
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = PrismAmber)
        }
    }
}

@Composable
fun LibraryScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit) {
    val lib by vm.library.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar("Library", onBack = onBack) }
        if (lib.loading) item { Text("Loading…", fontSize = 20.sp, color = PrismAmber) }
        lib.error?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        item { Text("Artists", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        item {
            PagedList(lib.artists, emptyText = "No artists found. Check your music library on the server.") { artist ->
                ArtistRow(artist, vm.artworkUrl(artist.thumb)) { vm.openArtist(artist) }
            }
        }
        if (lib.albums.isNotEmpty()) {
            item { Text("Albums", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
            item {
                PagedList(lib.albums) { album ->
                    AlbumRow(album, vm.artworkUrl(album.thumb)) { vm.openAlbum(album) }
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
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAmber, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.size(12.dp))
            Text(artist.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = PrismAmber)
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
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrismAmber, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
                Text(listOf(album.artistTitle, album.year?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 16.sp, color = Color(0xFFD8CFC0))
            }
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = PrismAmber)
        }
    }
}

@Composable
fun SearchScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val lib by vm.library.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar("Search", onBack = onBack) }
        item {
            Text(
                "Tip: type while parked. While driving, use Home, Library, or Queue instead.",
                fontSize = 16.sp, color = Color(0xFFD8CFC0), modifier = Modifier.padding(vertical = 8.dp),
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
        item { BigButton("Search") { vm.search(query) } }
        itemsIndexed(lib.searchResults) { index, track ->
            TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                vm.playTracks(lib.searchResults, index); onNowPlaying()
            })
        }
    }
}
