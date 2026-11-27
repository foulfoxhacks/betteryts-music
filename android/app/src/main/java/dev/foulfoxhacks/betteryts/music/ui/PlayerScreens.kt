package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Repeat
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle
import kotlinx.coroutines.launch

fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

/** 0.75×, 1× — a compact label for the speed button and its menu. */
private fun formatSpeed(rate: Float): String =
    if (rate % 1f == 0f) "${rate.toInt()}×" else "${rate}×"

fun copyText(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Bar above the bottom navigation. The video sits in the first [videoWidth] (drawn by the App's
 * video layer). Swipe left/right to skip, tap to open the full player.
 */
@Composable
fun MiniPlayer(vm: AppViewModel, player: PlayerController, track: Track, height: Dp, videoWidth: Dp) {
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Surface)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            drag < -120f -> vm.next()
                            drag > 120f -> vm.previous()
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, amount -> drag += amount }
            },
    ) {
        val progress = if (player.duration > 0) (player.position / player.duration).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brand.Line)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Brand.Gold))
        }
        Row(Modifier.fillMaxWidth().height(height - 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(videoWidth))
            Column(Modifier.weight(1f).fillMaxHeight().clickable { vm.playerExpanded = true }.padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                Text(track.title, color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = vm::togglePlay) {
                Icon(if (player.playing) AppIcons.Pause else AppIcons.Play, if (player.playing) "Pause" else "Play", tint = Brand.Paper)
            }
            IconButton(onClick = { vm.next() }, enabled = vm.canSkip) {
                Icon(AppIcons.Next, "Next", tint = if (vm.canSkip) Brand.Paper else Brand.Muted.copy(alpha = 0.4f))
            }
        }
    }
}

private enum class PlayerTab(val label: String) { UpNext("Up next"), Lyrics("Lyrics"), Related("Related") }

