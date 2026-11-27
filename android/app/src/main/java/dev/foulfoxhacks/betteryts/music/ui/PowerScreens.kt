package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Insights
import dev.foulfoxhacks.betteryts.music.core.SmartPlaylist
import dev.foulfoxhacks.betteryts.music.core.SmartRule
import dev.foulfoxhacks.betteryts.music.core.Station
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.ButtonTextStyle

// Screens and cards for the library power tools: smart playlists, stations, pins, insights,
// recent searches, the backup preview and diagnostics.

// ---------------------------------------------------------------------------------------------
// Smart playlists
// ---------------------------------------------------------------------------------------------

/** Up to four covers in a 2×2 grid (one cover fills the tile). */
@Composable
fun Collage(art: List<String>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brand.Raised)) {
        when {
            art.isEmpty() -> Icon(AppIcons.Smart, null, Modifier.align(Alignment.Center).size(36.dp), tint = Brand.Gold)
            art.size < 4 -> RemoteImage(art.first(), Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize()) {
                listOf(art.take(2), art.drop(2).take(2)).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) { row.forEach { RemoteImage(it, Modifier.weight(1f).fillMaxHeight()) } }
                }
            }
        }
    }
}

@Composable
private fun SmartCard(sp: SmartPlaylist, pinned: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(144.dp).clickable(onClick = onClick)) {
        Box {
            Collage(sp.art, Modifier.size(144.dp))
            if (pinned) Icon(AppIcons.Pin, "Pinned", Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Brand.Gold)
        }
        Text(sp.name, Modifier.padding(top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${sp.count} songs · ${if (sp.builtIn) "Smart" else "Yours"}", color = Brand.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** Library shelf: the smart playlists that have songs, and a button to make your own. */
fun LazyListScope.smartShelf(vm: AppViewModel) {
    item(key = "smart-shelf") {
        LaunchedEffect(vm.library) { vm.refreshSmart() }
        var editing by remember { mutableStateOf(false) }
        SectionHeader("Smart playlists", action = "New") { editing = true }
        if (vm.smartPlaylists.isEmpty()) {
            Text("They fill themselves as you like and play songs: Most played, Never played, Forgotten favourites…", Modifier.padding(horizontal = 16.dp), color = Brand.Muted, fontSize = 13.sp)
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.smartPlaylists, key = { "sp-" + it.id }) { sp -> SmartCard(sp, vm.library.isPinned("smart:" + sp.id)) { vm.openSmart(sp) } }
            }
        }
        if (editing) SmartRuleDialog(vm, onDismiss = { editing = false })
    }
}

@Composable
fun SmartPlaylistScreen(vm: AppViewModel, route: Route.SmartRoute, pad: PaddingValues) {
    val sp = vm.smartPlaylists.firstOrNull { it.id == route.id }
    val state = vm.smartTracks[route.id] ?: Load.Loading
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(route.id, vm.library) { vm.loadSmart(route.id) }
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Collage(sp?.art.orEmpty(), Modifier.size(200.dp))
                    Eyebrow(if (sp?.builtIn == false) "Your smart playlist" else "Smart playlist")
                    DisplayTitle(sp?.name ?: route.name, size = 32)
                    sp?.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Brand.Muted, fontSize = 13.sp) }
                    Text("Updates itself from your library and listening, on this phone.", color = Brand.Muted, fontSize = 12.sp)
                }
            }
            when (state) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { ErrorBox(state.message) { vm.loadSmart(route.id) } }
                is Load.Ready -> {
                    val tracks = state.value
                    item {
                        if (tracks.isNotEmpty()) PlayShuffle(onPlay = { vm.playTracks(tracks, 0) }, onShuffle = { vm.playTracks(tracks, 0, shuffle = true) })
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { BrandButton("Save as playlist", { vm.newPlaylistWith = tracks }, icon = AppIcons.PlaylistAdd, enabled = tracks.isNotEmpty()) }
                            item { BrandButton(if (vm.library.isPinned("smart:" + route.id)) "Unpin" else "Pin", { vm.togglePin("smart:" + route.id, sp?.name ?: route.name) }, icon = AppIcons.Pin) }
                            if (sp?.builtIn == false) item { BrandButton("Delete", { deleting = true }, icon = AppIcons.Delete) }
                        }
                        if (tracks.isEmpty()) EmptyNote("Nothing here yet", "No songs match right now. Like and play more and it fills in.")
                    }
                    itemsIndexed(tracks, key = { i, t -> "sm-$i-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(tracks, i) }, leading = "${i + 1}") }
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Brand.Surface,
            title = { Text("Delete smart playlist?", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
            text = { Text("Only the rule is deleted; your songs stay where they are.", color = Brand.Paper) },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteSmart(route.id) }) { Text("Delete", style = ButtonTextStyle, color = Brand.Ember) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
        )
    }
}

