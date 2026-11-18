package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import java.util.Calendar

private val MOODS = listOf(
    "Lo-fi" to "lofi hip hop",
    "Chill" to "chill music mix",
    "Focus" to "focus music",
    "Workout" to "workout music",
    "Party" to "party hits",
    "Sleep" to "sleep music",
    "Hip hop" to "hip hop hits",
    "Pop" to "pop hits",
    "Rock" to "rock classics",
    "Jazz" to "jazz",
    "Anime" to "anime openings",
    "Gaming" to "gaming music",
    "VRChat" to "vrchat music",
)

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(vm: AppViewModel, pad: PaddingValues) {
    val lib = vm.library
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x59FF681E), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(900f, 60f),
                            radius = 700f,
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Column {
                    Eyebrow(greeting())
                    Spacer(Modifier.height(8.dp))
                    DisplayTitle("BetterYTS", size = 58, maxLines = 1)
                    DisplayTitle("Music", size = 58, brush = Brand.HeadingGradient, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    Text("No ads. No account. Just the songs.", color = Brand.Muted, fontSize = 15.sp)
                }
            }
        }

        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(MOODS) { _, (label, q) -> Chip(label, selected = false) { vm.searchFor(q) } }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (lib.history.isNotEmpty()) {
            item { SectionHeader("Listen again") }
            item {
                val history = lib.history.take(20)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(history, key = { _, t -> t.id }) { i, t -> TrackCard(t) { vm.playTracks(history, i) } }
                }
            }
        }

        if (lib.followed.isNotEmpty()) {
            item { SectionHeader("New from your artists", action = "Play all") { (vm.feed as? Load.Ready)?.value?.let { vm.playTracks(it, 0) } } }
            when (val f = vm.feed) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { ErrorBox(f.message) { vm.refreshFeed() } }
                is Load.Ready -> {
                    val tracks = f.value.take(15)
                    if (tracks.isEmpty()) item { EmptyNote("Nothing new", "Your artists haven't posted anything recently.") }
                    itemsIndexed(tracks, key = { _, t -> "feed-" + t.id }) { i, t -> TrackRow(vm, t, onClick = { vm.playTracks(f.value, i) }) }
                }
            }
        }

        if (lib.favorites.isNotEmpty()) {
            item { SectionHeader("Your favorites", action = "Shuffle") { vm.playTracks(lib.favorites, 0, shuffle = true) } }
            item {
                val favs = lib.favorites.take(20)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(favs, key = { _, t -> t.id }) { i, t -> TrackCard(t) { vm.playTracks(lib.favorites, i) } }
                }
            }
        }

        if (lib.isEmpty) {
            item {
                EmptyNote(
                    "Make it yours",
                    "Pick a mood above or search for something you love. Tap ♡ to keep songs, follow artists to get their new releases here, and build playlists. Everything stays on this phone.",
                )
            }
        }

        item {
            Text(
                "BetterYTS Music by FoulFoxHacks · plays through YouTube's official player",
                Modifier.fillMaxWidth().padding(16.dp),
                color = Brand.Muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}