/** Full-screen player. The video occupies [videoHeight] under the top bar (drawn by the App's video layer). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlaying(vm: AppViewModel, player: PlayerController, top: Dp, bottom: Dp, videoHeight: Dp) {
    val q = vm.queue
    val track = vm.nowPlaying ?: return
    var tab by rememberSaveable { mutableStateOf(PlayerTab.UpNext) }
    val tabs = PlayerTab.entries.filter { it != PlayerTab.Lyrics || vm.pluginOn("lyrics") }
    if (tab !in tabs) tab = PlayerTab.UpNext
    val list = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Brand.Ink)) {
        // Blurred artwork + the theme's glow behind everything.
        RemoteImage(track.largeArt, Modifier.fillMaxWidth().height(420.dp).blur(48.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Brand.Ink.copy(alpha = 0.55f), Brand.Ink.copy(alpha = 0.92f), Brand.Ink), endY = 1400f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Brand.Glow, Color.Transparent), radius = 900f)))

        Column(Modifier.fillMaxSize().padding(top = top)) {
            PlayerTopBar(vm, track)
            Spacer(Modifier.height(videoHeight))

            if (tab == PlayerTab.Lyrics) LyricsAutoScroll(vm, player, track, list)
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
                item { TitleRow(vm, track) }
                item { SeekBar(vm, player, track) }
                item { Controls(vm, player) }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEach { t -> Chip(t.label, tab == t) { tab = t } }
                    }
                }
                when (tab) {
                    PlayerTab.UpNext -> if (vm.room != null) roomPanel(vm) else upNext(vm, q)
                    PlayerTab.Lyrics -> lyricsItems(vm, player, track)
                    PlayerTab.Related -> related(vm, track)
                }
                item(key = "plugin-panels") { PluginPanels(vm) }
                item {
                    Text(
                        if (vm.pluginOn("background")) "Background play is on: the music keeps going when you leave the app or turn off the screen."
                        else "Plays through YouTube's official player. Press Home while a song plays and it keeps going in a floating player.",
                        Modifier.fillMaxWidth().padding(16.dp),
                        color = Brand.Muted.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(vm: AppViewModel, track: Track) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.playerExpanded = false }) { Icon(AppIcons.Collapse, "Close player", tint = Brand.Paper) }
        Column(Modifier.weight(1f)) {
            Eyebrow("Now playing")
            val sleep = vm.sleepAt
            val note = when {
                vm.sleepAtEndOfSong -> "Sleep timer: after this song"
                sleep != null -> "Sleep timer: ${((sleep - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min"
                vm.room != null -> "Listening together · ${vm.room?.code}"
                else -> "${vm.queue.position + 1} of ${vm.queue.tracks.size}"
            }
            Text(note, color = Brand.Muted, fontSize = 11.sp)
        }
        if (vm.pluginOn("sleep")) {
            Box {
                IconButton(onClick = { sleepMenu = true }) {
                    Icon(AppIcons.Sleep, "Sleep timer", tint = if (vm.sleepAt != null || vm.sleepAtEndOfSong) Brand.Gold else Brand.Paper)
                }
                DropdownMenu(sleepMenu, onDismissRequest = { sleepMenu = false }, containerColor = Brand.Raised) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                        DropdownMenuItem(text = { Text("$m minutes", color = Brand.Paper) }, onClick = { sleepMenu = false; vm.sleepIn(m) })
                    }
                    DropdownMenuItem(text = { Text("End of this song", color = Brand.Paper) }, onClick = { sleepMenu = false; vm.sleepAfterThisSong() })
                    if (vm.sleepAt != null || vm.sleepAtEndOfSong) {
                        DropdownMenuItem(text = { Text("Turn off sleep timer", color = Brand.Ember) }, onClick = { sleepMenu = false; vm.cancelSleep(); vm.message = "Sleep timer off" })
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { speedMenu = true }) {
                Text(
                    formatSpeed(vm.speed),
                    color = if (vm.speed != 1f) Brand.Gold else Brand.Paper,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                )
            }
            DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }, containerColor = Brand.Raised) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { r ->
                    DropdownMenuItem(
                        text = { Text(if (r == 1f) "1× · Normal" else formatSpeed(r), color = if (vm.speed == r) Brand.Gold else Brand.Paper) },
                        onClick = { speedMenu = false; vm.chooseSpeed(r) },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "More", tint = Brand.Paper) }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Brand.Raised) {
                DropdownMenuItem(text = { Text("Sound, captions, output…", color = Brand.Paper) }, onClick = { menu = false; vm.soundOpen = true })
                DropdownMenuItem(text = { Text("Add to playlist…", color = Brand.Paper) }, onClick = { menu = false; vm.playlistPickerFor = track })
                if (!track.isLocal) DropdownMenuItem(text = { Text("Share", color = Brand.Paper) }, onClick = { menu = false; vm.share(track) })
                if (vm.pluginOn("together") && vm.room == null) {
                    DropdownMenuItem(text = { Text("Start listening together", color = Brand.Paper) }, onClick = { menu = false; vm.startTogether() })
                    DropdownMenuItem(text = { Text("Join a room…", color = Brand.Paper) }, onClick = { menu = false; vm.joinRoomDialog = true })
                }
                if (track.artistId != null) DropdownMenuItem(text = { Text("Go to artist", color = Brand.Paper) }, onClick = { menu = false; vm.openArtist(track.artistId, track.artist) })
                if (vm.pluginOn("vrchat") && !track.isLocal) {
                    DropdownMenuItem(
                        text = { Text("Copy VRChat link", color = Brand.Paper) },
                        leadingIcon = { Icon(AppIcons.Headset, null, tint = Brand.Gold) },
                        onClick = {
                            menu = false
                            copyText(context, "VRChat link", vm.vrchatLink(track))
                            vm.message = "Copied. Paste it into any VRChat video player."
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy queue as ProTV playlist", color = Brand.Paper) },
                        leadingIcon = { Icon(AppIcons.Copy, null, tint = Brand.Gold) },
                        onClick = {
                            menu = false
                            scope.launch {
                                copyText(context, "ProTV playlist", vm.protvQueue())
                                vm.message = "Copied ${vm.queue.tracks.size} songs as a ProTV playlist"
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleRow(vm: AppViewModel, track: Track) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track.title, Modifier.basicMarquee(), color = Brand.Paper, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1)
            Text(
                track.artist,
                Modifier.clickable(enabled = track.artistId != null) { vm.openArtist(track.artistId, track.artist) },
                color = Brand.Tan,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val votes = vm.dislikes
            if (vm.pluginOn("dislikes") && votes != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.ThumbUp, "Likes", Modifier.size(14.dp), tint = Brand.Muted)
                    Text(" ${compact(votes.first)}   ", color = Brand.Muted, fontSize = 12.sp)
                    Icon(AppIcons.ThumbDown, "Dislikes", Modifier.size(14.dp), tint = Brand.Muted)
                    Text(" ${compact(votes.second)}", color = Brand.Muted, fontSize = 12.sp)
                }
            }
        }
        val disliked = vm.library.isDisliked(track.id)
        IconButton(onClick = { vm.toggleDislike(track) }) {
            Icon(AppIcons.ThumbDown, if (disliked) "Remove dislike" else "Dislike", tint = if (disliked) Brand.Gold else Brand.Muted)
        }
        val fav = vm.library.isFavorite(track.id)
        IconButton(onClick = { vm.toggleFavorite(track) }) {
            Icon(if (fav) AppIcons.Favorite else AppIcons.FavoriteBorder, if (fav) "Remove from favorites" else "Add to favorites", tint = if (fav) Brand.Pink else Brand.Paper)
        }
    }
}

private fun LazyListScope.upNext(vm: AppViewModel, q: dev.foulfoxhacks.betteryts.music.core.QueueState) {
    val st = vm.station
    if (st != null) {
        item(key = "station-header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Radio, null, Modifier.size(20.dp), tint = Brand.Gold)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(st.label, color = Brand.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Station · keeps going${if (st.blocked > 0) " · ${st.blocked} blocked" else ""}", color = Brand.Muted, fontSize = 12.sp)
                    }
                    if (!st.saved) BrandButton("Save", { vm.saveStation() }) else Text("Saved", color = Brand.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("familiar" to "Familiar", "balanced" to "Balanced", "discover" to "Discover", "smart" to "Smart").forEach { (mix, label) ->
                        Chip(label, st.mix == mix) { vm.tuneStation(mix) }
                    }
                }
            }
        }
    }
    if (q.tracks.isNotEmpty()) {
        item(key = "queue-actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton("Save as playlist", { vm.saveQueueAsPlaylist() }, icon = AppIcons.PlaylistAdd)
                BrandButton("Clear", { vm.clearQueue() }, icon = AppIcons.Delete)
            }
        }
    }
    itemsIndexed(q.tracks, key = { i, t -> "q-$i-" + t.id }) { i, t ->
        TrackRow(
            vm,
            t,
            onClick = { vm.jumpTo(i) },
            current = i == q.position,
            leading = "${i + 1}",
            extraActions = buildList {
                add("Remove from queue" to { vm.removeFromQueue(i) })
                if (i > 0) add("Move up" to { vm.moveInQueue(i, i - 1) })
                if (i < q.tracks.lastIndex) add("Move down" to { vm.moveInQueue(i, i + 1) })
                if (st != null && i != q.position) add("Not for this station" to { vm.notForStation(t, i) })
                if (st != null) add("More like this" to { vm.moreLikeThis(t) })
            },
        )
    }
}

private fun LazyListScope.related(vm: AppViewModel, track: Track) {
    val id = track.artistId
    if (id == null) {
        item { EmptyNote("Nothing related", "This song isn't linked to an artist page.") }
        return
    }
    item {
        LaunchedEffect(id) { if (vm.artistPages[id] !is Load.Ready) vm.loadArtist(id) }
        Eyebrow("More from ${track.artist}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
    when (val page = vm.artistPages[id]) {
        is Load.Ready -> {
            val more = page.value.tracks.filter { it.id != track.id }.take(20)
            itemsIndexed(more, key = { _, t -> "rel-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(more, i) }) }
        }
        is Load.Failed -> item { ErrorBox(page.message) { vm.loadArtist(id) } }
        else -> item { Loading() }
    }
}

/** Rows above the first lyric line in the player's list: title, seek bar, controls, tabs, header. */
private const val LYRICS_FIRST_ROW = 5