private data class Choice<T>(val label: String, val value: T)

@Composable
private fun <T> ChoiceRow(title: String, choices: List<Choice<T>>, selected: T, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Brand.Muted, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(choices) { c -> Chip(c.label, c.value == selected) { onPick(c.value) } }
        }
    }
}

/** Build your own smart playlist: pick a few rules, see how many songs match, then save. */
@Composable
fun SmartRuleDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var rule by remember { mutableStateOf(SmartRule()) }
    var artist by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Triple<Int, List<Track>, String>?>(null) }
    val effective = rule.copy(artist = artist.trim().ifBlank { null })
    LaunchedEffect(effective) {
        kotlinx.coroutines.delay(200)
        preview = try {
            vm.previewSmart(effective)
        } catch (_: dev.foulfoxhacks.betteryts.music.core.CoreException) {
            null
        }
    }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand.Gold, unfocusedBorderColor = Brand.Line, cursorColor = Brand.Gold, focusedTextColor = Brand.Paper, unfocusedTextColor = Brand.Paper)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.Raised,
        title = { Text("New smart playlist", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            LazyColumn(Modifier.height(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Name", color = Brand.Muted) }, colors = colors) }
                item {
                    ChoiceRow("From", listOf(Choice("Everything", "all"), Choice("Liked", "favorites"), Choice("Playlists", "playlists"), Choice("History", "history")), rule.source) { rule = rule.copy(source = it) }
                }
                item {
                    val length = when {
                        rule.maxSeconds == 180 -> "short"
                        rule.minSeconds == 180 && rule.maxSeconds == 480 -> "mid"
                        rule.minSeconds == 1200 -> "long"
                        else -> "any"
                    }
                    ChoiceRow("Length", listOf(Choice("Any", "any"), Choice("Under 3 min", "short"), Choice("3–8 min", "mid"), Choice("20 min+", "long")), length) {
                        rule = when (it) {
                            "short" -> rule.copy(minSeconds = null, maxSeconds = 180)
                            "mid" -> rule.copy(minSeconds = 180, maxSeconds = 480)
                            "long" -> rule.copy(minSeconds = 1200, maxSeconds = null)
                            else -> rule.copy(minSeconds = null, maxSeconds = null)
                        }
                    }
                }
                item {
                    val plays = when {
                        rule.maxPlays == 0 -> "never"
                        rule.minPlays == 3 -> "3"
                        rule.minPlays == 1 -> "1"
                        else -> "any"
                    }
                    ChoiceRow("Plays", listOf(Choice("Any", "any"), Choice("Never played", "never"), Choice("Played", "1"), Choice("3+ times", "3")), plays) {
                        rule = when (it) {
                            "never" -> rule.copy(minPlays = null, maxPlays = 0)
                            "1" -> rule.copy(minPlays = 1, maxPlays = null)
                            "3" -> rule.copy(minPlays = 3, maxPlays = null)
                            else -> rule.copy(minPlays = null, maxPlays = null)
                        }
                    }
                }
                item {
                    val recency = when {
                        rule.playedWithinDays == 30 -> "recent"
                        rule.notPlayedForDays == 60 -> "forgotten"
                        else -> "any"
                    }
                    ChoiceRow("When", listOf(Choice("Any time", "any"), Choice("Played this month", "recent"), Choice("Not in 60 days", "forgotten")), recency) {
                        rule = when (it) {
                            "recent" -> rule.copy(playedWithinDays = 30, notPlayedForDays = null)
                            "forgotten" -> rule.copy(playedWithinDays = null, notPlayedForDays = 60)
                            else -> rule.copy(playedWithinDays = null, notPlayedForDays = null)
                        }
                    }
                }
                item {
                    ChoiceRow("Order", listOf(Choice("Library order", "natural"), Choice("Most played", "mostPlayed"), Choice("Recently played", "recentlyPlayed"), Choice("A–Z", "title"), Choice("Artist", "artist"), Choice("Shortest", "shortest")), rule.sort) { rule = rule.copy(sort = it) }
                }
                item { OutlinedTextField(artist, { artist = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Artist contains (optional)", color = Brand.Muted) }, colors = colors) }
                item { PickRow("Leave out live streams", rule.excludeLive) { rule = rule.copy(excludeLive = it) } }
                item {
                    val p = preview
                    Column(Modifier.fillMaxWidth().card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (p == null) "Counting…" else "${p.first} song${if (p.first == 1) "" else "s"} match", color = Brand.Paper, fontWeight = FontWeight.Bold)
                        p?.third?.let { Text(it, color = Brand.Muted, fontSize = 12.sp) }
                        p?.second?.take(5)?.forEach { t -> Text("${t.title} · ${t.artist}", color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); vm.saveSmart(name.ifBlank { preview?.third ?: "Smart playlist" }, effective) }, enabled = (preview?.first ?: 0) > 0) {
                Text("Save", style = ButtonTextStyle, color = if ((preview?.first ?: 0) > 0) Brand.Gold else Brand.Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
    )
}

// ---------------------------------------------------------------------------------------------
// Stations and pins
// ---------------------------------------------------------------------------------------------

@Composable
private fun StationCard(st: Station, pinned: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(144.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(144.dp).clip(RoundedCornerShape(if (st.kind == "artist") 72.dp else 14.dp)).background(Brand.Raised)) {
            if (st.art != null) RemoteImage(st.art, Modifier.fillMaxSize())
            Icon(AppIcons.Radio, null, Modifier.align(Alignment.BottomStart).padding(8.dp).size(22.dp), tint = Brand.Gold)
            if (pinned) Icon(AppIcons.Pin, "Pinned", Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Brand.Gold)
        }
        Text(st.label, Modifier.padding(top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(if (st.saved) "Saved" else "Recent", st.mix.takeIf { it != "balanced" }?.replaceFirstChar { it.uppercase() }).joinToString(" · "), color = Brand.Muted, fontSize = 12.sp)
    }
}

/** Saved stations first, then recent ones; tap to play, long menu to pin or remove. */
fun LazyListScope.stationsShelf(vm: AppViewModel) {
    val all = (vm.stations.saved + vm.stations.recent.filter { r -> vm.stations.saved.none { it.id == r.id } }).take(16)
    if (all.isEmpty() || !vm.pluginOn("radio")) return
    item(key = "stations-shelf") {
        SectionHeader("Your stations")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(all, key = { "st-" + it.id }) { st ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    StationCard(st, vm.library.isPinned("station:" + st.id)) { vm.resumeStation(st) }
                    IconButton(onClick = { menu = true }, Modifier.align(Alignment.TopStart)) { Icon(AppIcons.More, "Options for ${st.label}", tint = Brand.Paper) }
                    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Brand.Raised) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Play", color = Brand.Paper) }, onClick = { menu = false; vm.resumeStation(st) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(if (vm.library.isPinned("station:" + st.id)) "Unpin" else "Pin", color = Brand.Paper) }, onClick = { menu = false; vm.togglePin("station:" + st.id, st.label) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Remove", color = Brand.Ember) }, onClick = { menu = false; vm.deleteStation(st.id) })
                    }
                }
            }
        }
    }
}

