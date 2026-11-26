package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.EyebrowStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.displayStyle

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = EyebrowStyle)
}

/** Page headline: heavy, sentence case, solid colour (Mello Zone's "Welcome home"). */
@Composable
fun DisplayTitle(text: String, modifier: Modifier = Modifier, size: Int = 34, brush: Brush = Brand.TitleGradient, maxLines: Int = 3) {
    Text(text, modifier, style = displayStyle(minOf(size, 34), brush), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A section title (sentence-case headline) with an optional text action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = Brand.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Brand.Gold, style = ButtonTextStyle) }
    }
}

/** Pill buttons: primary is filled with the accent; secondary is outlined with accent text. */
@Composable
fun BrandButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonTextStyle)
    }
    val pad = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
    if (primary) {
        Button(onClick, modifier, enabled = enabled, shape = CircleShape, contentPadding = pad, content = content,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Gold, contentColor = Brand.GoldInk))
    } else {
        OutlinedButton(onClick, modifier, enabled = enabled, shape = CircleShape, contentPadding = pad, content = content,
            border = BorderStroke(1.dp, Brand.Muted.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Gold))
    }
}

/** Pill filter chip: filled accent when selected, a hairline outline otherwise. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Brand.Gold) else Modifier.border(1.dp, Brand.Muted.copy(alpha = 0.35f), CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (selected) Brand.GoldInk else Brand.Paper)
    }
}

/** A flat, rounded surface for grouped content (replaces the old framed panels). */
fun Modifier.card(): Modifier = this.clip(RoundedCornerShape(16.dp)).background(Brand.Surface)

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Brand.Gold, strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).card().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = Brand.Paper, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) BrandButton("Try again", onRetry)
    }
}

@Composable
fun EmptyNote(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .card()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Brand.Paper)
        Text(body, color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
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
        RemoteImage(track.art, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
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
            if (vm.pluginOn("radio") && !track.isLocal) MenuItem("Start radio", close) { vm.startRadio(track) }
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
        RemoteImage(track.art, Modifier.size(144.dp).clip(RoundedCornerShape(14.dp)))
        Text(track.title, Modifier.padding(top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(track.artist, color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CollectionRow(c: Collection, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(c.thumbnail, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
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
        containerColor = Brand.Raised,
        title = { Text("Add to playlist", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
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
        dismissButton = { TextButton(onClick = { vm.playlistPickerFor = null }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
    )
}

@Composable
fun JoinRoomDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val valid = Regex("^[A-HJ-NP-Z2-9]{6}$").matches(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text("Join a room", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the 6-character code from a BetterYTS watch party (web, app or VRChat).", color = Brand.Muted, fontSize = 13.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(6) },
                    singleLine = true,
                    placeholder = { Text("ABC234", color = Brand.Muted) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = Brand.Paper),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Muted.copy(alpha = 0.45f), cursorColor = Brand.Gold),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onJoin(code) }, enabled = valid) { Text("Join", style = ButtonTextStyle, color = if (valid) Brand.Gold else Brand.Muted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
    )
}

@Composable
fun NamePlaylistDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                placeholder = { Text("Playlist name", color = Brand.Muted) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Muted.copy(alpha = 0.45f), cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm, style = ButtonTextStyle, color = Brand.Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
    )
}
