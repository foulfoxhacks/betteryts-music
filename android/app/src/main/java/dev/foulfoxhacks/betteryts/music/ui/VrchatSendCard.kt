package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

/**
 * Library card: send playlists to a VRChat world that shows an activation code. Same flow as the
 * website's "Send music to a VRChat world" page. It's a handoff to BetterYTS-compatible worlds,
 * not a VRChat account link: the app never sees your VRChat account.
 */
@Composable
fun VrchatSendCard(vm: AppViewModel) {
    val s = vm.worldSend
    var code by remember(s.prefill) { mutableStateOf(s.prefill) }
    val picked = remember { mutableStateListOf<String>() }
    var liked by remember { mutableStateOf(true) }
    var queue by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper)
    val canSave = s.capabilities.isEmpty() || "saved_library" in s.capabilities

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).card().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow("Send to a VRChat world")
        Text("In a world with BetterYTS, press Link YouTube or Link Spotify and enter the code it shows. Your playlists appear there, and the world can remember them next visit.", color = Brand.Paper, fontSize = 14.sp)
        if (s.sent != null) {
            Text(s.sent, color = Brand.Paper, fontSize = 14.sp)
            BrandButton("Link another world", { vm.resetWorldSend() })
            RecentSends(vm)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it.uppercase().take(9) }, Modifier.weight(1f), placeholder = { Text("ABCD-1234", color = Brand.Muted) }, singleLine = true, colors = colors)
            BrandButton(if (s.checkedCode != null) "Checked" else "Check", { vm.checkWorldCode(code) }, primary = s.checkedCode == null, enabled = !s.busy && code.isNotBlank())
        }
        if (s.checkedCode != null) {
            Text("Linked to a ${if (s.service == "spotify") "Spotify" else "YouTube"} player${if ("listen_together" in s.capabilities) " · supports Listen Together" else ""}.", color = Brand.Muted, fontSize = 12.sp)
            if (vm.queue.tracks.isNotEmpty()) {
                PickRow("Current queue · ${vm.queue.tracks.size}", queue) { queue = it }
            }
            if (vm.library.favorites.isNotEmpty()) {
                PickRow("Liked songs · ${vm.library.favorites.size}", liked) { liked = it }
            }
            vm.library.playlists.forEach { p ->
                PickRow("${p.name} · ${p.tracks.size}", p.id in picked) { on -> if (on) picked.add(p.id) else picked.remove(p.id) }
            }
            if (canSave) PickRow("Save for my next visit (up to 30 days; Unlink in the world deletes it). Off: just this visit.", save) { save = it }

            // What will be sent, counted by the core before anything leaves the phone.
            LaunchedEffect(picked.toList(), liked, queue) { vm.previewWorldSend(picked.toList(), liked, queue) }
            s.preview?.let { pv ->
                Column(Modifier.fillMaxWidth().card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Will send ${pv.lists.count { it.second > 0 }} playlist${if (pv.lists.count { it.second > 0 } == 1) "" else "s"}, ${pv.totalTracks} songs (${(pv.bytes + 1023) / 1024} KB)", color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (pv.unsupported > 0) Text("${pv.unsupported} left out: songs on this phone can't play in a world", color = Brand.Muted, fontSize = 12.sp)
                    if (pv.duplicates > 0) Text("${pv.duplicates} repeats left out", color = Brand.Muted, fontSize = 12.sp)
                    if (pv.overLimit > 0) Text("${pv.overLimit} past the 200-songs-per-playlist limit", color = Brand.Muted, fontSize = 12.sp)
                    if (pv.playlistsLeftOut > 0) Text("${pv.playlistsLeftOut} playlists past the 20-playlist limit", color = Brand.Ember, fontSize = 12.sp)
                    Text(pv.sends, color = Brand.Muted, fontSize = 12.sp)
                }
            }
            val nothing = picked.isEmpty() && !(liked && vm.library.favorites.isNotEmpty()) && !(queue && vm.queue.tracks.isNotEmpty())
            BrandButton(
                if (s.busy) "Sending…" else "Send to the world",
                { vm.sendToWorld(picked.toList(), liked, save && canSave, queue) },
                primary = true,
                enabled = !s.busy && !nothing && (s.preview?.totalTracks ?: 1) > 0,
            )
        }
        s.error?.let { Text(it, color = Brand.Ember, fontSize = 13.sp) }
        RecentSends(vm)
    }
}

/** What you sent lately (never the codes), with when saved handoffs expire. */
@Composable
private fun RecentSends(vm: AppViewModel) {
    val sends = vm.library.worldSends
    if (sends.isEmpty()) return
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Recent sends", color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        sends.take(5).forEach { w ->
            val ago = ((now - w.at) / 86_400_000L).let { d -> if (d <= 0) "today" else if (d == 1L) "yesterday" else "$d days ago" }
            val keep = when {
                !w.saved -> "this visit only"
                (w.expiresAt ?: 0) <= now -> "expired"
                else -> {
                    val left = ((w.expiresAt ?: now) - now) / 86_400_000L
                    if (left <= 3) "expires in ${left.coerceAtLeast(0)} day${if (left == 1L) "" else "s"}" else "saved $left more days"
                }
            }
            Text("$ago · ${w.playlists} playlists, ${w.tracks} songs · $keep", color = Brand.Muted, fontSize = 12.sp)
        }
        Text("Forget this list", Modifier.clickable { vm.forgetWorldSends() }.padding(vertical = 4.dp), color = Brand.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PickRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Brand.Gold, checkmarkColor = Brand.GoldInk, uncheckedColor = Brand.Muted))
        Text(label, color = Brand.Paper, fontSize = 14.sp)
    }
}
