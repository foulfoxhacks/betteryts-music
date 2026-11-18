package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.core.Repeat
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Bar above the bottom navigation. The video sits in the first [videoWidth] (drawn by the App's video layer). */
@Composable
fun MiniPlayer(vm: AppViewModel, player: PlayerController, track: Track, height: Dp, videoWidth: Dp) {
    Column(Modifier.fillMaxWidth().background(Brand.Surface)) {
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
            IconButton(onClick = { vm.next() }, enabled = vm.queue.hasNext) {
                Icon(AppIcons.Next, "Next", tint = if (vm.queue.hasNext) Brand.Paper else Brand.Muted.copy(alpha = 0.4f))
            }
        }
    }
}

/** Full-screen player. The video occupies [videoHeight] under the top bar (drawn by the App's video layer). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlaying(vm: AppViewModel, player: PlayerController, top: Dp, bottom: Dp, videoHeight: Dp) {
    val q = vm.queue
    val track = q.current ?: return
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Ink)
            .background(Brush.radialGradient(listOf(Color(0x40FF681E), Color.Transparent), radius = 900f))
            .padding(top = top),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.playerExpanded = false }) { Icon(AppIcons.Collapse, "Close player", tint = Brand.Paper) }
            Eyebrow("Now playing", Modifier.weight(1f))
            Text("${q.position + 1} / ${q.tracks.size}", Modifier.padding(end = 16.dp), color = Brand.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(videoHeight))

        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom + 16.dp)) {
            item {
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
                    }
                    val fav = vm.library.isFavorite(track.id)
                    IconButton(onClick = { vm.toggleFavorite(track) }) {
                        Icon(if (fav) AppIcons.Favorite else AppIcons.FavoriteBorder, if (fav) "Remove from favorites" else "Add to favorites", tint = if (fav) Brand.Pink else Brand.Paper)
                    }
                    IconButton(onClick = { vm.playlistPickerFor = track }) { Icon(AppIcons.PlaylistAdd, "Add to playlist", tint = Brand.Paper) }
                }
            }
            item { SeekBar(vm, player, track) }
            item { Controls(vm, player) }
            item {
                Text(
                    "Plays through YouTube's official player, so music pauses when you leave the app.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Brand.Muted.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
            item { SectionHeader("Up next") }
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
                    },
                )
            }
        }
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
        IconButton(onClick = { vm.next() }, enabled = q.hasNext) {
            Icon(AppIcons.Next, "Next", Modifier.size(32.dp), tint = if (q.hasNext) Brand.Paper else Brand.Muted.copy(alpha = 0.4f))
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
