package dev.foulfoxhacks.betteryts.music.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle

/** Library card: connect a YouTube account and import playlists, liked songs and subscriptions. */
@Composable
fun GoogleCard(vm: AppViewModel) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Brand.CardGradient)
            .border(1.dp, Brand.LineGold)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow("YouTube account")
        val busy = vm.googleBusy
        when {
            busy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Brand.Gold, strokeWidth = 2.dp)
                Text(busy, Modifier.padding(start = 12.dp), color = Brand.Paper)
            }

            !vm.googleConnected -> {
                Text("Bring your YouTube and YouTube Music playlists, liked songs and subscriptions into BetterYTS Music.", color = Brand.Paper, fontSize = 14.sp)
                Text("Read-only: nothing on your account is changed. Optional: the app works fine without it.", color = Brand.Muted, fontSize = 12.sp)
                BrandButton("Connect with Google", {
                    try {
                        context.startActivity(vm.googleSignInIntent())
                    } catch (_: ActivityNotFoundException) {
                        vm.message = "No browser found to sign in with"
                    }
                }, primary = true)
            }

            else -> {
                Text(vm.googleChannel?.let { "Connected as $it" } ?: "Connected", color = Brand.Paper, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton("Import", { choosing = true }, primary = true)
                    BrandButton("Disconnect", { confirmDisconnect = true })
                }
            }
        }
    }

    if (choosing) ImportDialog(onDismiss = { choosing = false }) { p, l, s ->
        choosing = false
        vm.importFromGoogle(p, l, s)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = Brand.Surface,
            title = { Text("DISCONNECT YOUTUBE?", style = LabelStyle, color = Brand.Tan) },
            text = { Text("BetterYTS Music's access to your Google account is revoked. Music you've already imported stays in the app.", color = Brand.Paper) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnectGoogle() }) { Text("DISCONNECT", style = LabelStyle, color = Brand.Ember) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("CANCEL", style = LabelStyle, color = Brand.Muted) } },
        )
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (Boolean, Boolean, Boolean) -> Unit) {
    var playlists by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(true) }
    var subs by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Surface,
        title = { Text("IMPORT FROM YOUTUBE", style = LabelStyle, color = Brand.Tan) },
        text = {
            Column {
                Option("Playlists", "Re-importing updates them in place", playlists) { playlists = it }
                Option("Liked songs → Favorites", "Added to your favorites, never removed", liked) { liked = it }
                Option("Subscriptions → Artists you follow", "Their new songs show up on Home", subs) { subs = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(playlists, liked, subs) }, enabled = playlists || liked || subs) { Text("IMPORT", style = LabelStyle, color = Brand.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", style = LabelStyle, color = Brand.Muted) } },
    )
}

@Composable
private fun Option(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Brand.Gold, checkmarkColor = Brand.GoldInk, uncheckedColor = Brand.Muted))
        Column {
            Text(title, color = Brand.Paper, fontSize = 14.sp)
            Text(detail, color = Brand.Muted, fontSize = 12.sp)
        }
    }
}
