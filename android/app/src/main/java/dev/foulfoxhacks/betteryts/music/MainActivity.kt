package dev.foulfoxhacks.betteryts.music

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.ui.App
import dev.foulfoxhacks.betteryts.music.ui.AppViewModel
import dev.foulfoxhacks.betteryts.music.ui.theme.BetterYTSTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var player: PlayerController

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, whatever the system theme: light status/navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Log.i("BTMActivity", "onCreate (restored=${savedInstanceState != null})")
        player = PlayerController(this)
        vm.attach(player)
        setContent { BetterYTSTheme { App(vm, player) } }
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

    override fun onStop() {
        player.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        Log.i("BTMActivity", "onDestroy (finishing=$isFinishing, config change=$isChangingConfigurations)")
        vm.detach(player)
        player.destroy()
        super.onDestroy()
    }
}
