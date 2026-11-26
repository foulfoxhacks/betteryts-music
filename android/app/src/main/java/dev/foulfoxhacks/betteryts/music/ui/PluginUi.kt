package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Core
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun BrandSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) = Switch(
    checked = checked,
    onCheckedChange = onToggle,
    colors = SwitchDefaults.colors(checkedThumbColor = Brand.GoldInk, checkedTrackColor = Brand.Gold, uncheckedTrackColor = Brand.Raised, uncheckedThumbColor = Brand.Muted, uncheckedBorderColor = Brand.Line),
)

/** A category's whole-group switch. Off stops every plugin in it; each keeps its own switch. */
@Composable
fun CategoryRow(c: Core.Category, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!c.enabled) }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, color = if (c.enabled) Brand.Paper else Brand.Muted)
            Text(c.description, color = Brand.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        BrandSwitch(c.enabled, onToggle)
    }
}

/** An installed community plugin: its switch, what it may do, and Remove. */
@Composable
fun CommunityRow(p: Core.PluginInfo, permissionText: Map<String, String>, error: String?, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!p.enabled) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Brand.Paper, fontWeight = FontWeight.Bold)
            if (p.description.isNotBlank()) Text(p.description, color = Brand.Muted, fontSize = 12.sp)
            val by = listOfNotNull("Community", p.author.takeIf { it.isNotBlank() }?.let { "by $it" }, p.version.takeIf { it.isNotBlank() }?.let { "v$it" }).joinToString(" · ")
            Text("$by · can: " + p.permissions.joinToString("; ") { permissionText[it] ?: it }, color = Brand.Tan, fontSize = 11.sp)
            error?.let { Text(it, color = Brand.Ember, fontSize = 11.sp) }
            TextButton(onClick = onRemove) { Text("Remove", color = Brand.Gold, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(4.dp))
        BrandSwitch(p.enabled, onToggle)
    }
}

/** Paste a manifest, see what it asks for, then install. The core checks it again either way. */
@Composable
fun CommunityInstallCard(vm: AppViewModel) {
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).card().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow("Add a community plugin")
        Text(
            "Paste a plugin manifest. Community plugins can only join Player and Look. Their code runs in a sandbox with no network and no access to the app: it only gets what its permissions allow.",
            color = Brand.Paper,
            fontSize = 13.sp,
        )
        OutlinedTextField(
            text,
            { text = it; parseError = null },
            Modifier.fillMaxWidth(),
            placeholder = { Text("{ \"id\": \"song-clock\", \"category\": \"Player\", … }", color = Brand.Muted, fontSize = 12.sp) },
            minLines = 3,
            maxLines = 8,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Brand.Paper),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton("Install", {
                val m = runCatching { JSONObject(text.trim()) }.getOrNull()
                if (m == null) parseError = "That isn't a plugin manifest (expected JSON)" else confirm = m
            }, primary = true, enabled = text.isNotBlank())
            BrandButton("Paste", {
                scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { text = it.toString().trim() } }
            })
        }
        (parseError ?: vm.communityError)?.let { Text(it, color = Brand.Ember, fontSize = 13.sp) }
    }

    confirm?.let { m ->
        val perms = m.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Install “${m.optString("name", "plugin").take(40)}”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Category: ${m.optString("category").take(20)}")
                    Text("It asks to:")
                    perms.forEach { Text("• " + (vm.settings.permissionText[it] ?: it)) }
                    Text("It can't use the network or see anything else in the app.", color = Brand.Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.installPlugin(text) { text = "" }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Text panels from Player community plugins, shown under the player tabs. Plain text only. */
@Composable
fun PluginPanels(vm: AppViewModel) {
    val panels = vm.pluginPanels
    if (panels.isEmpty()) return
    val names = vm.settings.plugins.associate { it.id to it.name }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        panels.forEach { (id, text) ->
            if (text.isBlank() || !vm.pluginOn(id)) return@forEach
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Raised).padding(12.dp)) {
                Text("${names[id] ?: id} · community plugin", color = Brand.Tan, fontSize = 11.sp)
                Text(text, color = Brand.Paper, fontSize = 14.sp)
            }
        }
    }
}