/** Synced lyrics, one row per line: the current line is highlighted; tap a line to jump there. */
private fun LazyListScope.lyricsItems(vm: AppViewModel, player: PlayerController, track: Track) {
    item(key = "lyrics-head") {
        LaunchedEffect(track.id) { vm.loadLyrics(track) }
        Text("LYRICS FROM LRCLIB", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = LabelStyle, fontSize = 10.sp, color = Brand.Muted)
    }
    when (val state = vm.lyrics[track.id]) {
        is Load.Ready -> {
            val l = state.value
            when {
                l == null -> item { EmptyNote("No lyrics found", "LRCLIB doesn't have this song yet.") }
                l.instrumental -> item { EmptyNote("Instrumental", "No lyrics: enjoy the music.") }
                l.synced.isNotEmpty() -> itemsIndexed(l.synced, key = { i, _ -> "lyric-$i" }) { i, line ->
                    val current = l.synced.indexOfLast { it.time <= player.position + 0.3f }
                    val active = i == current
                    Text(
                        line.text.ifBlank { "♪" },
                        Modifier.fillMaxWidth().clickable { vm.seek(line.time) }.padding(horizontal = 16.dp, vertical = 5.dp),
                        color = when {
                            active -> Brand.Gold
                            i < current -> Brand.Muted.copy(alpha = 0.6f)
                            else -> Brand.Paper.copy(alpha = 0.85f)
                        },
                        fontSize = if (active) 22.sp else 19.sp,
                        fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                        lineHeight = 28.sp,
                    )
                }
                l.plain != null -> item { Text(l.plain, Modifier.padding(horizontal = 16.dp), color = Brand.Paper, fontSize = 18.sp, lineHeight = 28.sp) }
                else -> item { EmptyNote("No lyrics found", "LRCLIB doesn't have this song yet.") }
            }
        }
        is Load.Failed -> item { ErrorBox(state.message) }
        else -> item { Loading() }
    }
}

