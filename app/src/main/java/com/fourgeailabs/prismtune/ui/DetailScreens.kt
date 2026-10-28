package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourgeailabs.prismtune.viewmodel.PrismViewModel

/**
 * 1.1.0 dedicated detail views. A playlist tap, an artist tap,
 * and an album tap each open their OWN view — title, count, and
 * only that item's children — instead of dumping everything into
 * the shared LibraryScreen. Errors are shown here, on the detail
 * view, never as a stale red error on the Artists list.
 */
@Composable
fun ArtistDetailScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit, onAlbumDetail: () -> Unit = {}) {
    val lib by vm.library.collectAsState()
    val artist = lib.detailArtist
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (artist == null) {
            item { Text("No artist selected.", fontSize = 20.sp, color = PrismOnSurface) }
            return@LazyColumn
        }
        item { Text(artist.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        if (lib.detailLoading) {
            item { LoadingVinyl(size = 120.dp); Text("Loading albums…", fontSize = 20.sp, color = PrismAmber) }
        }
        lib.detailError?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        if (!lib.detailLoading && lib.detailError == null) {
            item { Text("${lib.detailAlbums.size} albums", fontSize = 18.sp, color = PrismSecondary) }
            item {
                PagedList(lib.detailAlbums) { album ->
                    AlbumRow(album, vm.artworkUrl(album.thumb)) { vm.openAlbumDetail(album); onAlbumDetail() }
                }
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit) {
    val lib by vm.library.collectAsState()
    val album = lib.detailAlbum
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (album == null) {
            item { Text("No album selected.", fontSize = 20.sp, color = PrismOnSurface) }
            return@LazyColumn
        }
        item { Text(album.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        if (album.artistTitle.isNotBlank()) {
            item { Text(album.artistTitle, fontSize = 20.sp, color = PrismAmber) }
        }
        if (lib.detailLoading) {
            item { LoadingVinyl(size = 120.dp); Text("Loading tracks…", fontSize = 20.sp, color = PrismAmber) }
        }
        lib.detailError?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        if (!lib.detailLoading && lib.detailError == null) {
            item { Text("${lib.detailTracks.size} tracks — tap to play", fontSize = 18.sp, color = PrismSecondary) }
            item {
                PagedList(lib.detailTracks) { track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        vm.playTracks(lib.detailTracks, lib.detailTracks.indexOf(track)); onNowPlaying()
                    })
                }
            }
            if (lib.detailTracks.isNotEmpty()) {
                item { BigButton("Play all tracks") { vm.playTracks(lib.detailTracks, 0); onNowPlaying() } }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(vm: PrismViewModel, onNowPlaying: () -> Unit, onBack: () -> Unit) {
    val lib by vm.library.collectAsState()
    val playlist = lib.detailPlaylist
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (playlist == null) {
            item { Text("No playlist selected.", fontSize = 20.sp, color = PrismOnSurface) }
            return@LazyColumn
        }
        item { Text(playlist.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface) }
        if (lib.detailLoading) {
            item { LoadingVinyl(size = 120.dp); Text("Loading tracks…", fontSize = 20.sp, color = PrismAmber) }
        }
        lib.detailError?.let { item { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) } }
        if (!lib.detailLoading && lib.detailError == null) {
            item { Text("${lib.detailTracks.size} tracks — tap to play", fontSize = 18.sp, color = PrismSecondary) }
            item {
                PagedList(lib.detailTracks) { track ->
                    TrackRow(track, vm.artworkUrl(track.thumb), onClick = {
                        vm.playTracks(lib.detailTracks, lib.detailTracks.indexOf(track)); onNowPlaying()
                    })
                }
            }
            if (lib.detailTracks.isNotEmpty()) {
                item { BigButton("Play all tracks") { vm.playTracks(lib.detailTracks, 0); onNowPlaying() } }
            }
        }
    }
}
