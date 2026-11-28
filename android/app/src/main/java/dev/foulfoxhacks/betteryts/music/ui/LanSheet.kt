package dev.foulfoxhacks.betteryts.music.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle

/**
 * "Link nearby devices": host a BTLink room on this Wi-Fi (QR + invite link, member list, lock),
 * or join one by pasting/opening an invite. Each device plays its own official player; the link
 * keeps them together.
 */
@Composable
fun LanSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val s = vm.lan
    var invite by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text("Link nearby devices", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    s == null -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Play in sync with phones and computers on the same Wi-Fi: no internet round trip, encrypted, and only devices with your invite can join.", color = Brand.Muted, fontSize = 13.sp)
                            BrandButton("Host on this network", { vm.hostLan() }, primary = true, icon = AppIcons.Together)
                            Text("Or join with an invite", color = Brand.Paper, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                invite, { invite = it.take(300) }, Modifier.fillMaxWidth(), singleLine = true,
                                placeholder = { Text("btlink:1:…", color = Brand.Muted) },
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper),
                            )
                            BrandButton("Join", { vm.joinLan(invite) }, enabled = invite.startsWith("btlink:") || invite.contains("link?i="))
                            Text("Tip: opening a shared invite link joins automatically.", color = Brand.Muted, fontSize = 12.sp)
                        }
                    }
                    s.role == "host" -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Hosting · ${s.peers.size} linked", color = Brand.Paper, fontWeight = FontWeight.Bold)
                            s.invite?.let { inv ->
                                QrCode(vm.lanInviteLink(inv), Modifier.size(200.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BrandButton("Share invite", {
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join my BetterYTS link (same Wi-Fi): ${vm.lanInviteLink(inv)}")
                                        context.startActivity(Intent.createChooser(send, "Share invite"))
                                    }, icon = AppIcons.Copy)
                                }
                                Text("Anyone with this invite can join while you host. Share it in person.", color = Brand.Muted, fontSize = 12.sp)
                            }
                            PickRow("Only I control playback", s.locked) { vm.setLanLocked(it) }
                            s.peers.forEach { (name, fp) ->
                                Column(Modifier.fillMaxWidth()) {
                                    Text(name, color = Brand.Paper, fontWeight = FontWeight.Bold)
                                    Text(fp, color = Brand.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            vm.lanFingerprint?.let { Text("This device: $it", color = Brand.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                    else -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val host = s.peers.firstOrNull()
                            Text(if (host == null) "Connecting…" else "Linked with ${host.first}", color = Brand.Paper, fontWeight = FontWeight.Bold)
                            host?.let { Text("Host: ${it.second}", color = Brand.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            s.rttMs?.let { Text("Round trip ${"%.1f".format(it)} ms", color = Brand.Muted, fontSize = 12.sp) }
                            Text("The host picks the music. Play, pause and seek here ask the host (unless they've locked controls).", color = Brand.Muted, fontSize = 12.sp)
                            vm.lanFingerprint?.let { Text("This device: $it", color = Brand.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
                if (s != null) item { BrandButton(if (s.role == "host") "Stop hosting" else "Leave", { vm.leaveLan() }, icon = AppIcons.Close) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", style = ButtonTextStyle, color = Brand.Gold) } },
    )
}