/** Keeps the current lyric line in view, unless the user is scrolling the list themself. */
@Composable
private fun LyricsAutoScroll(vm: AppViewModel, player: PlayerController, track: Track, list: androidx.compose.foundation.lazy.LazyListState) {
    val lines = (vm.lyrics[track.id] as? Load.Ready)?.value?.synced.orEmpty()
    if (lines.isEmpty()) return
    val current = lines.indexOfLast { it.time <= player.position + 0.3f }
    LaunchedEffect(current) {
        if (current >= 0 && !list.isScrollInProgress) list.animateScrollToItem((LYRICS_FIRST_ROW + current - 3).coerceAtLeast(0))
    }
}

/** Listen Together: room card + the room's shared queue. */
private fun LazyListScope.roomPanel(vm: AppViewModel) {
    val r = vm.room ?: return
    item(key = "room-card") {
        val context = LocalContext.current
        val s = vm.roomState
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).card().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Eyebrow("Listen Together")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Brand.Gold).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(r.code, color = Brand.GoldInk, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 3.sp)
                }
                Text(
                    listOf(vm.roomMembers, if (vm.roomIsHost) "you're the host" else null, vm.roomStatus.ifBlank { null }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.padding(start = 10.dp),
                    color = Brand.Muted,
                    fontSize = 12.sp,
                )
            }
            if (s?.locked == true) Text(if (vm.roomIsHost) "Host-only: guests can't change playback." else "The host controls playback.", color = Brand.Tan, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton("Invite", {
                    copyText(context, "Room invite", vm.roomInviteLink(r.code))
                    vm.message = "Invite link copied: it opens on the web or in the app"
                })
                if (vm.pluginOn("vrchat")) BrandButton("VRChat", {
                    copyText(context, "VRChat room link", vm.roomVrchatLink(r.code))
                    vm.message = "Copied. Paste into a VRChat video player to follow the room."
                })
                BrandButton(if (RoomQrHolder.show) "Hide QR" else "QR", { RoomQrHolder.show = !RoomQrHolder.show })
            }
            if (RoomQrHolder.show) {
                QrCode(vm.roomInviteLink(r.code), Modifier.padding(top = 4.dp).size(200.dp))
                Text("Scan to join on a phone (web or app).", color = Brand.Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.roomIsHost) BrandButton(if (s?.locked == true) "Unlock" else "Host only", { vm.toggleRoomLock() })
                BrandButton("Leave", { vm.leaveRoom() })
            }
        }
    }
    val queue = vm.roomQueue
    if (queue.isEmpty()) {
        item(key = "room-empty") { EmptyNote("Room queue is empty", "Use ⋮ → Add to queue on any song. Everyone in the room hears it next.") }
    }
    itemsIndexed(queue, key = { i, t -> "rq-$i-" + t.id }) { i, t ->
        TrackRow(vm, t, onClick = { vm.jumpTo(i) }, leading = "${i + 1}", extraActions = listOf("Remove from the room's queue" to { vm.removeFromQueue(i) }))
    }
}

