package dev.foulfoxhacks.betteryts.music.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Plays songs through YouTube's official embedded player (IFrame API, youtube-nocookie) in a
 * WebView. The player stays visible on screen, and playback stops when the app leaves the
 * foreground, as YouTube's embedded-player terms require.
 */
class PlayerController(context: Context) {
    interface Listener {
        fun onStartedPlaying(id: String)
        fun onEnded(id: String)
        fun onError(id: String?, code: Int)

        /** About twice a second while a song is loaded; plugins (SponsorBlock) use it. */
        fun onProgress(id: String, seconds: Float, duration: Float) {}
    }

    var listener: Listener? = null

    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var position by mutableFloatStateOf(0f)
        private set
    var duration by mutableFloatStateOf(0f)
        private set
    var currentId by mutableStateOf<String?>(null)
        private set

    /** While the user drags the seek bar, ignore position updates from the player. */
    var seeking = false

    private var startedFor: String? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(TAG, "js: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            // Links inside the player (YouTube logo, "watch on YouTube") must not navigate the player away.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        addJavascriptInterface(Bridge(), "Bridge")
        // The https base URL gives the embed an origin/referrer; YouTube refuses embeds without one.
        loadDataWithBaseURL(PlayerHtml.ORIGIN + "/", PlayerHtml.PAGE, "text/html", "utf-8", null)
    }

    fun load(id: String, startSeconds: Float = 0f, autoplay: Boolean = true) {
        currentId = id
        position = startSeconds
        duration = 0f
        startedFor = null
        js("load(${JSONObject.quote(id)}, $startSeconds, $autoplay)")
    }

    fun play() = js("play()")
    fun pause() = js("pause()")
    fun toggle() = if (playing) pause() else play()

    fun seek(seconds: Float) {
        position = seconds
        js("seek($seconds)")
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)

    /** Activity stopped: YouTube's terms don't allow background playback. */
    fun onStop() {
        pause()
        webView.onPause()
    }

    fun onStart() = webView.onResume()

    fun destroy() {
        listener = null
        webView.destroy()
    }

    /** Called from the page's JavaScript on a binder thread; everything hops to the main thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = main.post {
            Log.i(TAG, "player ready")
            ready = true
        }

        @JavascriptInterface
        fun onState(state: Int) = main.post {
            Log.d(TAG, "state $state for $currentId")
            // -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
            playing = state == 1 || state == 3
            buffering = state == 3
            val id = currentId
            if (state == 1 && id != null && startedFor != id) {
                startedFor = id
                listener?.onStartedPlaying(id)
            }
            if (state == 0 && id != null) listener?.onEnded(id)
        }

        @JavascriptInterface
        fun onError(code: Int) = main.post {
            Log.w(TAG, "player error $code for $currentId")
            listener?.onError(currentId, code)
        }

        @JavascriptInterface
        fun onTime(seconds: Double, total: Double) = main.post {
            if (!seeking) position = seconds.toFloat()
            if (total > 0) duration = total.toFloat()
            currentId?.let { if (!seeking) listener?.onProgress(it, seconds.toFloat(), duration) }
        }
    }

    companion object {
        private const val TAG = "BTMPlayer"

        /** YouTube's player error codes, in words. */
        fun describe(code: Int) = when (code) {
            2 -> "That song's link isn't valid."
            5 -> "The player couldn't play that song."
            100 -> "That song was removed or made private."
            101, 150 -> "The uploader doesn't allow this song in other apps. Skipping."
            152, 153 -> "YouTube refused to load the player. Skipping."
            else -> "Couldn't play that song (error $code). Skipping."
        }
    }
}
