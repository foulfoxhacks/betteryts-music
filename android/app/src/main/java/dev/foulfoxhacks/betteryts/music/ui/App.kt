package dev.foulfoxhacks.betteryts.music.ui

import androidx.compose.runtime.getValue
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand

private val NAV_HEIGHT = 80.dp
private val MINI_HEIGHT = 66.dp
private val MINI_VIDEO_WIDTH = (MINI_HEIGHT - 2.dp) * 16f / 9f
private val PLAYER_TOP_BAR = 56.dp
private val RAIL_WIDTH = 80.dp

/** The tabs chosen in Settings › Customize (Home always first), with their icons. */
private fun tabs(): List<Triple<Tab, String, ImageVector>> = Brand.look.tabs.mapNotNull {
    when (it) {
        "Home" -> Triple(Tab.Home, "Home", AppIcons.Home)
        "Explore" -> Triple(Tab.Explore, "Explore", AppIcons.Explore)
        "Search" -> Triple(Tab.Search, "Search", AppIcons.Search)
        "Library" -> Triple(Tab.Library, "Library", AppIcons.Library)
        else -> null
    }
}

/** Wide screens: the tabs as a side rail, with Settings at the bottom. */
@Composable
private fun SideRail(vm: AppViewModel, top: Dp, bottom: Dp) {
    androidx.compose.material3.NavigationRail(
        Modifier.width(RAIL_WIDTH).fillMaxHeight(),
        containerColor = Brand.Surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Box(Modifier.height(top + 8.dp))
        tabs().forEach { (tab, label, icon) ->
            androidx.compose.material3.NavigationRailItem(
                selected = vm.tab == tab && !vm.playerExpanded,
                onClick = { vm.selectTab(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, maxLines = 1, fontWeight = FontWeight.Bold) },
                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                    selectedIconColor = Brand.OnContainer, indicatorColor = Brand.Container, selectedTextColor = Brand.Paper,
                    unselectedIconColor = Brand.Muted, unselectedTextColor = Brand.Muted,
                ),
            )
        }
        Box(Modifier.weight(1f))
        androidx.compose.material3.NavigationRailItem(
            selected = false,
            onClick = { vm.openSettings() },
            icon = { Icon(AppIcons.Settings, contentDescription = "Settings") },
            colors = androidx.compose.material3.NavigationRailItemDefaults.colors(unselectedIconColor = Brand.Muted),
        )
        Box(Modifier.height(bottom + 8.dp))
    }
}

