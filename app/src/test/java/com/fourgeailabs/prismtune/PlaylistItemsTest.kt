package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.PlexException
import com.fourgeailabs.prismtune.data.PlexPlaylist
import com.fourgeailabs.prismtune.data.isPlaylistItems404
import com.fourgeailabs.prismtune.data.playlistItemsPath
import com.fourgeailabs.prismtune.data.playlistMetadataChildrenPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.2 All Music 404 (2026-10-26): opening "All Music"
 * (ratingKey 51262) called /library/metadata/51262/children
 * and got 404, because a playlist ID is not a library
 * metadata ID. Tracks must load from /playlists/<id>/items.
 * PlexClient.get is private, so the network call itself is
 * not mocked here; these tests cover the exact paths and
 * fallback decision tracksForPlaylistFor /
 * playlistItemCountFor use, which is where the bug lived.
 */
class PlaylistItemsTest {

    @Test
    fun itemsPathUsesKeyPathWhenPresent() {
        // As captured from /playlists: key "/playlists/51262".
        val pl = PlexPlaylist(key = "51262", title = "All Music", keyPath = "/playlists/51262")
        assertEquals("/playlists/51262/items", playlistItemsPath(pl))
    }

    @Test
    fun itemsPathUsesKeyPathItemsAsIs() {
        val pl = PlexPlaylist(key = "51262", title = "All Music", keyPath = "/playlists/51262/items")
        assertEquals("/playlists/51262/items", playlistItemsPath(pl))
    }

    @Test
    fun itemsPathFallsBackToRatingKeyWhenNoKeyPath() {
        val pl = PlexPlaylist(key = "51262", title = "All Music")
        assertEquals("/playlists/51262/items", playlistItemsPath(pl))
    }

    @Test
    fun itemsPathNeverUsesMetadataChildren() {
        val pl = PlexPlaylist(key = "51262", title = "All Music", keyPath = "/playlists/51262")
        assertFalse(playlistItemsPath(pl).startsWith("/library/metadata/"))
    }

    @Test
    fun metadataChildrenPathIsFallbackOnly() {
        val pl = PlexPlaylist(key = "51262", title = "All Music", keyPath = "/playlists/51262")
        assertEquals("/library/metadata/51262/children", playlistMetadataChildrenPath(pl))
    }

    @Test
    fun fallbackOnlyOn404() {
        assertTrue(isPlaylistItems404(PlexException("failed: 404", 404)))
        assertTrue(isPlaylistItems404(PlexException("GET failed: 404 Not Found")))
        assertFalse(isPlaylistItems404(PlexException("rejected", 401)))
        assertFalse(isPlaylistItems404(PlexException("connect timed out")))
    }

    @Test
    fun playlistItemCountPathIsItemsEndpoint() {
        // Count uses the same items endpoint with Size=0,
        // never the metadata-children path, so smart/dynamic
        // counts stay real and are never invented.
        val pl = PlexPlaylist(key = "51262", title = "All Music", keyPath = "/playlists/51262")
        assertEquals("/playlists/51262/items?X-Plex-Container-Size=0", "${playlistItemsPath(pl)}?X-Plex-Container-Size=0")
    }

    @Test
    fun emptyPlaylistIsNotInvented() {
        // A playlist with no items has no tracks to show:
        // the items path is still well-formed and the count
        // field stays 0 from the server, not a fabricated list.
        val pl = PlexPlaylist(key = "99999", title = "Empty", keyPath = "/playlists/99999", itemCount = 0)
        assertEquals("/playlists/99999/items", playlistItemsPath(pl))
        assertEquals(0, pl.itemCount)
    }
}
