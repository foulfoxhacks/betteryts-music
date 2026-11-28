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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.ALL_TABS
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle
import dev.foulfoxhacks.betteryts.music.ui.theme.CustomTheme
import dev.foulfoxhacks.betteryts.music.ui.theme.HOME_SECTIONS
import dev.foulfoxhacks.betteryts.music.ui.theme.MENU_ITEMS
import dev.foulfoxhacks.betteryts.music.ui.theme.Palette
import dev.foulfoxhacks.betteryts.music.ui.theme.Palettes
import dev.foulfoxhacks.betteryts.music.ui.theme.THEME_TOKENS
import dev.foulfoxhacks.betteryts.music.ui.theme.contrastProblem

/** Settings › Customize: style, text size, density, layout, Home sections, tabs, song menu, theme editor. */
@Composable
fun CustomizeScreen(vm: AppViewModel, pad: PaddingValues) {
    val look = Brand.look
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Eyebrow("Settings")
                    DisplayTitle("Customize", size = 40)
                    Text("Changes apply right away and stay on this device.", color = Brand.Muted, fontSize = 13.sp)
                }
            }

            item { SectionHeader("Style") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("flat" to "Flat", "ambient" to "Ambient", "glass" to "Glass").forEach { (id, label) ->
                            Chip(label, look.style == id) { vm.updateLook { it.copy(style = id) } }
                        }
                    }
                    Text(
                        when (look.style) {
                            "ambient" -> "A soft wash of the accent behind every screen. With Album colours on, it follows the song."
                            "glass" -> "Ambient, plus see-through cards and bars."
                            else -> "Solid surfaces, nothing behind them."
                        },
                        color = Brand.Muted, fontSize = 13.sp,
                    )
                }
            }

            item { SectionHeader("Theme", action = "Edit") { editing = true } }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val custom = look.custom
                    Text(if (look.useCustom && custom != null) "Using your theme “${custom.name}”" else "Using a built-in theme (Settings › Theme)", color = Brand.Paper)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrandButton("Theme editor", { editing = true }, primary = true, icon = AppIcons.Edit)
                        if (custom != null) BrandButton(if (look.useCustom) "Use built-in" else "Use mine", { vm.updateLook { it.copy(useCustom = !it.useCustom) } })
                    }
                }
            }

            item { SectionHeader("Text and spacing") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Text size", Modifier.weight(1f), color = Brand.Paper, fontWeight = FontWeight.Bold)
                        Text("${(look.fontScale * 100).toInt()}%", color = Brand.Gold, fontWeight = FontWeight.Bold)
                    }
                    Slider(look.fontScale, { v -> vm.updateLook { it.copy(fontScale = (v * 20).toInt() / 20f) } }, valueRange = 0.85f..1.3f, steps = 8, colors = SliderDefaults.colors(thumbColor = Brand.Gold, activeTrackColor = Brand.Gold, inactiveTrackColor = Brand.Line))
                }
                SwitchRow("Compact lists", "Smaller rows and covers, more songs on screen.", look.compact) { on -> vm.updateLook { it.copy(compact = on) } }
                SwitchRow("Reduce motion", "No sliding player or splash animation.", look.reducedMotion) { on -> vm.updateLook { it.copy(reducedMotion = on) } }
                SwitchRow("Liked songs as a grid", "Covers three to a row in Library › Songs.", look.libraryGrid) { on -> vm.updateLook { it.copy(libraryGrid = on) } }
                SwitchRow("Wide layout", "On tablets, foldables and in landscape: tabs in a side rail.", look.wideLayout) { on -> vm.updateLook { it.copy(wideLayout = on) } }
            }

            item { SectionHeader("Tabs") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Home is always first. Settings stays in the top bar.", color = Brand.Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ALL_TABS.forEach { tab ->
                            val on = tab in look.tabs
                            Chip(tab, on) {
                                if (tab != "Home") vm.updateLook { l -> l.copy(tabs = if (on) l.tabs - tab else ALL_TABS.filter { it in l.tabs || it == tab }) }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Home") }
            item { Text("Order and show the sections of Home.", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp) }
            val order = (look.homeOrder + HOME_SECTIONS.map { it.first }).distinct().filter { k -> HOME_SECTIONS.any { it.first == k } }
            order.forEachIndexed { i, key ->
                item(key = "home-$key") {
                    val label = HOME_SECTIONS.first { it.first == key }.second
                    val shown = key !in look.homeHidden
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PickRowCompact(label, shown, Modifier.weight(1f)) { on -> vm.updateLook { it.copy(homeHidden = if (on) it.homeHidden - key else it.homeHidden + key) } }
                        IconButton(onClick = { vm.updateLook { it.copy(homeOrder = order.toMutableList().apply { if (i > 0) add(i - 1, removeAt(i)) }) } }, enabled = i > 0) {
                            Icon(AppIcons.Up, "Move $label up", tint = if (i > 0) Brand.Paper else Brand.Line)
                        }
                        IconButton(onClick = { vm.updateLook { it.copy(homeOrder = order.toMutableList().apply { if (i < lastIndex) add(i + 1, removeAt(i)) }) } }, enabled = i < order.lastIndex) {
                            Icon(AppIcons.Collapse, "Move $label down", tint = if (i < order.lastIndex) Brand.Paper else Brand.Line)
                        }
                    }
                }
            }

            item { SectionHeader("Song menu") }
            item { Text("Hide what you never use from ⋮ on songs.", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp) }
            items(MENU_ITEMS, key = { "menu-" + it.first }) { (key, label) ->
                PickRowCompact(label, key !in look.menuHidden, Modifier.padding(horizontal = 4.dp)) { on -> vm.updateLook { it.copy(menuHidden = if (on) it.menuHidden - key else it.menuHidden + key) } }
            }
            item {
                BrandButton("Reset all customizations", { vm.updateLook { dev.foulfoxhacks.betteryts.music.ui.theme.Look(custom = it.custom) } }, Modifier.padding(16.dp), icon = AppIcons.Refresh)
            }
        }
    }
    if (editing) ThemeEditor(vm) { editing = false }
}

