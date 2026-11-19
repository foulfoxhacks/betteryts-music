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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Plays songs through YouTube's official embedded player (IFrame API, youtube-nocookie) in a
 * WebView. The player stays visible: when the app is hidden, playback pauses (unless the
 * activity is in picture-in-picture, where the window is still visible), as YouTube's
 * embedded-player terms require.
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

    /** Playback speed chosen by the user. Each new video starts at 1x, so the page re-applies it. */
    var speed by mutableFloatStateOf(1f)
        private set

    /** While the user drags the seek bar, ignore position updates from the player. */
    var seeking = false

    private var startedFor: String? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * Background play: Chromium suspends media in a WebView whose window goes invisible (app in
     * the background, screen off). While this is on, the WebView keeps reporting "visible".
     */
    var keepPlayingWhenHidden = false

    private inner class PlayerWebView(context: Context) : WebView(context) {
        // A stopped activity's window reports GONE; screen-off reports INVISIBLE. Either would
        // make Chromium treat the page as hidden and suspend its media.
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(if (keepPlayingWhenHidden) android.view.View.VISIBLE else visibility)
        }

        override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
            super.onVisibilityChanged(changedView, if (keepPlayingWhenHidden) android.view.View.VISIBLE else visibility)
        }
    }

    val webView: WebView = PlayerWebView(context).apply {
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

            override fun onPageFinished(view: WebView, url: String?) {
                if (pageLoaded) return
                pageLoaded = true
                pendingJs.forEach { view.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }
        addJavascriptInterface(Bridge(), "Bridge")
        // Background play: when the app is hidden the WebView reports the page as hidden, and the
        // embed may pause itself. In YouTube's embed frame only, keep reporting "visible".
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, VISIBILITY_SHIM, setOf("https://www.youtube-nocookie.com", "https://www.youtube.com"))
        }
        // The https base URL gives the embed an origin/referrer; YouTube refuses embeds without one.
        loadDataWithBaseURL(PlayerHtml.ORIGIN + "/", PlayerHtml.PAGE, "text/html", "utf-8", null)
    }

    fun load(id: String, startSeconds: Float = 0f, autoplay: Boolean = true) {
        currentId = id
        position = startSeconds
        duration = 0f
        startedFor = null
        js("load(${JSONObject.quote(id)}, $startSeconds, $autoplay)")
        js("speed($speed)")
    }

    fun play() = js("play()")
    fun pause() = js("pause()")
    fun toggle() = if (playing) pause() else play()

    fun seek(seconds: Float) {
        position = seconds
        js("seek($seconds)")
    }

    fun applySpeed(rate: Float) {
        speed = rate
        Log.i(TAG, "speed $rate")
        js("speed($rate)")
    }

    /** Calls made before the player page exists are queued and run once it has loaded. */
    private var pageLoaded = false
    private val pendingJs = mutableListOf<String>()

    private fun js(code: String) {
        if (pageLoaded) webView.evaluateJavascript(code, null) else pendingJs += code
    }

    /** Activity stopped for real (not PiP): YouTube's terms don't allow hidden playback. */
    fun onStop() {
        pause()
        webView.onPause()
    }

    /** Background play: the app is hidden but the music keeps going (PlaybackService holds it). */
    fun onStopKeepPlaying() {
        // Nothing to do: the WebView keeps running because onPause() isn't called, and the
        // visibility shim stops the page from pausing itself.
        Log.i(TAG, "app hidden, background play continues (playing=$playing)")
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
            // A speed chosen before the page finished loading never reached the player.
            js("speed($speed)")
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

        /** Injected into YouTube's embed frames: the page always looks visible and focused. */
        private val VISIBILITY_SHIM = """
            (function () {
              try {
                var d = Document.prototype;
                Object.defineProperty(d, 'hidden', { get: function () { return false; }, configurable: true });
                Object.defineProperty(d, 'webkitHidden', { get: function () { return false; }, configurable: true });
                Object.defineProperty(d, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
                Object.defineProperty(d, 'webkitVisibilityState', { get: function () { return 'visible'; }, configurable: true });
                d.hasFocus = function () { return true; };
                var stop = function (e) { e.stopImmediatePropagation(); };
                ['visibilitychange', 'webkitvisibilitychange', 'freeze'].forEach(function (t) { document.addEventListener(t, stop, true); });
                ['pagehide', 'blur'].forEach(function (t) { window.addEventListener(t, stop, true); });
                console.log('btm-shim active in ' + location.host);
              } catch (e) { console.log('btm-shim failed: ' + e); }
            })();
        """.trimIndent()

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
