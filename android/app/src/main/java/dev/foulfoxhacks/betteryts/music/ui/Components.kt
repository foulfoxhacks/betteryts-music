package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Artist
import dev.foulfoxhacks.betteryts.music.core.Collection
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.EyebrowStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.displayStyle

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = EyebrowStyle)
}

@Composable
fun DisplayTitle(text: String, modifier: Modifier = Modifier, size: Int = 40, brush: Brush = Brand.TitleGradient, maxLines: Int = 3) {
    Text(text.uppercase(), modifier, style = displayStyle(size, brush), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        DisplayTitle(title, Modifier.weight(1f), size = 24, brush = Brand.HeadingGradient, maxLines = 1)
        if (action != null) {
            Text(action.uppercase(), Modifier.clickable(onClick = onAction).padding(8.dp), style = LabelStyle, color = Brand.Gold)
        }
    }
}

/** Square, uppercase, tracked: primary is solid gold; secondary is the copper gradient with a gold border. */
@Composable
fun BrandButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true) {
    val base = modifier
        .then(if (primary) Modifier.background(if (enabled) Brand.Gold else Brand.Raised) else Modifier.background(Brand.ButtonGradient).border(1.dp, Brand.LineGold))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 11.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val color = if (primary) Brand.GoldInk else Brand.Paper
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = color)
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = LabelStyle, color = color)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .then(if (selected) Modifier.background(Brand.Gold) else Modifier.border(1.dp, Brand.Line))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text.uppercase(), style = LabelStyle, color = if (selected) Brand.GoldInk else Brand.Paper)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Brand.Gold, strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).background(Brand.CardGradient).border(1.dp, Brand.Line).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = Brand.Paper)
        if (onRetry != null) BrandButton("Try again", onRetry)
    }
}

@Composable
fun EmptyNote(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Brand.CardGradient)
            .border(1.dp, Brand.Line)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(), style = LabelStyle, color = Brand.Tan)
        Text(body, color = Brand.Muted, fontSize = 14.sp)
    }
}

/** A song row: square art, title, artist · duration, and a ⋮ menu. */
@Composable
fun TrackRow(
    vm: AppViewModel,
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    leading: String? = null,
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (current) Brand.Selected else Brand.Ink)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), color = if (current) Brand.Gold else Brand.Muted, fontSize = 13.sp)
        }
        RemoteImage(track.art, Modifier.size(52.dp).then(if (current) Modifier.border(2.dp, Brand.Gold) else Modifier))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = if (current) Brand.Gold else Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.subtitle, color = Brand.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (vm.library.isFavorite(track.id)) {
            Icon(AppIcons.Favorite, "Favorite", Modifier.size(16.dp), tint = Brand.Pink)
        }
        TrackMenuButton(vm, track, extraActions)
    }
}

@Composable
fun TrackMenuButton(vm: AppViewModel, track: Track, extraActions: List<Pair<String, () -> Unit>> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AppIcons.More, "More options for ${track.title}", tint = Brand.Muted) }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Brand.Raised) {
            val close = { open = false }
            MenuItem("Play next", close) { vm.playNext(track) }
            MenuItem("Add to queue", close) { vm.addToQueue(track) }
            MenuItem(if (vm.library.isFavorite(track.id)) "Remove from favorites" else "Add to favorites", close) { vm.toggleFavorite(track) }
            MenuItem("Add to playlist…", close) { vm.playlistPickerFor = track }
            if (track.artistId != null) MenuItem("Go to artist", close) { vm.openArtist(track.artistId, track.artist) }
            extraActions.forEach { (label, action) -> MenuItem(label, close, action) }
        }
    }
}

@Composable
private fun MenuItem(label: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = Brand.Paper) }, onClick = { close(); action() })
}

/** Square card for horizontal shelves. */
@Composable
fun TrackCard(track: Track, onClick: () -> Unit) {
    Column(Modifier.width(144.dp).clickable(onClick = onClick)) {
        RemoteImage(track.art, Modifier.size(144.dp))
        Text(track.title, Modifier.padding(top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist, color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CollectionRow(c: Collection, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(c.thumbnail, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(c.title, color = Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The core's mix subtitles already start with "Mix"; playlists get their label here.
            val sub = if (c.isMix) c.subtitle else listOf("Playlist", c.subtitle).filter(String::isNotBlank).joinToString(" · ")
            Text(sub, color = Brand.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ArtistRow(a: Artist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape)) {
            if (a.thumbnail != null) RemoteImage(a.thumbnail, Modifier.size(56.dp))
            else Box(Modifier.size(56.dp).background(Brand.Raised), contentAlignment = Alignment.Center) { Icon(AppIcons.Person, null, tint = Brand.Muted) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(a.name, color = Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.subtitle ?: "Artist", color = Brand.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AddToPlaylistDialog(vm: AppViewModel, track: Track) {
    AlertDialog(
        onDismissRequest = { vm.playlistPickerFor = null },
        containerColor = Brand.Surface,
        title = { Text("ADD TO PLAYLIST", style = LabelStyle, color = Brand.Tan) },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.playlistPickerFor = null; vm.newPlaylistWith = listOf(track) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Add, null, tint = Brand.Gold)
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", color = Brand.Gold, fontWeight = FontWeight.Bold)
                    }
                }
                items(vm.library.playlists, key = { it.id }) { p ->
                    Column(Modifier.fillMaxWidth().clickable { vm.playlistPickerFor = null; vm.addToPlaylist(p.id, track) }.padding(vertical = 10.dp)) {
                        Text(p.name, color = Brand.Paper, fontWeight = FontWeight.Bold)
                        Text("${p.tracks.size} songs", color = Brand.Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.playlistPickerFor = null }) { Text("CANCEL", style = LabelStyle, color = Brand.Muted) } },
    )
}

@Composable
fun NamePlaylistDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Surface,
        title = { Text(title.uppercase(), style = LabelStyle, color = Brand.Tan) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                placeholder = { Text("Playlist name", color = Brand.Muted) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm.uppercase(), style = LabelStyle, color = Brand.Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", style = LabelStyle, color = Brand.Muted) } },
    )
}
