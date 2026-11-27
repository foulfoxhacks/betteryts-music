package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.R
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.Palettes

@Composable
fun SwitchRow(title: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Brand.Paper, fontWeight = FontWeight.Bold)
            Text(description, color = Brand.Muted, fontSize = 13.sp)
        }
        Switch(checked, onToggle, colors = SwitchDefaults.colors(checkedThumbColor = Brand.GoldInk, checkedTrackColor = Brand.Gold))
    }
}

/** Settings › Personalize: accent, colours from the album art, start tab, autoplay. */
@Composable
fun PersonalizeSection(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Accent", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Palettes.byId(vm.settings.theme).accent)
                        .border(if (vm.accent == null) 3.dp else 1.dp, if (vm.accent == null) Brand.Paper else Brand.Line, CircleShape)
                        .clickable { vm.chooseAccent(null) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", color = Brand.Ink, fontWeight = FontWeight.Black, fontSize = 12.sp) }
            }
            items(Palettes.accents) { argb ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                        .border(if (vm.accent == argb) 3.dp else 0.dp, Brand.Paper, CircleShape)
                        .clickable { vm.chooseAccent(argb) },
                )
            }
        }
        Text("“A” uses the theme's own accent.", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 11.sp)
        SwitchRow("Album colours", "The accent follows the cover of the song that's playing.", vm.albumColors) { vm.updateAlbumColors(it) }
        SwitchRow("Autoplay", "When the queue ends, a station keeps the music going.", vm.autoplay) { vm.updateAutoplay(it) }
        Text("Open the app on", Modifier.padding(start = 16.dp, top = 6.dp), color = Brand.Muted, fontSize = 13.sp)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Tab.Home to "Home", Tab.Explore to "Explore", Tab.Library to "Library").forEach { (t, label) ->
                Chip(label, vm.startTab == t) { vm.chooseStartTab(t) }
            }
        }
    }
}

/** Settings › About: logo, developer credits, privacy statement and third-party notices. */
@Composable
fun AboutSection(vm: AppViewModel) {
    var privacy by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.brand_logo_dark), "BetterYTS logo", Modifier.fillMaxWidth(0.6f).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)))
        Text("Developed by FoulFoxHacks", color = Brand.Paper, fontWeight = FontWeight.Bold)
        Text("Design, Rust core and Android app by FoulFoxHacks, for the BetterYTS ecosystem (yts.foulfoxhacks.dev).", color = Brand.Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton("Privacy", { privacy = true })
            BrandButton("Notices", { notices = true })
        }
    }
    if (privacy) TextDialog("Privacy", PRIVACY) { privacy = false }
    if (notices) TextDialog("Third-party notices", NOTICES) { notices = false }
}

@Composable
private fun TextDialog(title: String, body: List<Pair<String, String>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(body) { (head, text) ->
                    Column {
                        Text(head, color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(text, color = Brand.Muted, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", style = ButtonTextStyle, color = Brand.Gold) } },
    )
}

/** Kept in step with PRIVACY.md at the repository root. */
private val PRIVACY = listOf(
    "In short" to "No account, no ads, no analytics, no tracking. Your library lives on this phone.",
    "Stays on this phone" to "Liked songs, playlists, history, followed artists, stations, smart playlists, search history, listening stats and settings. Backups are files you save yourself; the app never uploads one.",
    "BetterYTS service" to "Searches, song/playlist/artist look-ups, skip segments and dislike counts go to the BetterYTS API (yts.foulfoxhacks.dev). It sees the search or ID and your IP address, like any website. No account is tied to it.",
    "YouTube" to "Songs play in YouTube's official embedded player (youtube-nocookie.com), so Google receives what that player sends. Radio and stations ask YouTube Music (music.youtube.com) directly for song lists.",
    "Lyrics" to "Song title and artist are sent to LRCLIB to find lyrics, when the Lyrics plugin is on.",
    "Only if you use them" to "Connect with Google or Spotify (read-only import; tokens stay on the phone), Listen Together (room messages go to BetterYTS), Send to a VRChat world (only the songs you pick: IDs, titles, channels), scrobbling (only to the service you connect).",
    "Updates" to "The sideload build checks yts.foulfoxhacks.dev for new versions every few hours. The Play build updates through Google Play.",
    "Diagnostics" to "Settings › Diagnostics is local. A copied report leaves out song IDs, searches, tokens and room codes, and is only shared if you paste it somewhere.",
    "Permissions" to "Internet; notifications (playback controls); music files (only for On this phone, when you allow it); install packages (sideload updater only). The app does not ask for your location, contacts, microphone or camera.",
    "Delete everything" to "Clear the app's storage in Android settings, or uninstall. Disconnect Google in Library to revoke its token.",
)

private val NOTICES = listOf(
    "YouTube" to "Playback uses YouTube's IFrame Player API under YouTube's API Services Terms.",
    "SponsorBlock" to "Skip segment data from SponsorBlock (sponsor.ajay.app), CC BY-NC-SA 4.0.",
    "Return YouTube Dislike" to "Dislike estimates from returnyoutubedislike.com.",
    "LRCLIB" to "Lyrics from lrclib.net.",
    "Open source" to "Rust: serde, serde_json, ureq, rustls, jni (MIT/Apache-2.0). Android: Jetpack Compose, AndroidX, Media3, OkHttp (Apache-2.0).",
)

/** Settings › Playback extras: skip disliked songs, SponsorBlock categories, scrobbling. */
@Composable
fun PlaybackExtras(vm: AppViewModel) {
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow("Skip songs you disliked", "Thumbs-down a song (player or ⋮ menu): stations never play it, and the queue skips it.", vm.skipDisliked) { vm.updateSkipDisliked(it) }
        if (vm.pluginOn("sponsorblock")) {
            Text("SponsorBlock skips", Modifier.padding(start = 16.dp, top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold)
            Text("Community-marked parts of videos to jump over. Each skip can be undone from the message.", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.settings.sponsorCatalog) { (id, name) ->
                    val on = id in vm.settings.sponsorCategories
                    Chip(name, on) { vm.setSponsorCategory(id, !on) }
                }
            }
        }
        Text("Scrobbling", Modifier.padding(start = 16.dp, top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold)
        if (vm.settings.scrobbling) {
            Text("Sending what you play to ListenBrainz${vm.scrobbleUser?.let { " as $it" } ?: ""} (songs you play at least halfway or 4 minutes).", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp)
            BrandButton("Disconnect", { vm.disconnectScrobbling() }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        } else {
            Text("Keep a listening history on ListenBrainz (open, non-profit). Paste your user token from listenbrainz.org › Settings. It stays on this phone.", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    token, { token = it.trim().take(100) }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("ListenBrainz token", color = Brand.Muted) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper),
                )
                BrandButton("Connect", { vm.connectScrobbling(token); token = "" }, primary = true, enabled = token.length >= 8)
            }
        }
    }
}