@Composable
private fun SeekBar(vm: AppViewModel, player: PlayerController, track: Track) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val total = player.duration.takeIf { it > 0f } ?: track.durationSeconds?.toFloat() ?: 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (track.live) {
            Text("● LIVE", Modifier.padding(vertical = 12.dp), color = Brand.Ember, fontWeight = FontWeight.Black, fontSize = 13.sp)
            return@Column
        }
        Slider(
            value = (drag ?: player.position).coerceIn(0f, total.coerceAtLeast(1f)),
            onValueChange = {
                player.seeking = true
                drag = it
            },
            onValueChangeFinished = {
                drag?.let { vm.seek(it) }
                drag = null
                player.seeking = false
            },
            valueRange = 0f..total.coerceAtLeast(1f),
            enabled = total > 0f,
            colors = SliderDefaults.colors(thumbColor = Brand.Gold, activeTrackColor = Brand.Gold, inactiveTrackColor = Brand.Line),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(drag ?: player.position), color = Brand.Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (total > 0f) formatTime(total) else "--:--", color = Brand.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Controls(vm: AppViewModel, player: PlayerController) {
    val q = vm.queue
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.toggleShuffle() }) {
            Icon(AppIcons.Shuffle, if (q.shuffle) "Shuffle on" else "Shuffle off", tint = if (q.shuffle) Brand.Gold else Brand.Muted)
        }
        IconButton(onClick = { vm.previous() }) { Icon(AppIcons.Previous, "Previous", Modifier.size(32.dp), tint = Brand.Paper) }
        Box(
            Modifier.size(72.dp).background(Brand.Gold).clickable(onClick = vm::togglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (player.playing) AppIcons.Pause else AppIcons.Play, if (player.playing) "Pause" else "Play", Modifier.size(40.dp), tint = Brand.GoldInk)
        }
        IconButton(onClick = { vm.next() }, enabled = vm.canSkip) {
            Icon(AppIcons.Next, "Next", Modifier.size(32.dp), tint = if (vm.canSkip) Brand.Paper else Brand.Muted.copy(alpha = 0.4f))
        }
        IconButton(onClick = { vm.cycleRepeat() }) {
            val (icon, label) = when (q.repeat) {
                Repeat.One -> AppIcons.RepeatOne to "Repeat one"
                Repeat.All -> AppIcons.Repeat to "Repeat all"
                Repeat.Off -> AppIcons.Repeat to "Repeat off"
            }
            Icon(icon, label, tint = if (q.repeat == Repeat.Off) Brand.Muted else Brand.Gold)
        }
    }
}

/** Shown above the bottom bar while in a room that has nothing playing (the full player needs a song). */
@Composable
fun RoomIdleBar(vm: AppViewModel) {
    val r = vm.room ?: return
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().card().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Brand.Gold).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(r.code, color = Brand.GoldInk, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            }
            Text(
                listOf("Listen Together", vm.roomMembers, vm.roomStatus).filter { it.isNotBlank() }.joinToString(" · "),
                Modifier.padding(start = 10.dp).weight(1f),
                color = Brand.Muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Text("Nothing playing yet. Pick a song and use ⋮ → Add to queue (or Play) to start the room.", color = Brand.Paper, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton("Invite", {
                copyText(context, "Room invite", vm.roomInviteLink(r.code))
                vm.message = "Invite link copied: it opens on the web or in the app"
            })
            BrandButton(if (RoomQrHolder.show) "Hide QR" else "QR", { RoomQrHolder.show = !RoomQrHolder.show })
            BrandButton("Leave", { vm.leaveRoom() })
        }
        if (RoomQrHolder.show) QrCode(vm.roomInviteLink(r.code), Modifier.size(160.dp))
    }
}
