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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

/** Moods & genres: label, search, and the colour stripe on the card (YouTube Music style). */
private val MOODS_GENRES = listOf(
    Triple("Chill", "chill music", Color(0xFF3F86FF)),
    Triple("Workout", "workout music", Color(0xFFFF681E)),
    Triple("Focus", "focus music instrumental", Color(0xFF9B6CFF)),
    Triple("Party", "party hits", Color(0xFFFF8DD0)),
    Triple("Sleep", "sleep music", Color(0xFF135CFF)),
    Triple("Feel good", "feel good songs", Color(0xFFFFC857)),
    Triple("Romance", "love songs", Color(0xFFEF8FCB)),
    Triple("Commute", "driving music", Color(0xFFB87333)),
    Triple("Hip hop", "hip hop hits", Color(0xFFF0C890)),
    Triple("Pop", "pop hits", Color(0xFFF4B8E4)),
    Triple("Rock", "rock classics", Color(0xFFFF994A)),
    Triple("Electronic", "electronic dance music", Color(0xFF7637FF)),
    Triple("Lo-fi", "lofi hip hop", Color(0xFFA8C7FF)),
    Triple("Anime", "anime openings", Color(0xFFFF6B6B)),
    Triple("Gaming", "gaming music", Color(0xFF5FD4FF)),
    Triple("VRChat", "vrchat music", Color(0xFFCDBDA9)),
)

@Composable
fun ExploreScreen(vm: AppViewModel, pad: PaddingValues) {
    LaunchedEffect(Unit) { vm.loadExplore() }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Brand.Glow, Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 900f)),
        contentPadding = PaddingValues(bottom = pad.calculateBottomPadding()),
    ) {
        item { BrandTopBar(vm, pad.calculateTopPadding()) }
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Eyebrow("What everyone's playing")
                DisplayTitle("Explore", size = 44, maxLines = 1)
            }
        }

        vm.exploreShelves.forEach { shelf ->
            item {
                Spacer(Modifier.height(14.dp))
                SectionHeader(shelf.title, action = "Play") { (shelf.tracks as? Load.Ready)?.value?.let { vm.playTracks(it, 0) } }
                when (val s = shelf.tracks) {
                    Load.Loading -> Loading()
                    is Load.Failed -> ErrorBox(s.message) { vm.loadExplore(force = true) }
                    is Load.Ready -> {
                        // Charts read as a numbered list; the rest as cover carousels.
                        if (shelf.title.startsWith("Charts")) ChartColumns(vm, s.value)
                        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(s.value, key = { _, t -> t.id }) { i, t -> TrackCard(t) { vm.playTracks(s.value, i) } }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            SectionHeader("Moods & genres")
        }
        // Two columns of colour-striped cards.
        MOODS_GENRES.chunked(2).forEach { row ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, q, color) ->
                        Row(
                            Modifier.weight(1f).height(52.dp).background(Brand.Raised).clickable { vm.searchFor(q) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(6.dp).fillMaxHeight().background(color))
                            Text(label, Modifier.padding(horizontal = 14.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Chart: numbered rows in columns of four, paging sideways. */
@Composable
private fun ChartColumns(vm: AppViewModel, tracks: List<dev.foulfoxhacks.betteryts.music.core.Track>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
        itemsIndexed(tracks.chunked(4)) { c, col ->
            Column(Modifier.width(310.dp)) {
                col.forEachIndexed { r, t ->
                    val i = c * 4 + r
                    TrackRow(vm, t, onClick = { vm.playTracks(tracks, i) }, leading = "${i + 1}")
                }
            }
        }
    }
}
