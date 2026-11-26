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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

/**
 * Library card: send playlists to a VRChat world that shows an activation code. Same flow as the
 * website's "Send music to a VRChat world" page; the world keeps them for the next visit.
 */
@Composable
fun VrchatSendCard(vm: AppViewModel) {
    val s = vm.worldSend
    var code by remember(s.prefill) { mutableStateOf(s.prefill) }
    val picked = remember { mutableStateListOf<String>() }
    var liked by remember { mutableStateOf(true) }
    var save by remember { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper)

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).card().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow("Send to a VRChat world")
        Text("In a world with BetterYTS, press Link YouTube or Link Spotify and enter the code it shows. Your playlists appear there, and the world remembers them next visit.", color = Brand.Paper, fontSize = 14.sp)
        if (s.sent != null) {
            Text(s.sent, color = Brand.Paper, fontSize = 14.sp)
            BrandButton("Link another world", { vm.resetWorldSend() })
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it.uppercase().take(9) }, Modifier.weight(1f), placeholder = { Text("ABCD-1234", color = Brand.Muted) }, singleLine = true, colors = colors)
            BrandButton(if (s.checkedCode != null) "Checked" else "Check", { vm.checkWorldCode(code) }, primary = s.checkedCode == null, enabled = !s.busy && code.isNotBlank())
        }
        if (s.checkedCode != null) {
            Text("Pick what to send (up to 20 playlists, 200 songs each). Only the songs' video IDs, titles and channels are sent, never your account.", color = Brand.Muted, fontSize = 12.sp)
            if (vm.library.favorites.isNotEmpty()) {
                PickRow("Liked songs · ${vm.library.favorites.size}", liked) { liked = it }
            }
            vm.library.playlists.forEach { p ->
                PickRow("${p.name} · ${p.tracks.size}", p.id in picked) { on -> if (on) picked.add(p.id) else picked.remove(p.id) }
            }
            PickRow("Save for my next visit (up to 30 days; Unlink deletes it). Off: just this visit.", save) { save = it }
            BrandButton(if (s.busy) "Sending…" else "Send to the world", { vm.sendToWorld(picked.toList(), liked, save) }, primary = true, enabled = !s.busy && (picked.isNotEmpty() || (liked && vm.library.favorites.isNotEmpty())))
        }
        s.error?.let { Text(it, color = Brand.Ember, fontSize = 13.sp) }
    }
}

@Composable
internal fun PickRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Brand.Gold, checkmarkColor = Brand.GoldInk, uncheckedColor = Brand.Muted))
        Text(label, color = Brand.Paper, fontSize = 14.sp)
    }
}
