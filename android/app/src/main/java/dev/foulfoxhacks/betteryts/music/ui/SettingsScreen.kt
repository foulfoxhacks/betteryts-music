package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Core
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.Palette
import dev.foulfoxhacks.betteryts.music.ui.theme.Palettes

private fun pluginIcon(id: String): ImageVector = when (id) {
    "sponsorblock" -> AppIcons.Next
    "lyrics" -> AppIcons.Lyrics
    "dislikes" -> AppIcons.ThumbDown
    "sleep" -> AppIcons.Sleep
    "vrchat" -> AppIcons.Headset
    else -> AppIcons.Plugin
}

@Composable
fun SettingsScreen(vm: AppViewModel, pad: PaddingValues) {
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Eyebrow("BetterYTS Music")
                    DisplayTitle("Settings", size = 44)
                }
            }

            item { SectionHeader("Theme") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Palettes.all.forEach { p ->
                        val name = vm.settings.themes.firstOrNull { it.first == p.id }?.second ?: p.id
                        ThemeCard(p, name, selected = Brand.palette.id == p.id, Modifier.weight(1f)) { vm.setTheme(p.id) }
                    }
                }
            }

            item { SectionHeader("Playback") }
            item {
                ToggleRow(
                    icon = AppIcons.Resume,
                    title = "Resume where you left off",
                    description = "Long mixes and songs pick up from where you paused, even after a restart.",
                    checked = vm.resumeEnabled,
                ) { vm.setResume(it) }
            }

            item {
                SectionHeader("Plugins")
                Text(
                    "Built for the BetterYTS ecosystem, grouped by category. Switch a whole category or any plugin off; changes apply right away.",
                    Modifier.padding(horizontal = 16.dp),
                    color = Brand.Muted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
            vm.settings.categories.forEach { c ->
                item(key = "category-" + c.id) { CategoryRow(c) { vm.setCategory(c.id, it) } }
                items(vm.settings.plugins.filter { it.category == c.id }, key = { "plugin-" + it.id }) { p ->
                    // A switched-off category dims its plugins but keeps each one's own switch.
                    Box(Modifier.alpha(if (c.enabled) 1f else 0.45f)) {
                        if (p.community) {
                            CommunityRow(p, vm.settings.permissionText, vm.sandboxErrors[p.id], { vm.setPlugin(p.id, it) }, { vm.removePlugin(p.id) })
                        } else {
                            PluginRow(p) { vm.setPlugin(p.id, it) }
                        }
                    }
                }
            }
            item(key = "community-install") { CommunityInstallCard(vm) }

            item { SectionHeader("Hidden") }
            item { HiddenCard(vm) }

            item { SectionHeader("YouTube account") }
            item { GoogleCard(vm) }

            item { SectionHeader("About") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
                    Text("BetterYTS Music by FoulFoxHacks · v$version", color = Brand.Paper, fontWeight = FontWeight.Bold)
                    Text("Part of the BetterYTS ecosystem: every project and version at yts.foulfoxhacks.dev/ecosystem", color = Brand.Muted, fontSize = 13.sp)
                    Text("Search and pages come from the BetterYTS API (yts.foulfoxhacks.dev). Songs play through YouTube's official player.", color = Brand.Muted, fontSize = 13.sp)
                    Text("Skip data: SponsorBlock (CC BY-NC-SA 4.0). Dislike counts: Return YouTube Dislike. Lyrics: LRCLIB.", color = Brand.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(p: Palette, name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(Brush.linearGradient(p.card))
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.line)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A tiny preview: title gradient, accent button, swatches.
        Text(name.substringBefore(" ·").uppercase(), style = dev.foulfoxhacks.betteryts.music.ui.theme.displayStyle(22, Brush.linearGradient(p.title)))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(p.accent, p.eyebrow, p.violet, p.hot, p.love).forEach { Box(Modifier.size(14.dp).background(it)) }
        }
        Box(Modifier.background(p.accent).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(if (selected) "IN USE" else "USE", style = LabelStyle, fontSize = 10.sp, color = p.onAccent)
        }
        Text(name.substringAfter("· ", ""), color = p.muted, fontSize = 11.sp)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).border(1.dp, Brand.LineGold), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (checked) Brand.Gold else Brand.Muted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Brand.Paper, fontWeight = FontWeight.Bold)
            Text(description, color = Brand.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Brand.GoldInk, checkedTrackColor = Brand.Gold, uncheckedTrackColor = Brand.Raised, uncheckedThumbColor = Brand.Muted, uncheckedBorderColor = Brand.Line),
        )
    }
}

@Composable
private fun PluginRow(p: Core.PluginInfo, onToggle: (Boolean) -> Unit) {    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!p.enabled) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).border(1.dp, Brand.LineGold), contentAlignment = Alignment.Center) {
            Icon(pluginIcon(p.id), null, Modifier.size(20.dp), tint = if (p.enabled) Brand.Gold else Brand.Muted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.name, color = Brand.Paper, fontWeight = FontWeight.Bold)
            Text(p.description, color = Brand.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = p.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Brand.GoldInk, checkedTrackColor = Brand.Gold, uncheckedTrackColor = Brand.Raised, uncheckedThumbColor = Brand.Muted, uncheckedBorderColor = Brand.Line),
        )
    }
}

/** Artists/channels and words that never show in search, radio or the feed (same as the website's). */
@androidx.compose.runtime.Composable
private fun HiddenCard(vm: AppViewModel) {
    var channels by androidx.compose.runtime.saveable.rememberSaveable(vm.settings.hiddenChannels) { androidx.compose.runtime.mutableStateOf(vm.settings.hiddenChannels.joinToString(", ")) }
    var words by androidx.compose.runtime.saveable.rememberSaveable(vm.settings.hiddenWords) { androidx.compose.runtime.mutableStateOf(vm.settings.hiddenWords.joinToString(", ")) }
    val colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Never show these in search, radio or your feed. Separate with commas. Tip: an artist page has a Hide button.", color = Brand.Muted, fontSize = 13.sp)
        androidx.compose.material3.OutlinedTextField(channels, { channels = it }, Modifier.fillMaxWidth(), label = { Text("Artists or channels") }, placeholder = { Text("@handle or name", color = Brand.Muted) }, colors = colors)
        androidx.compose.material3.OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), label = { Text("Words") }, placeholder = { Text("sped up, nightcore", color = Brand.Muted) }, colors = colors)
        val changed = channels != vm.settings.hiddenChannels.joinToString(", ") || words != vm.settings.hiddenWords.joinToString(", ")
        BrandButton("Save", { vm.setHidden(channels, words) }, primary = true, enabled = changed)
    }
}