private class Pin(val key: String, val title: String, val subtitle: String, val art: String?, val open: () -> Unit)

/** Pinned playlists, artists, stations and smart playlists, in the order they were pinned. */
fun LazyListScope.pinnedShelf(vm: AppViewModel) {
    val lib = vm.library
    val pins = lib.pinned.mapNotNull { key ->
        val (kind, id) = key.substringBefore(':') to key.substringAfter(':')
        when (kind) {
            "playlist" -> lib.playlist(id)?.let { p -> Pin(key, p.name, "${p.tracks.size} songs", p.tracks.firstOrNull()?.art) { vm.openUserPlaylist(p.id) } }
            "artist" -> lib.followed.firstOrNull { it.id == id }?.let { a -> Pin(key, a.name, "Artist", a.thumbnail) { vm.openArtist(a.id, a.name) } }
            "station" -> (vm.stations.saved + vm.stations.recent).firstOrNull { it.id == id }?.let { st -> Pin(key, st.label, "Station", st.art) { vm.resumeStation(st) } }
            "smart" -> vm.smartPlaylists.firstOrNull { it.id == id }?.let { sp -> Pin(key, sp.name, "${sp.count} songs", sp.art.firstOrNull()) { vm.openSmart(sp) } }
            else -> null
        }
    }
    if (pins.isEmpty()) return
    item(key = "pinned-shelf") {
        SectionHeader("Pinned")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pins, key = { "pin-" + it.key }) { p ->
                Column(Modifier.width(112.dp).clickable(onClick = p.open)) {
                    Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Raised)) {
                        if (p.art != null) RemoteImage(p.art, Modifier.fillMaxSize())
                        Icon(AppIcons.Pin, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp), tint = Brand.Gold)
                    }
                    Text(p.title, Modifier.padding(top = 6.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.subtitle, color = Brand.Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Insights
// ---------------------------------------------------------------------------------------------

/** Library › Insights: local listening stats, worked out on this phone and never uploaded. */
fun LazyListScope.insightsView(vm: AppViewModel) {
    item(key = "insights") {
        LaunchedEffect(Unit) { vm.loadInsights() }
        when (val s = vm.insights) {
            null, Load.Loading -> Loading()
            is Load.Failed -> ErrorBox(s.message) { vm.loadInsights() }
            is Load.Ready -> InsightsBody(vm, s.value)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.card().padding(12.dp)) {
        Text(value, color = Brand.Gold, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1)
        Text(label, color = Brand.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun InsightsBody(vm: AppViewModel, i: Insights) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Worked out on this phone from what you play. Never uploaded, and not part of backups.", color = Brand.Muted, fontSize = 12.sp)
        if (i.totalPlays == 0L) {
            EmptyNote("No stats yet", "Play some songs and your listening shows up here: top artists, minutes, streaks and when you listen.")
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${i.minutes}", "minutes", Modifier.weight(1f))
            Stat("${i.totalPlays}", "plays", Modifier.weight(1f))
            Stat("${i.distinctArtists}", "artists", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${i.currentStreak} day${if (i.currentStreak == 1) "" else "s"}", "current streak", Modifier.weight(1f))
            Stat("${i.longestStreak} day${if (i.longestStreak == 1) "" else "s"}", "longest streak", Modifier.weight(1f))
        }
        i.listener?.let { Text(it + (i.peakHour?.let { h -> " · most plays around ${"%02d".format(h)}:00" } ?: ""), color = Brand.Paper, fontWeight = FontWeight.Bold) }
        // Plays by hour of the day.
        val max = (i.hours.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            i.hours.forEach { n ->
                Box(Modifier.weight(1f).fillMaxHeight(fraction = (0.04f + 0.96f * n / max).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(3.dp)).background(if (n == max) Brand.Gold else Brand.Gold.copy(alpha = 0.35f)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "23").forEach { Text(it, color = Brand.Muted, fontSize = 10.sp) }
        }
        if (i.topArtists.isNotEmpty()) {
            Text("Top artists", color = Brand.Paper, fontWeight = FontWeight.Bold)
            i.topArtists.forEachIndexed { n, (name, plays) -> Text("${n + 1}. $name · $plays plays", color = Brand.Paper, fontSize = 14.sp) }
        }
    }
    if (i.topTracks.isNotEmpty()) {
        Text("Top songs", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Brand.Paper, fontWeight = FontWeight.Bold)
        val tracks = i.topTracks.map { it.track }
        i.topTracks.forEachIndexed { n, t -> TrackRow(vm, t.track, onClick = { vm.playTracks(tracks, n) }, leading = "${t.plays}×") }
    }
    if (i.totalPlays > 0L) {
        BrandButton("Clear listening stats", { confirmClear = true }, Modifier.padding(16.dp), icon = AppIcons.Delete)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Brand.Surface,
            title = { Text("Clear listening stats?", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
            text = { Text("Play counts, minutes and streaks are deleted. Your history, likes and playlists stay.", color = Brand.Paper) },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearListeningStats() }) { Text("Clear", style = ButtonTextStyle, color = Brand.Ember) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Recent searches
// ---------------------------------------------------------------------------------------------

@Composable
fun RecentSearches(vm: AppViewModel, pad: PaddingValues, onPick: (String) -> Unit) {
    val recent = vm.library.searches
    if (recent.isEmpty()) {
        EmptyNote("Search without the ads", "Find songs, artists and playlists. Results come from YouTube through BetterYTS, with ads and filler stripped out.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
        item { SectionHeader("Recent searches", action = "Clear all") { vm.clearSearches() } }
        items(recent, key = { "rs-$it" }) { q ->
            Row(Modifier.fillMaxWidth().clickable { onPick(q) }.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.History, null, tint = Brand.Muted)
                Spacer(Modifier.width(16.dp))
                Text(q, Modifier.weight(1f), color = Brand.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { vm.removeSearch(q) }) { Icon(AppIcons.Close, "Remove “$q” from history", tint = Brand.Muted) }
            }
        }
        item { Text("Kept on this phone only.", Modifier.padding(16.dp), color = Brand.Muted, fontSize = 12.sp) }
    }
}

// ---------------------------------------------------------------------------------------------
// Backup preview
// ---------------------------------------------------------------------------------------------

@Composable
fun BackupPreviewDialog(vm: AppViewModel, p: AppViewModel.PendingBackup) {
    val pv = p.preview
    val s = pv.summary
    fun n(k: String) = s.optInt(k)
    AlertDialog(
        onDismissRequest = { vm.cancelBackup() },
        containerColor = Brand.Raised,
        title = { Text("Import this backup?", style = MaterialTheme.typography.titleLarge, color = Brand.Paper) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(
                        if (pv.madeBy == "app") "From BetterYTS Music" else "From the BetterYTS website",
                        pv.appVersion?.let { "v$it" },
                        pv.exportedAt?.take(10),
                    ).joinToString(" · "),
                    color = Brand.Muted,
                    fontSize = 12.sp,
                )
                Text("The file has ${pv.favorites} liked songs, ${pv.playlists} playlists (${pv.tracksInPlaylists} songs), ${pv.artists} artists and ${pv.history} history entries.", color = Brand.Paper, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Add to my library", !p.replace) { vm.setBackupReplace(false) }
                    Chip("Replace it", p.replace) { vm.setBackupReplace(true) }
                }
                if (p.replace) {
                    Text("Replace deletes your liked songs, playlists, followed artists and history on this phone first, then imports the file. This can't be undone; export a backup first if unsure.", color = Brand.Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.fillMaxWidth().card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("What will change", color = Brand.Paper, fontWeight = FontWeight.Bold)
                    listOf(
                        "liked songs added" to n("favoritesAdded"),
                        "playlists added" to n("playlistsAdded"),
                        "playlists updated" to n("playlistsUpdated"),
                        "artists followed" to n("artistsAdded"),
                        "history entries" to n("historyAdded"),
                        "plugins installed" to n("pluginsInstalled"),
                        "smart playlists" to n("smartAdded"),
                    ).filter { it.second > 0 }.ifEmpty { listOf("nothing new to add" to 0) }.forEach { (label, count) ->
                        Text(if (count > 0) "$count $label" else label, color = Brand.Paper, fontSize = 13.sp)
                    }
                    if (n("duplicates") > 0) Text("${n("duplicates")} already here (skipped)", color = Brand.Muted, fontSize = 12.sp)
                    if (n("invalid") > 0) Text("${n("invalid")} invalid entries left out", color = Brand.Muted, fontSize = 12.sp)
                    if (n("playlistsSkipped") > 0) Text("${n("playlistsSkipped")} playlists don't fit (100-playlist limit)", color = Brand.Ember, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmBackup() }) { Text(if (p.replace) "Replace" else "Import", style = ButtonTextStyle, color = if (p.replace) Brand.Ember else Brand.Gold) } },
        dismissButton = { TextButton(onClick = { vm.cancelBackup() }) { Text("Cancel", style = ButtonTextStyle, color = Brand.Muted) } },
    )
}

// ---------------------------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------------------------

@Composable
fun DiagnosticsScreen(vm: AppViewModel, pad: PaddingValues) {
    val context = LocalContext.current
    val p = vm.playerForDiagnostics
    val n = vm.networkDiagnostics
    Column(Modifier.fillMaxSize()) {
        PageTopBar(vm, pad.calculateTopPadding())
        LazyColumn(contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Settings")
                    DisplayTitle("Diagnostics", size = 40)
                    Text("What the player and the network did recently, to explain buffering or failures. The copied report leaves out song IDs, searches, tokens and room codes.", color = Brand.Muted, fontSize = 13.sp)
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandButton("Copy report", { copyText(context, "BetterYTS diagnostics", vm.diagnosticsReport()); vm.message = "Report copied" }, primary = true, icon = AppIcons.Copy)
                    BrandButton("Refresh", { vm.loadDiagnostics() }, icon = AppIcons.Refresh)
                    BrandButton("Clear", { vm.clearDiagnostics() })
                }
            }
            if (p != null) {
                val m = p.machine
                item {
                    SectionHeader("Player")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("State: ${p.status}", color = Brand.Paper, fontWeight = FontWeight.Bold)
                        Text("Now: ${p.currentId ?: "nothing loaded"}", color = Brand.Muted, fontSize = 13.sp)
                        Text("Time to ready: ${m.lastTimeToReadyMs?.let { "$it ms" } ?: "n/a"} · rebuffers ${m.rebufferCount} (${m.rebufferMs} ms)", color = Brand.Muted, fontSize = 13.sp)
                        Text("Last error: ${m.lastError ?: "none"}", color = Brand.Muted, fontSize = 13.sp)
                        if (vm.room != null) Text("Room drift: ${"%.2f".format(vm.roomDrift)} s", color = Brand.Muted, fontSize = 13.sp)
                    }
                }
                item { SectionHeader("Recent player changes") }
                val now = android.os.SystemClock.elapsedRealtime()
                items(m.transitions.take(30)) { t ->
                    Text(
                        "-${(now - t.at) / 1000}s  ${t.from} → ${t.to}  ${t.reason}${if (t.expected) "" else "  [unexpected]"}",
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        color = if (t.expected) Brand.Paper else Brand.Ember,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            item { SectionHeader("Network") }
            if (n == null) item { Loading() } else {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${n.requests} requests · ${n.failures} failed · ${n.cacheHits} from cache · ${n.retries} retries", color = Brand.Paper, fontWeight = FontWeight.Bold)
                        Text("Median ${n.medianMs} ms · slowest ${n.maxMs} ms", color = Brand.Muted, fontSize = 13.sp)
                        n.lastError?.let { Text("Last error: $it", color = Brand.Ember, fontSize = 13.sp) }
                    }
                }
                items(n.recent.take(40)) { r ->
                    Text(
                        "${r.method} ${r.route}  ${if (r.cached) "cache" else "${r.status} · ${r.ms} ms"}${if (r.retries > 0) " · ${r.retries} retries" else ""}${r.error?.let { " · $it" } ?: ""}",
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        color = if (r.error != null) Brand.Ember else Brand.Paper,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