@Composable
private fun PickRowCompact(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Box(modifier) { PickRow(label, checked, onChange) }
}

private val SWATCHES = listOf(
    0xFF0B1020, 0xFF000000, 0xFF111A33, 0xFF1E1B2E, 0xFF14161A, 0xFFF6F7FB, 0xFFFFFFFF, 0xFFF2F5FF,
    0xFFB4BDD8, 0xFF4A5372, 0xFFFF6A00, 0xFF0A84FF, 0xFF00A3E0, 0xFF7C3AED, 0xFFFF8DD0, 0xFFFFC857,
    0xFF22C55E, 0xFFEF4444, 0xFFDB2777, 0xFF14B8A6,
)

/** Edit every colour token with swatches or hex, preview live, check contrast, export/import. */
@Composable
private fun ThemeEditor(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val start = Brand.look.custom ?: CustomTheme("My theme", vm.settings.theme, emptyMap())
    var name by remember { mutableStateOf(start.name) }
    var base by remember { mutableStateOf(start.base) }
    var colors by remember { mutableStateOf(start.colors) }
    var picking by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val draft = CustomTheme(name.ifBlank { "My theme" }, base, colors)
    val p = draft.palette()
    val problem = contrastProblem(p)
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text("Theme editor", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Preview(p) }
                item { problem?.let { Text(it, color = Brand.Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold) } ?: Text("Readable: contrast checks pass.", color = Brand.Muted, fontSize = 12.sp) }
                item { OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, colors = fieldColors) }
                item {
                    Text("Start from", color = Brand.Muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Palettes.all) { pal -> Chip(pal.id.replaceFirstChar { it.uppercase() }, base == pal.id) { base = pal.id } }
                    }
                }
                items(THEME_TOKENS, key = { "tok-" + it.first }) { (key, label) ->
                    val current = colors[key]?.let { Color(it) } ?: tokenOf(Palettes.byId(base), key)
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { picking = if (picking == key) null else key }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(current).border(1.dp, Brand.Line, CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Text(label, Modifier.weight(1f), color = Brand.Paper)
                            Text(if (colors.containsKey(key)) CustomTheme.hex(colors.getValue(key)) else "theme", color = Brand.Muted, fontSize = 12.sp)
                        }
                        if (picking == key) {
                            var hex by remember(key) { mutableStateOf(colors[key]?.let(CustomTheme::hex) ?: "") }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(SWATCHES) { argb ->
                                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Brand.Line, CircleShape).clickable { colors = colors + (key to argb); hex = CustomTheme.hex(argb) })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(hex, { v ->
                                    hex = v.take(7)
                                    runCatching { CustomTheme.parseHex(hex) }.getOrNull()?.let { colors = colors + (key to it) }
                                }, Modifier.weight(1f), singleLine = true, placeholder = { Text("#FF6A00", color = Brand.Muted) }, colors = fieldColors)
                                TextButton(onClick = { colors = colors - key; hex = "" }) { Text("Theme's", color = Brand.Gold) }
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrandButton("Export", {
                            copyText(context, "BetterYTS theme", draft.toJson().toString(2))
                            vm.message = "Theme copied. Paste it anywhere to share."
                        }, icon = AppIcons.Copy)
                        BrandButton("Import", { importing = true })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (vm.saveCustomTheme(draft)) onDismiss() }, enabled = problem == null) {
                Text("Apply", style = ButtonTextStyle, color = if (problem == null) Brand.Gold else Brand.Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
    )
    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            containerColor = Brand.Raised,
            title = { Text("Import a theme", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
            text = { OutlinedTextField(text, { text = it.take(4000) }, Modifier.fillMaxWidth(), minLines = 4, placeholder = { Text("Paste a BetterYTS theme", color = Brand.Muted) }, colors = fieldColors) },
            confirmButton = { TextButton(onClick = { importing = false; vm.importTheme(text); onDismiss() }) { Text("Import", style = ButtonTextStyle, color = Brand.Gold) } },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
        )
    }
}

private fun tokenOf(p: Palette, key: String): Color = when (key) {
    "ink" -> p.ink
    "surface" -> p.surface
    "raised" -> p.raised
    "paper" -> p.paper
    "muted" -> p.muted
    "accent" -> p.accent
    "eyebrow" -> p.eyebrow
    "hot" -> p.hot
    "violet" -> p.violet
    else -> p.love
}

/** A miniature screen drawn with the draft palette. */
@Composable
private fun Preview(p: Palette) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NOW PLAYING", color = p.eyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(10.dp)) {
            Text("Midnight City", color = p.paper, fontWeight = FontWeight.Bold)
            Text("M83 · 4:03", color = p.muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).background(p.accent).padding(horizontal = 14.dp, vertical = 6.dp)) { Text("Play", color = p.onAccent, fontWeight = FontWeight.Bold) }
            Text("♥", color = p.love, fontSize = 18.sp)
            Text("Shuffle", color = p.accent)
        }
    }
}
