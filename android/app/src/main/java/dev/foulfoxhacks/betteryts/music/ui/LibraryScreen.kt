package dev.foulfoxhacks.betteryts.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.core.UserPlaylist
import dev.foulfoxhacks.betteryts.music.device.DeviceMusic
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

/** YouTube Music's library chips, plus History (theirs hides it in the account menu). */
private val TABS = listOf("all" to "All", "songs" to "Songs", "playlists" to "Playlists", "artists" to "Artists", "device" to "On this phone", "history" to "History")

@Composable
fun LibraryScreen(vm: AppViewModel, pad: PaddingValues) {
    val tab = vm.libraryTab
    var filter by rememberSaveable { mutableStateOf("") }
    var az by rememberSaveable { mutableStateOf(false) }
    val lib = vm.library
    val q = filter.trim()

    fun Track.matches() = q.isEmpty() || title.contains(q, true) || artist.contains(q, true)
    val songs = lib.favorites.filter { it.matches() }.let { if (az) it.sortedBy { t -> t.title.lowercase() } else it }
    val playlists = lib.playlists.filter { q.isEmpty() || it.name.contains(q, true) }.let { if (az) it.sortedBy { p -> p.name.lowercase() } else it }
    val artists = lib.followed.filter { q.isEmpty() || it.name.contains(q, true) }.let { if (az) it.sortedBy { a -> a.name.lowercase() } else it }
    val history = lib.history.filter { it.matches() }
    val device = (vm.deviceSongs as? Load.Ready)?.value.orEmpty().filter { it.matches() }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadDeviceSongs() else vm.message = "BetterYTS Music needs that permission to see the music on your phone."
    }
    LaunchedEffect(tab) { if (tab == "device" && vm.deviceSongs == null) vm.loadDeviceSongs() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Column(Modifier.padding(16.dp)) {
                Eyebrow("Stored on this phone")
                Spacer(Modifier.height(6.dp))
                DisplayTitle("Your library", size = 44)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TABS) { (key, label) -> Chip(label, tab == key) { vm.libraryTab = key } }
            }
        }
        if (!lib.isEmpty || tab == "device") {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        filter,
                        { filter = it },
                        Modifier.weight(1f),
                        placeholder = { Text("Search your library", color = Brand.Muted) },
                        leadingIcon = { Icon(AppIcons.Search, null, tint = Brand.Muted) },
                        trailingIcon = if (filter.isNotEmpty()) ({ Icon(AppIcons.Close, "Clear", Modifier.clickable { filter = "" }, tint = Brand.Muted) }) else null,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Brand.Gold,
                            unfocusedBorderColor = Brand.Line,
                            cursorColor = Brand.Gold,
                            focusedTextColor = Brand.Paper,
                            unfocusedTextColor = Brand.Paper,
                        ),
                    )
                    if (tab != "history") {
                        Text(
                            if (az) "A–Z" else "RECENT",
                            Modifier.clickable { az = !az }.padding(horizontal = 12.dp, vertical = 14.dp),
                            color = Brand.Gold,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        when (tab) {
            "all" -> allView(vm, songs, playlists, history, q.isEmpty())

            "songs" -> {
                if (songs.isEmpty()) {
                    item { EmptyNote(if (q.isEmpty()) "No liked songs yet" else "No matches", if (q.isEmpty()) "Tap ♡ in the player, or ⋮ → Add to favorites on any song." else "Nothing in your liked songs matches \"$q\".") }
                } else {
                    item { PlayShuffle(onPlay = { vm.playTracks(songs, 0) }, onShuffle = { vm.playTracks(songs, 0, shuffle = true) }) }
                    itemsIndexed(songs, key = { _, t -> "fav-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(songs, i) }) }
                }
            }

            "playlists" -> {
                item { BrandButton("New playlist", { vm.newPlaylistWith = emptyList() }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), primary = true, icon = AppIcons.Add) }
                if (playlists.isEmpty()) {
                    item { EmptyNote(if (q.isEmpty()) "No playlists yet" else "No matches", "Create one here, save a YouTube playlist from its page, or import yours from YouTube.") }
                }
                items(playlists, key = { "pl-" + it.id }) { p -> PlaylistRow(vm, p) }
            }

            "device" -> when (val d = vm.deviceSongs) {
                null -> item {
                    Column(Modifier.padding(16.dp)) {
                        EmptyNote("Your own music, offline", "Play the songs stored on this phone (MP3, FLAC, M4A, Opus…) with the same queue, lock-screen controls, sleep timer and lyrics. Nothing leaves the phone.")
                        BrandButton("Allow access to music", { askPermission.launch(DeviceMusic.permission) }, Modifier.padding(top = 8.dp), primary = true, icon = AppIcons.Library)
                    }
                }
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { ErrorBox(d.message) { vm.loadDeviceSongs() } }
                is Load.Ready -> if (device.isEmpty()) {
                    item { EmptyNote(if (q.isEmpty()) "No music on this phone" else "No matches", if (q.isEmpty()) "Copy songs into the Music folder and they show up here." else "No song on this phone matches \"$q\".") }
                } else {
                    item { PlayShuffle(onPlay = { vm.playTracks(device, 0) }, onShuffle = { vm.playTracks(device, 0, shuffle = true) }, extra = { BrandButton("Rescan", { vm.loadDeviceSongs() }, icon = AppIcons.Refresh) }) }
                    itemsIndexed(device, key = { _, t -> "d-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(device, i) }) }
                }
            }

            "history" -> {
                if (history.isEmpty()) {
                    item { EmptyNote(if (q.isEmpty()) "Nothing played yet" else "No matches", "Songs you play show up here.") }
                } else {
                    if (q.isEmpty()) item { BrandButton("Clear history", { vm.clearHistory() }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = AppIcons.Delete) }
                    itemsIndexed(history, key = { _, t -> "h-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(history, i) }) }
                }
            }

            else -> {
                if (artists.isEmpty()) {
                    item { EmptyNote(if (q.isEmpty()) "Not following anyone" else "No matches", "Open an artist and tap Follow. Their new songs show up on Home.") }
                }
                items(artists, key = { "a-" + it.id }) { a -> ArtistRow(a) { vm.openArtist(a.id, a.name) } }
            }
        }
        item { Spacer(Modifier.height(8.dp)); SpotifyCard(vm) }
        item { GoogleCard(vm) }
        if (vm.pluginOn("vrchat")) item { VrchatSendCard(vm) }
    }
}

/** "All": recently played grid, then Liked songs and your playlists as one list, YouTube Music style. */
private fun LazyListScope.allView(vm: AppViewModel, songs: List<Track>, playlists: List<UserPlaylist>, history: List<Track>, unfiltered: Boolean) {
    val recent = history.take(6)
    if (recent.isNotEmpty()) {
        item { SectionHeader("Recently played", Modifier.padding(top = 4.dp), action = if (unfiltered) "See all" else null) { vm.libraryTab = "history" } }
        recent.chunked(3).forEach { row ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Column(Modifier.weight(1f).clickable { vm.playTracks(history, history.indexOf(t)) }) {
                            RemoteImage(t.art, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(t.title, Modifier.padding(top = 6.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Brand.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    item { Spacer(Modifier.height(10.dp)) }
    if (unfiltered || songs.isNotEmpty()) item { LikedSongsRow(vm, songs) }
    items(playlists, key = { "all-pl-" + it.id }) { p -> PlaylistRow(vm, p) }
    if (!unfiltered && songs.isNotEmpty()) {
        itemsIndexed(songs, key = { _, t -> "all-fav-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(songs, i) }) }
    }
}

/** The pinned "Liked songs" auto-playlist, with a brand-gradient tile instead of art. */
@Composable
private fun LikedSongsRow(vm: AppViewModel, songs: List<Track>) {
    Row(Modifier.fillMaxWidth().clickable { vm.libraryTab = "songs" }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).background(Brush.linearGradient(listOf(Brand.Pink, Brand.Violet))), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Favorite, null, Modifier.size(30.dp), tint = Brand.Paper)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Liked songs", color = Brand.Paper, fontWeight = FontWeight.Bold)
            Text("Auto playlist · ${songs.size} song${if (songs.size == 1) "" else "s"}", color = Brand.Muted, fontSize = 13.sp)
        }
        if (songs.isNotEmpty()) {
            Text("SHUFFLE", Modifier.clickable { vm.playTracks(songs, 0, shuffle = true) }.padding(8.dp), color = Brand.Gold, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlaylistRow(vm: AppViewModel, p: UserPlaylist) {
    Row(Modifier.fillMaxWidth().clickable { vm.openUserPlaylist(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(p.tracks.firstOrNull()?.art, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.name, color = Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("Playlist", if (p.fromYouTube) "From YouTube" else null, "${p.tracks.size} song${if (p.tracks.size == 1) "" else "s"}").joinToString(" · "), color = Brand.Muted, fontSize = 13.sp)
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
