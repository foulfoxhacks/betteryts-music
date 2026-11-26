package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import kotlinx.coroutines.launch

/** Library card: paste a Spotify playlist/album link; each song is matched to YouTube and saved as a playlist. */
@Composable
fun SpotifyCard(vm: AppViewModel) {
    var link by remember { mutableStateOf("") }
    val job = vm.spotify
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .card()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow("Import from Spotify")
        Text("Paste a public Spotify playlist or album link. Each song is matched to its YouTube version and saved as one of your playlists.", color = Brand.Paper, fontSize = 14.sp)

        if (job.running) {
            Text(if (job.total == 0) "Reading the playlist…" else "Matching songs… ${job.done} / ${job.total}", color = Brand.Paper, fontSize = 14.sp)
            LinearProgressIndicator(
                progress = { if (job.total == 0) 0f else job.done.toFloat() / job.total },
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Gold,
                trackColor = Brand.Line,
            )
            BrandButton("Cancel", { vm.cancelSpotifyImport() })
        } else {
            OutlinedTextField(
                link,
                { link = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text("open.spotify.com/playlist/…", color = Brand.Muted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.importFromSpotify(link); link = "" }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.Gold,
                    unfocusedBorderColor = Brand.Line,
                    cursorColor = Brand.Gold,
                    focusedTextColor = Brand.Paper,
                    unfocusedTextColor = Brand.Paper,
                ),
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton("Import", { vm.importFromSpotify(link); link = "" }, primary = true, enabled = link.isNotBlank())
                BrandButton("Paste", {
                    scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { link = it.toString().trim() } }
                })
            }
            SpotifySignIn(vm)
            job.error?.let { Text(it, color = Brand.Ember, fontSize = 13.sp) }
            job.summary?.let { Text(it, color = Brand.Paper, fontSize = 14.sp) }
            job.note?.let { Text(it, color = Brand.Muted, fontSize = 12.sp) }
            Expandable("${job.unsure.size} guessed — check these", job.unsure)
            Expandable("${job.missing.size} not found on YouTube", job.missing)
        }
    }
}

@Composable
private fun Expandable(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text((if (open) "▾ " else "▸ ") + title, Modifier.clickable { open = !open }, color = Brand.Tan, fontSize = 13.sp)
    if (open) lines.forEach { Text("• $it", Modifier.padding(start = 12.dp), color = Brand.Muted, fontSize = 12.sp) }
}

/** Sign in with Spotify for private playlists and Liked Songs; the token is used once, never saved. */
@Composable
private fun SpotifySignIn(vm: AppViewModel) {
    val acct = vm.spotifyAccount
    val context = androidx.compose.ui.platform.LocalContext.current
    val picked = androidx.compose.runtime.remember(acct.lists) { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(acct.lists.take(3).map { it.id }) } }
    if (acct.lists.isEmpty()) {
        BrandButton("Sign in with Spotify", { runCatching { context.startActivity(vm.spotifySignInIntent()) } })
        Text("For private playlists and Liked Songs. Read-only; the sign-in is used for this import and not saved.", color = Brand.Muted, fontSize = 12.sp)
    } else {
        Text("Pick playlists to import. Each song is matched to its YouTube version.", color = Brand.Paper, fontSize = 13.sp)
        acct.lists.forEach { l -> PickRow("${l.name} · ${l.count}", l.id in picked) { on -> if (on) picked.add(l.id) else picked.remove(l.id) } }
        BrandButton("Import picked", { vm.importSpotifyPicked(picked.toList()) }, primary = true, enabled = picked.isNotEmpty())
    }
    acct.busy?.let { Text(it, color = Brand.Paper, fontSize = 13.sp) }
    acct.error?.let { Text(it, color = Brand.Ember, fontSize = 13.sp) }
}
