package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.update.Updater
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/** A slim bar above the player while an update downloads or is ready to install. */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    val s = vm.updater.state
    val show = s is Updater.State.Downloading || s is Updater.State.Downloaded || s is Updater.State.Installing
    AnimatedVisibility(show, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().card().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (s) {
                is Updater.State.Downloading -> {
                    Text("Downloading BetterYTS Music ${s.update.versionName}… ${mb(s.done)} of ${mb(s.update.size)}", color = Brand.Paper, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { s.done.toFloat() / s.update.size }, modifier = Modifier.fillMaxWidth(), color = Brand.Gold, trackColor = Brand.Line)
                }
                is Updater.State.Downloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Version ${s.update.versionName} is ready", color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Checked: same signing key, matching checksum.", color = Brand.Muted, fontSize = 11.sp)
                    }
                    BrandButton("Update", { vm.installUpdate() }, primary = true)
                }
                is Updater.State.Installing -> Text("Installing ${s.update.versionName}… the app restarts when it's done.", color = Brand.Paper, fontSize = 12.sp)
                else -> Unit
            }
        }
    }
}

/** Settings › About: current version, check now, and what happened last. */
@Composable
fun UpdateSettings(vm: AppViewModel) {
    if (!vm.updater.enabled) {
        Text("Updates come from Google Play.", color = Brand.Muted, fontSize = 13.sp)
        return
    }
    val s = vm.updater.state
    val status = when (s) {
        Updater.State.Idle -> "Checks for updates automatically every few hours."
        Updater.State.Checking -> "Checking…"
        Updater.State.UpToDate -> "You're on the latest version."
        is Updater.State.Ready -> "Version ${s.update.versionName} is available."
        is Updater.State.Downloading -> "Downloading ${s.update.versionName}…"
        is Updater.State.Downloaded -> "Version ${s.update.versionName} is ready to install." + if (s.update.notes.isNotBlank()) " ${s.update.notes}" else ""
        is Updater.State.Installing -> "Installing…"
        is Updater.State.Failed -> s.message
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(status, color = if (s is Updater.State.Failed) Brand.Ember else Brand.Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s is Updater.State.Downloaded) BrandButton("Install update", { vm.installUpdate() }, primary = true)
            BrandButton("Check for updates", { vm.checkForUpdate() }, enabled = s !is Updater.State.Checking && s !is Updater.State.Downloading && s !is Updater.State.Installing)
        }
    }
}
