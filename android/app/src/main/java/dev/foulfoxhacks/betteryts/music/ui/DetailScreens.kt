package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Collection
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle

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
                            Box(Modifier.size(88.dp).clip(CircleShape).border(3.dp, Brand.Gold, CircleShape)) {
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
                            BrandButton(if (vm.settings.hidesArtist(page.artist.name, page.artist.id)) "Unhide" else "Hide", { vm.toggleHideArtist(page.artist) }, primary = false)
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
                            RemoteImage(page.thumbnail ?: c.thumbnail ?: page.tracks.firstOrNull()?.largeArt, Modifier.size(200.dp).border(1.dp, Brand.LineGold))
                            Eyebrow(if (c.isMix) "Mix" else "Playlist")
                            DisplayTitle(page.title, size = 32)
                            if (page.subtitle.isNotBlank()) Text(page.subtitle, color = Brand.Muted, fontSize = 13.sp)
                        }
                        PlayShuffle(onPlay = { vm.playTracks(page.tracks, 0) }, onShuffle = { vm.playTracks(page.tracks, 0, shuffle = true) })
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrandButton("Save", { vm.createPlaylist(page.title, page.tracks) }, icon = AppIcons.PlaylistAdd)
                            BrandButton("Queue all", { vm.addAllToQueue(page.tracks) }, icon = AppIcons.QueueMusic)
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
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteImage(p.tracks.firstOrNull()?.largeArt, Modifier.size(200.dp).border(1.dp, Brand.LineGold))
                    Eyebrow("Your playlist")
                    DisplayTitle(p.name, size = 32)
                    Text("${p.tracks.size} song${if (p.tracks.size == 1) "" else "s"}", color = Brand.Muted, fontSize = 13.sp)
                }
                if (p.tracks.isNotEmpty()) PlayShuffle(onPlay = { vm.playTracks(p.tracks, 0) }, onShuffle = { vm.playTracks(p.tracks, 0, shuffle = true) })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton("Rename", { renaming = true }, icon = AppIcons.Edit)
                    BrandButton("Delete", { deleting = true }, icon = AppIcons.Delete)
                }
                if (p.tracks.isEmpty()) EmptyNote("Empty playlist", "Use ⋮ → Add to playlist… on any song.")
            }
            itemsIndexed(p.tracks, key = { i, t -> "u-$i-" + t.id }) { i, t ->
                TrackRow(vm, t, onClick = { vm.playTracks(p.tracks, i) }, leading = "${i + 1}", extraActions = listOf("Remove from this playlist" to { vm.removeFromPlaylist(p.id, i) }))
            }
        }
    }
    if (renaming) {
        NamePlaylistDialog("Rename playlist", p.name, "Rename", onDismiss = { renaming = false }) { name ->
            renaming = false
            vm.renamePlaylist(p.id, name)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Brand.Surface,
            title = { Text("DELETE PLAYLIST?", style = LabelStyle, color = Brand.Tan) },
            text = { Text("“${p.name}” will be removed from this phone. This can't be undone.", color = Brand.Paper) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deletePlaylist(p.id) }) { Text("DELETE", style = LabelStyle, color = Brand.Ember) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("CANCEL", style = LabelStyle, color = Brand.Muted) } },
        )
    }
}
