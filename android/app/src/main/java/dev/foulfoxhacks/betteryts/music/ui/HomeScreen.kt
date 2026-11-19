package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foulfoxhacks.betteryts.music.R
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import java.util.Calendar

private val MOODS = listOf(
    "Relax" to "relaxing music",
    "Energize" to "energetic workout music",
    "Focus" to "focus music",
    "Lo-fi" to "lofi hip hop",
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

/** Logo, wordmark, search and settings: the top of Home and Library. */
@Composable
fun BrandTopBar(vm: AppViewModel, top: Dp) {
    Row(
        Modifier.fillMaxWidth().padding(top = top).height(56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_art),
            null,
            Modifier.size(32.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("BetterYTS Music", color = Brand.Paper, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1)
            Text("BY FOULFOXHACKS", color = Brand.Tan, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
        IconButton(onClick = { vm.selectTab(Tab.Search) }) { Icon(AppIcons.Search, "Search", tint = Brand.Paper) }
        Box(Modifier.padding(horizontal = 8.dp)) { Avatar(vm, 34) { vm.accountOpen = true } }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, pad: PaddingValues) {
    val lib = vm.library
    val feed = (vm.feed as? Load.Ready)?.value.orEmpty()
    val shelfTracks = vm.shelves.flatMap { (it.tracks as? Load.Ready)?.value.orEmpty() }
    // Quick picks: your own music first, topped up from the shelves so it's never empty.
    val quickPicks = (lib.history.take(8) + lib.favorites.take(8) + feed.take(8) + shelfTracks).distinctBy { it.id }.take(20)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Brand.Glow, Color.Transparent), center = Offset(1000f, 0f), radius = 900f)),
        contentPadding = PaddingValues(bottom = pad.calculateBottomPadding()),
    ) {
        item { BrandTopBar(vm, pad.calculateTopPadding()) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MOODS) { (label, q) -> Chip(label, selected = false) { vm.searchFor(q) } }
            }
        }
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                Eyebrow(greeting())
                DisplayTitle("Listen now", size = 40, maxLines = 1)
            }
        }

        // Speed dial: what you play most, nine at a time.
        val dial = (lib.history + lib.favorites).distinctBy { it.id }.take(27)
        if (dial.size >= 3) item { SpeedDial(vm, dial) }

        if (quickPicks.isNotEmpty()) {
            shelfHeader("Start radio from a song", "Quick picks", action = "Play all") { vm.playTracks(quickPicks, 0) }
            item { QuickPicks(vm, quickPicks) }
        }

        if (lib.history.isNotEmpty()) {
            shelfHeader("Your recent plays", "Listen again")
            item {
                val history = lib.history.take(20)
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    // Two rows of cards (144dp art + two title lines + artist) plus the gap.
                    modifier = Modifier.height(232.dp * 2 + 10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(history, key = { _, t -> "again-" + t.id }) { i, t -> TrackCard(t) { vm.playTracks(history, i) } }
                }
            }
        }

        if (lib.followed.isNotEmpty()) {
            shelfHeader("From artists you follow", "New releases", action = "Play all") { vm.playTracks(feed, 0) }
            when (val f = vm.feed) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { ErrorBox(f.message) { vm.refreshFeed() } }
                is Load.Ready -> item {
                    if (f.value.isEmpty()) EmptyNote("Nothing new", "Your artists haven't posted anything recently.")
                    else TrackCarousel(f.value.take(20)) { i -> vm.playTracks(f.value, i) }
                }
            }
        }

        vm.shelves.forEach { shelf ->
            shelfHeader("Mixed for you", shelf.title, action = "Play") { (shelf.tracks as? Load.Ready)?.value?.let { vm.playTracks(it, 0) } }
            item {
                when (val s = shelf.tracks) {
                    Load.Loading -> Loading()
                    is Load.Failed -> ErrorBox(s.message) { vm.retryShelves() }
                    is Load.Ready -> TrackCarousel(s.value) { i -> vm.playTracks(s.value, i) }
                }
            }
        }

        if (lib.playlists.isNotEmpty()) {
            shelfHeader("Made by you", "Your playlists")
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(lib.playlists, key = { "pl-" + it.id }) { p ->
                        Column(Modifier.width(144.dp).clickable { vm.openUserPlaylist(p.id) }) {
                            RemoteImage(p.tracks.firstOrNull()?.art, Modifier.size(144.dp))
                            Text(p.name, Modifier.padding(top = 8.dp), color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.fromYouTube) "From YouTube · ${p.tracks.size}" else "${p.tracks.size} songs", color = Brand.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (lib.favorites.isNotEmpty()) {
            shelfHeader("Songs you love", "Favorites", action = "Shuffle") { vm.playTracks(lib.favorites, 0, shuffle = true) }
            item { TrackCarousel(lib.favorites.take(20)) { i -> vm.playTracks(lib.favorites, i) } }
        }

        if (lib.isEmpty) {
            item {
                EmptyNote(
                    "Make it yours",
                    "Tap ♡ to keep songs, follow artists for their new releases, and build playlists. Or bring your YouTube library over from Library → Connect with Google.",
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

/** Eyebrow + display title, the akasammythepuppy.me / Mello Zone section header. */
private fun LazyListScope.shelfHeader(eyebrow: String, title: String, action: String? = null, onAction: () -> Unit = {}) {
    item {
        Spacer(Modifier.height(14.dp))
        Eyebrow(eyebrow, Modifier.padding(horizontal = 16.dp))
        SectionHeader(title, Modifier.padding(top = 0.dp), action = action, onAction = onAction)
    }
}

@Composable
private fun TrackCarousel(tracks: List<Track>, play: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackCard(t) { play(i) } }
    }
}

/** YouTube Music's "Quick picks": columns of four compact rows, paging sideways. */
@Composable
private fun QuickPicks(vm: AppViewModel, tracks: List<Track>) {
    val columns = tracks.chunked(4)
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
        itemsIndexed(columns) { c, col ->
            Column(Modifier.width(300.dp)) {
                col.forEachIndexed { r, t ->
                    val index = c * 4 + r
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.playTracks(tracks, index) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteImage(t.art, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(t.title, color = Brand.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.subtitle, color = Brand.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TrackMenuButton(vm, t)
                    }
                }
            }
        }
    }
}

/** YouTube Music's Speed dial in the BetterYTS look: square tiles, nine per page, page dots. */
@Composable
private fun SpeedDial(vm: AppViewModel, tracks: List<Track>) {
    val pages = tracks.chunked(9)
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.selectTab(Tab.Library) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(vm, 40)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Eyebrow(vm.displayName)
                Text("Speed dial", color = Brand.Paper, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            Icon(AppIcons.Chevron, "Open your library", tint = Brand.Paper)
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
        ) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pages[p].chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { c, t ->
                            val index = p * 9 + r * 3 + c
                            DialTile(t, Modifier.weight(1f)) { vm.playTracks(tracks, index) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (pages.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { i ->
                    Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(if (pager.currentPage == i) Brand.Paper else Brand.Muted.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

@Composable
private fun DialTile(t: Track, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        RemoteImage(t.largeArt, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
        Text(
            t.title,
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