@Composable
fun App(vm: AppViewModel, player: PlayerController) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        val action = vm.messageAction
        vm.messageAction = null
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(m, actionLabel = action?.first, duration = if (action != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short)
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) action?.second?.invoke()
        if (vm.message == m) vm.message = null
    }
    BackHandler(enabled = vm.playerExpanded || vm.stack.isNotEmpty() || vm.tab != Tab.Home) { vm.back() }

    val look = Brand.look
    // Glass and Ambient: a soft wash of the accent (album colours when that's on) behind everything.
    val backdrop = if (look.style == "flat") Modifier else Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Brand.Gold.copy(alpha = if (Brand.palette.light) 0.16f else 0.26f), Brand.Violet.copy(alpha = 0.08f), Brand.Ink.copy(alpha = 0f)), endY = 1400f))
    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Ink).then(backdrop)) {
        // Tablets, foldables and landscape: a side rail instead of the bottom bar.
        val wide = look.wideLayout && maxWidth >= 600.dp
        val railWidth = if (wide) RAIL_WIDTH else 0.dp
        val navHeight = if (wide) 0.dp else NAV_HEIGHT
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val current = vm.nowPlaying
        val expanded = vm.playerExpanded && current != null
        val pip = vm.inPip && current != null

        // Full player. Its video slot is sized to fit landscape screens too.
        val fullVideoWidth = min(maxWidth, (maxHeight - top - PLAYER_TOP_BAR) * 0.55f * 16f / 9f)
        val fullVideoHeight = fullVideoWidth * 9f / 16f

        // In picture-in-picture the video fills the window and everything else stays out of it.
        if (!pip) {
            // Content, mini player, bottom navigation.
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                if (wide) SideRail(vm, top, bottom)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Screens(vm, PaddingValues(top = top, bottom = 24.dp))
                    }
                    UpdateBanner(vm)
                    if (current != null) MiniPlayer(vm, player, current, MINI_HEIGHT, MINI_VIDEO_WIDTH)
                    // In a room with nothing playing there's no player to hold the room card.
                    else if (vm.room != null) RoomIdleBar(vm)
                    if (wide) Box(Modifier.fillMaxWidth().height(bottom)) else BottomBar(vm, bottom)
                }
            }
            // Status-bar backing: scrolled content never sits under the clock.
            Box(Modifier.fillMaxWidth().height(top).background(Brand.Ink.copy(alpha = 0.94f)))

            AnimatedVisibility(
                expanded,
                enter = if (look.reducedMotion) fadeIn(androidx.compose.animation.core.snap()) else slideInVertically { it } + fadeIn(),
                exit = if (look.reducedMotion) fadeOut(androidx.compose.animation.core.snap()) else slideOutVertically { it } + fadeOut(),
            ) {
                NowPlaying(vm, player, top, bottom, fullVideoHeight)
            }
        }

        // The one and only video view, moved between the mini player, the full player and PiP.
        if (current != null) {
            val miniY = maxHeight - bottom - navHeight - MINI_HEIGHT + 2.dp
            val spec: androidx.compose.animation.core.AnimationSpec<Dp> = if (look.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring()
            val x by animateDpAsState(if (pip) 0.dp else if (expanded) (maxWidth - fullVideoWidth) / 2 else railWidth, spec, label = "videoX")
            val y by animateDpAsState(if (pip) 0.dp else if (expanded) top + PLAYER_TOP_BAR else miniY, spec, label = "videoY")
            val w by animateDpAsState(if (pip) maxWidth else if (expanded) fullVideoWidth else MINI_VIDEO_WIDTH, spec, label = "videoW")
            val h by animateDpAsState(if (pip) maxHeight else if (expanded) fullVideoHeight else MINI_HEIGHT - 2.dp, spec, label = "videoH")
            AndroidView(
                factory = {
                    (player.webView.parent as? ViewGroup)?.removeView(player.webView)
                    player.webView
                },
                modifier = Modifier.offset(x, y).size(w, h),
            )
            // Device files have no video: show their cover art in the video's place.
            if (current.isLocal) {
                Box(Modifier.offset(x, y).size(w, h).background(Brand.Ink), contentAlignment = Alignment.Center) {
                    RemoteImage(current.largeArt, Modifier.fillMaxHeight().aspectRatio(1f))
                }
            }
            // Taps on the video: open the player when it's small, play/pause when it's big.
            // Big player: double-tap the sides to skip ±10 s, swipe up/down on the right for volume
            // and on the left for brightness (YouTube-style), with a short hint on the video.
            if (!pip && !expanded) {
                Box(Modifier.offset(x, y).size(w, h).clickable { vm.playerExpanded = true })
            } else if (!pip) {
                PlayerGestures(vm, Modifier.offset(x, y).size(w, h))
            }
        }

        if (!pip) {
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + navHeight + (if (current != null && !expanded) MINI_HEIGHT else 0.dp) + 8.dp),
            ) { data -> Snackbar(data, shape = RoundedCornerShape(14.dp), containerColor = Brand.Raised, contentColor = Brand.Paper, actionColor = Brand.Gold) }

            vm.playlistPickerFor?.let { AddToPlaylistDialog(vm, it) }
            vm.pendingBackup?.let { BackupPreviewDialog(vm, it) }
            if (vm.soundOpen) SoundPanel(vm, player) { vm.soundOpen = false }
            if (vm.lanOpen) LanSheet(vm) { vm.lanOpen = false }
            if (vm.accountOpen) AccountSheet(vm)
            if (vm.joinRoomDialog) {
                JoinRoomDialog(onDismiss = { vm.joinRoomDialog = false }) { code ->
                    vm.joinRoomDialog = false
                    vm.joinRoom(code)
                }
            }
            vm.newPlaylistWith?.let { tracks ->
                NamePlaylistDialog("New playlist", "", "Create", onDismiss = { vm.newPlaylistWith = null }) { name ->
                    vm.newPlaylistWith = null
                    vm.createPlaylist(name, tracks, open = tracks.isEmpty())
                }
            }
        }
    }
}

@Composable
private fun Screens(vm: AppViewModel, pad: PaddingValues) {
    when (val route = vm.stack.lastOrNull()) {
        is Route.ArtistRoute -> ArtistScreen(vm, route, pad)
        is Route.CollectionRoute -> CollectionScreen(vm, route.collection, pad)
        is Route.PlaylistRoute -> UserPlaylistScreen(vm, route.id, pad)
        is Route.SmartRoute -> SmartPlaylistScreen(vm, route, pad)
        Route.SettingsRoute -> SettingsScreen(vm, pad)
        Route.DiagnosticsRoute -> DiagnosticsScreen(vm, pad)
        Route.CustomizeRoute -> CustomizeScreen(vm, pad)
        null -> when (vm.tab) {
            Tab.Home -> HomeScreen(vm, pad)
            Tab.Explore -> ExploreScreen(vm, pad)
            Tab.Search -> SearchScreen(vm, pad)
            Tab.Library -> LibraryScreen(vm, pad)
        }
    }
}

/** Material bottom navigation with a pill indicator, as in the Mello Zone app. */
@Composable
private fun BottomBar(vm: AppViewModel, bottomInset: Dp) {
    Column(Modifier.fillMaxWidth().background(Brand.Surface)) {
        NavigationBar(Modifier.height(NAV_HEIGHT), containerColor = Brand.Surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
            tabs().forEach { (tab, label, icon) -> NavItem(vm, tab, label, icon) }
        }
        Box(Modifier.fillMaxWidth().height(bottomInset))
    }
}

@Composable
private fun RowScope.NavItem(vm: AppViewModel, tab: Tab, label: String, icon: ImageVector) {
    NavigationBarItem(
        selected = vm.tab == tab && !vm.playerExpanded,
        onClick = { vm.selectTab(tab) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, maxLines = 1, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Brand.OnContainer,
            indicatorColor = Brand.Container,
            selectedTextColor = Brand.Paper,
            unselectedIconColor = Brand.Muted,
            unselectedTextColor = Brand.Muted,
        ),
    )
}
