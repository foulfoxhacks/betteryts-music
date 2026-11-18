package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

private val TABS = listOf("favorites" to "Favorites", "playlists" to "Playlists", "history" to "History", "artists" to "Artists")

@Composable
fun LibraryScreen(vm: AppViewModel, pad: PaddingValues) {
    var tab by rememberSaveable { mutableStateOf("favorites") }
    val lib = vm.library
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Column(Modifier.padding(16.dp)) {
                Eyebrow("Stored on this phone")
                Spacer(Modifier.height(6.dp))
                DisplayTitle("Your library", size = 44)
            }
        }
        item { GoogleCard(vm) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TABS) { (key, label) -> Chip(label, tab == key) { tab = key } }
            }
            Spacer(Modifier.height(12.dp))
        }

        when (tab) {
            "favorites" -> {
                if (lib.favorites.isEmpty()) {
                    item { EmptyNote("No favorites yet", "Tap ⋮ on any song and choose Add to favorites, or ♡ in the player.") }
                } else {
                    item { PlayShuffle(onPlay = { vm.playTracks(lib.favorites, 0) }, onShuffle = { vm.playTracks(lib.favorites, 0, shuffle = true) }) }
                    itemsIndexed(lib.favorites, key = { _, t -> "fav-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(lib.favorites, i) }) }
                }
            }

            "playlists" -> {
                item { BrandButton("New playlist", { vm.newPlaylistWith = emptyList() }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), primary = true, icon = AppIcons.Add) }
                if (lib.playlists.isEmpty()) {
                    item { EmptyNote("No playlists yet", "Create one here, or save a YouTube playlist from its page.") }
                }
                items(lib.playlists, key = { "pl-" + it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openUserPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteImage(p.tracks.firstOrNull()?.art, Modifier.size(64.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, color = Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(if (p.fromYouTube) "From YouTube" else null, "${p.tracks.size} song${if (p.tracks.size == 1) "" else "s"}").joinToString(" · "), color = Brand.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }

            "history" -> {
                if (lib.history.isEmpty()) {
                    item { EmptyNote("Nothing played yet", "Songs you play show up here.") }
                } else {
                    item { BrandButton("Clear history", { vm.clearHistory() }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = AppIcons.Delete) }
                    itemsIndexed(lib.history, key = { _, t -> "h-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(lib.history, i) }) }
                }
            }

            else -> {
                if (lib.followed.isEmpty()) {
                    item { EmptyNote("Not following anyone", "Open an artist and tap Follow. Their new songs show up on Home.") }
                }
                items(lib.followed, key = { "a-" + it.id }) { a -> ArtistRow(a) { vm.openArtist(a.id, a.name) } }
            }
        }
    }
}

@Composable
fun PlayShuffle(onPlay: () -> Unit, onShuffle: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrandButton("Play", onPlay, primary = true, icon = AppIcons.Play)
        BrandButton("Shuffle", onShuffle, icon = AppIcons.Shuffle)
        extra()
    }
}
