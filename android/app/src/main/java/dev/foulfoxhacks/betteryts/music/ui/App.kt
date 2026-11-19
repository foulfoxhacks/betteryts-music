package dev.foulfoxhacks.betteryts.music.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.LabelStyle

private val NAV_HEIGHT = 64.dp
private val MINI_HEIGHT = 66.dp
private val MINI_VIDEO_WIDTH = (MINI_HEIGHT - 2.dp) * 16f / 9f
private val PLAYER_TOP_BAR = 56.dp

@Composable
fun App(vm: AppViewModel, player: PlayerController) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(m)
        if (vm.message == m) vm.message = null
    }
    BackHandler(enabled = vm.playerExpanded || vm.stack.isNotEmpty() || vm.tab != Tab.Home) { vm.back() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Ink)) {
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
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Screens(vm, PaddingValues(top = top, bottom = 24.dp))
                }
                if (current != null) MiniPlayer(vm, player, current, MINI_HEIGHT, MINI_VIDEO_WIDTH)
                BottomBar(vm, bottom)
            }
            // Status-bar backing: scrolled content never sits under the clock.
            Box(Modifier.fillMaxWidth().height(top).background(Brand.Ink.copy(alpha = 0.94f)))

            AnimatedVisibility(expanded, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                NowPlaying(vm, player, top, bottom, fullVideoHeight)
            }
        }

        // The one and only video view, moved between the mini player, the full player and PiP.
        if (current != null) {
            val miniY = maxHeight - bottom - NAV_HEIGHT - MINI_HEIGHT + 2.dp
            val x by animateDpAsState(if (pip) 0.dp else if (expanded) (maxWidth - fullVideoWidth) / 2 else 0.dp, label = "videoX")
            val y by animateDpAsState(if (pip) 0.dp else if (expanded) top + PLAYER_TOP_BAR else miniY, label = "videoY")
            val w by animateDpAsState(if (pip) maxWidth else if (expanded) fullVideoWidth else MINI_VIDEO_WIDTH, label = "videoW")
            val h by animateDpAsState(if (pip) maxHeight else if (expanded) fullVideoHeight else MINI_HEIGHT - 2.dp, label = "videoH")
            AndroidView(
                factory = {
                    (player.webView.parent as? ViewGroup)?.removeView(player.webView)
                    player.webView
                },
                modifier = Modifier.offset(x, y).size(w, h),
            )
            // Taps on the video: open the player when it's small, play/pause when it's big.
            if (!pip) {
                Box(Modifier.offset(x, y).size(w, h).clickable { if (expanded) vm.togglePlay() else vm.playerExpanded = true })
            }
        }

        if (!pip) {
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + NAV_HEIGHT + (if (current != null && !expanded) MINI_HEIGHT else 0.dp) + 8.dp),
            ) { data -> Snackbar(data, containerColor = Brand.Raised, contentColor = Brand.Paper) }

            vm.playlistPickerFor?.let { AddToPlaylistDialog(vm, it) }
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
        Route.SettingsRoute -> SettingsScreen(vm, pad)
        null -> when (vm.tab) {
            Tab.Home -> HomeScreen(vm, pad)
            Tab.Explore -> ExploreScreen(vm, pad)
            Tab.Search -> SearchScreen(vm, pad)
            Tab.Library -> LibraryScreen(vm, pad)
        }
    }
}

@Composable
private fun BottomBar(vm: AppViewModel, bottomInset: Dp) {
    Column(Modifier.fillMaxWidth().background(Brand.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.LineGold))
        Row(Modifier.fillMaxWidth().height(NAV_HEIGHT - 1.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavItem(vm, Tab.Home, "Home", AppIcons.Home)
            NavItem(vm, Tab.Explore, "Explore", AppIcons.Explore)
            NavItem(vm, Tab.Library, "Library", AppIcons.Library)
        }
        Box(Modifier.fillMaxWidth().height(bottomInset))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(vm: AppViewModel, tab: Tab, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val selected = vm.tab == tab && !vm.playerExpanded
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable { vm.selectTab(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(28.dp).height(2.dp).background(if (selected) Brand.Gold else Brand.Surface))
        Icon(icon, null, Modifier.padding(top = 6.dp).size(24.dp), tint = if (selected) Brand.Gold else Brand.Muted)
        Text(label.uppercase(), style = LabelStyle, fontSize = 10.sp, color = if (selected) Brand.Gold else Brand.Muted)
    }
}
