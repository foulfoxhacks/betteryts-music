package dev.foulfoxhacks.betteryts.music

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import dev.foulfoxhacks.betteryts.music.player.PlaybackHub
import dev.foulfoxhacks.betteryts.music.player.PlaybackService
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.App
import dev.foulfoxhacks.betteryts.music.ui.ImageCache
import dev.foulfoxhacks.betteryts.music.ui.AppViewModel
import dev.foulfoxhacks.betteryts.music.ui.theme.BetterYTSTheme

private const val ACTION_PIP_TOGGLE = "dev.foulfoxhacks.betteryts.music.PIP_TOGGLE"
private const val ACTION_PIP_NEXT = "dev.foulfoxhacks.betteryts.music.PIP_NEXT"

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var player: PlayerController
    private val main = Handler(Looper.getMainLooper())

    /**
     * PiP keeps the player visible, so it may keep playing while the user is in another app.
     * The window's buttons come back through this receiver.
     */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_TOGGLE -> if (player.playing) player.pause() else player.play()
                ACTION_PIP_NEXT -> vm.next()
            }
            // The page reports its new state a moment later; refresh the button after that.
            main.postDelayed(::refreshPipParams, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, whatever the system theme: light status/navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Log.i("BTMActivity", "onCreate (restored=${savedInstanceState != null})")
        ImageCache.context = applicationContext
        player = PlayerController(this)
        vm.attach(player)
        // Notification, lock-screen, headset and car buttons (Background play).
        PlaybackHub.controls = object : PlaybackHub.Controls {
            override fun play() { if (!player.playing) vm.togglePlay() }
            override fun pause() { if (player.playing) vm.togglePlay() }
            override fun next() { vm.next() }
            override fun previous() { vm.previous() }
            override fun seekTo(seconds: Float) { vm.seek(seconds) }
        }
        syncMediaNotification()
        setContent {
            BetterYTSTheme {
                // Keep the PiP play/pause button in step with the page.
                LaunchedEffect(player.playing) { refreshPipParams() }
                App(vm, player)
            }
        }
        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            IntentFilter().apply {
                addAction(ACTION_PIP_TOGGLE)
                addAction(ACTION_PIP_NEXT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "betterytsmusic" && data.host == "oauth") {
            vm.finishGoogleSignIn(data)
            return
        }
        // Watch-party invites: yts.foulfoxhacks.dev/?room=CODE or /room/CODE
        if (intent?.action == Intent.ACTION_VIEW && data?.host == "yts.foulfoxhacks.dev") {
            val code = data.getQueryParameter("room") ?: data.pathSegments.takeIf { it.size == 2 && it[0] == "room" }?.get(1)
            if (code != null) {
                vm.joinRoom(code)
                return
            }
        }
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) vm.openLink(text)
    }

    override fun onStart() {
        super.onStart()
        player.onStart()
    }

    /** Home/recents while music plays: shrink to a floating player instead of going quiet. */
    /** Background play on: music just keeps going (like a music app). Off: shrink to PiP. */
    private val backgroundPlay get() = vm.pluginOn("background")

    /**
     * Keeps the media notification / lock screen in step with the player. This must not live in
     * a Composable: Compose stops recomposing once the activity is stopped, which is exactly when
     * background play needs the notification (and the foreground service) to follow song changes.
     * snapshotFlow is driven by snapshot writes, not frames, so it keeps running while hidden.
     */
    private fun syncMediaNotification() {
        lifecycleScope.launch {
            snapshotFlow {
                val on = backgroundPlay
                player.keepPlayingWhenHidden = on
                val now = vm.nowPlaying
                // Position in whole seconds: the session extrapolates in between.
                if (on && now != null) {
                    PlaybackHub.Now(now.id, now.title, now.artist, now.largeArt, player.playing, player.position.toInt().toFloat(), player.duration, vm.canSkip)
                } else {
                    null
                }
            }.distinctUntilChanged().collect { next ->
                // Start the service when music starts (the app is in the foreground then, as Android requires).
                if (next != null && next.playing && PlaybackHub.now?.playing != true) PlaybackService.start(this@MainActivity)
                PlaybackHub.update(next)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (backgroundPlay) return
        if (isInPictureInPictureMode || !::player.isInitialized || !player.playing) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            val entered = enterPictureInPictureMode(pipParams())
            Log.i("BTMActivity", "picture-in-picture: $entered")
        } catch (e: IllegalStateException) {
            Log.w("BTMActivity", "couldn't enter picture-in-picture", e)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) refreshPipParams()
        Log.i("BTMActivity", "picture-in-picture mode: $isInPictureInPictureMode")
    }

    /** The PiP window is part of the app's visible surface, so only a real stop pauses. */
    override fun onStop() {
        when {
            isInPictureInPictureMode -> Unit
            backgroundPlay && player.playing -> player.onStopKeepPlaying()
            else -> player.onStop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        Log.i("BTMActivity", "onDestroy (finishing=$isFinishing, config change=$isChangingConfigurations)")
        // The player dies with the activity, so the notification goes too.
        PlaybackHub.controls = null
        PlaybackHub.update(null)
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: IllegalArgumentException) {
        }
        vm.detach(player)
        player.destroy()
        super.onDestroy()
    }

    private fun refreshPipParams() {
        if (!::player.isInitialized) return
        setPictureInPictureParams(pipParams())
    }

    private fun pipParams(): PictureInPictureParams {
        val playing = ::player.isInitialized && player.playing
        val toggle = remoteAction(
            icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            label = if (playing) "Pause" else "Play",
            requestCode = 1,
            action = ACTION_PIP_TOGGLE,
        )
        val next = remoteAction(android.R.drawable.ic_media_next, "Next", 2, ACTION_PIP_NEXT)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(toggle, next))
            .build()
    }

    private fun remoteAction(icon: Int, label: String, requestCode: Int, action: String) = RemoteAction(
        Icon.createWithResource(this, icon),
        label,
        label,
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
}
