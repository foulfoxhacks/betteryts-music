package dev.foulfoxhacks.betteryts.music.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.foulfoxhacks.betteryts.music.R
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

/** Round avatar: your Google channel's initial when connected, Sammy's fox otherwise. */
@Composable
fun Avatar(vm: AppViewModel, size: Int, onClick: (() -> Unit)? = null) {
    val m = Modifier.size(size.dp).clip(CircleShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    val name = vm.googleChannel
    if (vm.googleConnected && !name.isNullOrBlank()) {
        Box(m.background(Brand.Gold), contentAlignment = Alignment.Center) {
            Text(name.first().uppercase(), color = Brand.GoldInk, fontWeight = FontWeight.Black, fontSize = (size * 0.45).sp)
        }
    } else {
        Image(painterResource(R.drawable.ic_launcher_art), "Account", m.border(1.dp, Brand.LineGold, CircleShape), contentScale = ContentScale.Crop)
    }
}

/** YouTube Music-style account panel, in the BetterYTS look, with your listening recap. */
@Composable
fun AccountSheet(vm: AppViewModel) {
    val context = LocalContext.current
    var showRecap by remember { mutableStateOf(false) }
    fun close() {
        vm.accountOpen = false
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .background(Brand.Surface)
                .border(1.dp, Brand.LineGold)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close) { Icon(AppIcons.Close, "Close", tint = Brand.Paper) }
                Eyebrow("Account", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(vm, 52)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(if (vm.googleConnected) vm.displayName else "Not signed in", color = Brand.Paper, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (vm.googleConnected) "YouTube account connected" else "Everything stays on this phone",
                        color = Brand.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (!vm.googleConnected) {
                Text(
                    "Connect your YouTube account",
                    Modifier.padding(start = 86.dp, bottom = 10.dp).clickable { close(); vm.selectTab(Tab.Library) },
                    color = Brand.Gold,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Line))

            SheetRow(AppIcons.Library, "Your library") { close(); vm.libraryTab = "all"; vm.selectTab(Tab.Library) }
            SheetRow(AppIcons.History, "History") { close(); vm.libraryTab = "history"; vm.selectTab(Tab.Library) }
            SheetRow(AppIcons.Recap, "Your recap") { showRecap = !showRecap }
            if (showRecap) Recap(vm)
            if (vm.pluginOn("together")) {
                SheetRow(AppIcons.Together, if (vm.room != null) "Listening together · ${vm.room?.code}" else "Listen together") {
                    close()
                    if (vm.room != null) vm.playerExpanded = true else vm.joinRoomDialog = true
                }
            }
            SheetRow(AppIcons.Settings, "Settings") { close(); vm.openSettings() }
            SheetRow(AppIcons.Help, "Help & feedback") {
                close()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/foulfoxhacks/betteryts/issues"))) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Line))
            Text(
                "BetterYTS Music by FoulFoxHacks",
                Modifier.fillMaxWidth().padding(16.dp),
                color = Brand.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = Brand.Paper)
        Spacer(Modifier.width(20.dp))
        Text(label, color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Top artists from your recent listening; nothing leaves the phone. */
@Composable
private fun Recap(vm: AppViewModel) {
    val top = vm.recap
    Column(Modifier.fillMaxWidth().padding(start = 64.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${vm.library.history.size} songs in your recent history", color = Brand.Muted, fontSize = 12.sp)
        if (top.isEmpty()) Text("Play some music and your top artists show up here.", color = Brand.Muted, fontSize = 13.sp)
        top.forEachIndexed { i, (artist, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(22.dp), color = Brand.Gold, fontWeight = FontWeight.Black)
                Text(artist, Modifier.weight(1f), color = Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$n song${if (n == 1) "" else "s"}", color = Brand.Muted, fontSize = 12.sp)
            }
        }
    }
}
