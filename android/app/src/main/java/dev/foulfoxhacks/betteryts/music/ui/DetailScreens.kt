package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Collection
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

/** Back arrow row used at the top of pushed pages. */
@Composable
fun PageTopBar(vm: AppViewModel, top: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth().padding(top = top).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.back() }) { Icon(AppIcons.Back, "Back", tint = Brand.Paper) }
    }
}

private fun LazyListScope.loadMoreFooter(key: Int, loading: Boolean, hasMore: Boolean, load: () -> Unit) {
    if (!hasMore) return
    item {
        LaunchedEffect(key) { load() }
        if (loading) Loading()
    }
}

@Composable
fun ArtistScreen(vm: AppViewModel, route: Route.ArtistRoute, pad: PaddingValues) {
    val state = vm.artistPages[route.id] ?: Load.Loading
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        when (state) {
            Load.Loading -> {
                if (route.name != null) DisplayTitle(route.name, Modifier.padding(horizontal = 16.dp), size = 40)
                Loading()
            }
            is Load.Failed -> ErrorBox(state.message) { vm.loadArtist(route.id) }
            is Load.Ready -> {
                val page = state.value
                val following = vm.library.isFollowing(page.artist.id) || vm.library.isFollowing(page.artist.handle)
                LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
                    item {
                        Box {
                            if (page.banner != null) RemoteImage(page.banner, Modifier.fillMaxWidth().aspectRatio(16f / 5f))
                            else Box(Modifier.fillMaxWidth().aspectRatio(16f / 5f).background(Brand.HeadingGradient))
                        }
                        Row(Modifier.padding(horizontal = 16.dp).offset(y = (-28).dp), verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.size(88.dp).clip(CircleShape)) {
                                RemoteImage(page.artist.thumbnail, Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.padding(horizontal = 16.dp).offset(y = (-16).dp)) {
                            Eyebrow("Artist")
                            DisplayTitle(page.artist.name, size = 40)
                            page.artist.subtitle?.let { Text(it, color = Brand.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                        }
                        PlayShuffle(
                            onPlay = { vm.playTracks(page.tracks, 0) },
                            onShuffle = { vm.playTracks(page.tracks, 0, shuffle = true) },
                        ) {
                            BrandButton(if (following) "Following" else "Follow", { vm.toggleFollow(page.artist) }, primary = false)
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (vm.pluginOn("radio")) item { BrandButton("Radio", { vm.startArtistRadio(page.artist) }, icon = AppIcons.Radio) }
                            if (following) {
                                val key = "artist:" + page.artist.id
                                item { BrandButton(if (vm.library.isPinned(key)) "Unpin" else "Pin", { vm.togglePin(key, page.artist.name) }, icon = AppIcons.Pin) }
                            }
                            item { BrandButton(if (vm.settings.hidesArtist(page.artist.name, page.artist.id)) "Unhide" else "Hide", { vm.toggleHideArtist(page.artist) }, primary = false) }
                        }
                        SectionHeader("Songs")
                    }
                    itemsIndexed(page.tracks, key = { _, t -> "ar-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(page.tracks, i) }) }
                    loadMoreFooter(page.tracks.size, state.loadingMore, page.next != null) { vm.loadMoreArtist(route.id) }
                }
            }
        }
    }
}

@Composable
fun CollectionScreen(vm: AppViewModel, c: Collection, pad: PaddingValues) {
    val state = vm.collectionPages[c.id] ?: Load.Loading
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        when (state) {
            Load.Loading -> Loading()
            is Load.Failed -> {
                ErrorBox(state.message) { vm.loadCollection(c) }
                if (c.firstVideoId != null) BrandButton("Play the first song", { vm.playCollection(c.copy(collectionType = "mix")) }, Modifier.padding(16.dp))
            }
            is Load.Ready -> {
                val page = state.value
                LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoteImage(page.thumbnail ?: c.thumbnail ?: page.tracks.firstOrNull()?.largeArt, Modifier.size(200.dp).clip(RoundedCornerShape(20.dp)))
                            Eyebrow(if (c.isMix) "Mix" else "Playlist")
                            DisplayTitle(page.title, size = 32)
                            if (page.subtitle.isNotBlank()) Text(page.subtitle, color = Brand.Muted, fontSize = 13.sp)
                        }
                        PlayShuffle(onPlay = { vm.playTracks(page.tracks, 0) }, onShuffle = { vm.playTracks(page.tracks, 0, shuffle = true) })
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { BrandButton("Save", { vm.createPlaylist(page.title, page.tracks) }, icon = AppIcons.PlaylistAdd) }
                            if (vm.pluginOn("radio") && !c.isMix) item { BrandButton("Radio", { vm.startCollectionRadio(c.copy(title = page.title, thumbnail = page.thumbnail ?: c.thumbnail)) }, icon = AppIcons.Radio) }
                            item { BrandButton("Queue all", { vm.addAllToQueue(page.tracks) }, icon = AppIcons.QueueMusic) }
                            item { BrandButton("Like all", { vm.likeAll(page.tracks) }, icon = AppIcons.Favorite) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    itemsIndexed(page.tracks, key = { i, t -> "c-$i-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(page.tracks, i) }, leading = "${i + 1}") }
                    loadMoreFooter(page.tracks.size, state.loadingMore, page.next != null) { vm.loadMoreCollection(c.id) }
                }
            }
        }
    }
}

@Composable
fun UserPlaylistScreen(vm: AppViewModel, id: String, pad: PaddingValues) {
    val p = vm.library.playlist(id)
    if (p == null) {
        Column(Modifier.fillMaxSize()) {
            PageTopBar(vm, pad.calculateTopPadding())
            EmptyNote("Playlist not found", "It may have been deleted.")
        }
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    /** Multi-select: positions picked for a batch remove. */
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    val pinKey = "playlist:" + p.id
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteImage(p.tracks.firstOrNull()?.largeArt, Modifier.size(200.dp).clip(RoundedCornerShape(20.dp)))
                    Eyebrow(listOfNotNull("Your playlist", p.sourceLabel).joinToString(" · "))
                    DisplayTitle(p.name, size = 32)
                    val minutes = p.tracks.sumOf { it.durationSeconds ?: 0 } / 60
                    Text(listOfNotNull("${p.tracks.size} song${if (p.tracks.size == 1) "" else "s"}", if (minutes > 0) "about ${if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"}" else null).joinToString(" · "), color = Brand.Muted, fontSize = 13.sp)
                }
                if (p.tracks.isNotEmpty() && !selecting) PlayShuffle(onPlay = { vm.playTracks(p.tracks, 0) }, onShuffle = { vm.playTracks(p.tracks, 0, shuffle = true) })
                if (selecting) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size} selected", Modifier.weight(1f), color = Brand.Paper, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        BrandButton("Remove", { vm.removeManyFromPlaylist(p.id, selected.toList()); selected.clear(); selecting = false }, primary = true, enabled = selected.isNotEmpty(), icon = AppIcons.Delete)
                        BrandButton("Done", { selected.clear(); selecting = false })
                    }
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.pluginOn("radio") && p.tracks.any { !it.isLocal }) item { BrandButton("Radio", { vm.startPlaylistRadio(p) }, icon = AppIcons.Radio) }
                        item { BrandButton(if (vm.library.isPinned(pinKey)) "Unpin" else "Pin", { vm.togglePin(pinKey, p.name) }, icon = AppIcons.Pin) }
                        if (p.tracks.size > 1) item { BrandButton("Select", { selecting = true }, icon = AppIcons.Select) }
                        item { BrandButton("Rename", { renaming = true }, icon = AppIcons.Edit) }
                        if (p.tracks.size > 1) item { BrandButton("Remove repeats", { vm.dedupePlaylist(p.id) }) }
                        if (vm.library.playlists.size > 1) item { BrandButton("Merge…", { merging = true }) }
                        item { BrandButton("Copy", { vm.duplicatePlaylist(p.id) }, icon = AppIcons.Copy) }
                        item { BrandButton("Delete", { deleting = true }, icon = AppIcons.Delete) }
                    }
                }
                if (p.tracks.isEmpty()) EmptyNote("Empty playlist", "Use ⋮ → Add to playlist… on any song.")
            }
            itemsIndexed(p.tracks, key = { i, t -> "u-$i-" + t.id }) { i, t ->
                if (selecting) {
                    PickRow("${i + 1}. ${t.title} · ${t.artist}", i in selected) { on -> if (on) selected.add(i) else selected.remove(i) }
                } else {
                    TrackRow(
                        vm, t, onClick = { vm.playTracks(p.tracks, i) }, leading = "${i + 1}",
                        extraActions = buildList<Pair<String, () -> Unit>> {
                            add("Remove from this playlist" to { vm.removeFromPlaylist(p.id, i); Unit })
                            if (i > 0) add("Move up" to { vm.movePlaylistTrack(p.id, i, i - 1); Unit })
                            if (i < p.tracks.lastIndex) add("Move down" to { vm.movePlaylistTrack(p.id, i, i + 1); Unit })
                            if (i > 0) add("Move to top" to { vm.movePlaylistTrack(p.id, i, 0); Unit })
                        },
                    )
                }
            }
        }
    }
    if (renaming) {
        NamePlaylistDialog("Rename playlist", p.name, "Rename", onDismiss = { renaming = false }) { name ->
            renaming = false
            vm.renamePlaylist(p.id, name)
        }
    }
    if (merging) {
        var deleteAfter by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { merging = false },
            containerColor = Brand.Raised,
            title = { Text("Merge into “${p.name}”", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pick a playlist: its songs are added here (no repeats).", color = Brand.Muted, fontSize = 13.sp)
                    PickRow("Delete it afterwards", deleteAfter) { deleteAfter = it }
                    LazyColumn(Modifier.height(260.dp)) {
                        items(vm.library.playlists.filter { it.id != p.id }, key = { "m-" + it.id }) { other ->
                            Column(Modifier.fillMaxWidth().clickable { merging = false; vm.mergePlaylists(p.id, other.id, deleteAfter) }.padding(vertical = 10.dp)) {
                                Text(other.name, color = Brand.Paper, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text("${other.tracks.size} songs", color = Brand.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Brand.Surface,
            title = { Text("Delete playlist?", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
            text = { Text("“${p.name}” will be removed from this phone. This can't be undone.", color = Brand.Paper) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deletePlaylist(p.id) }) { Text("Delete", style = ButtonTextStyle, color = Brand.Ember) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
        )
    }
}
