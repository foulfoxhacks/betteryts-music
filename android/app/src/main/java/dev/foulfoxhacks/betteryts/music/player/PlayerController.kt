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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.webkit.WebViewCompat
import dev.foulfoxhacks.betteryts.music.device.DeviceMusic
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Plays songs through YouTube's official embedded player (IFrame API, youtube-nocookie) in a
 * WebView. The player stays visible: when the app is hidden, playback pauses (unless the
 * activity is in picture-in-picture, where the window is still visible), as YouTube's
 * embedded-player terms require.
 *
 * Songs stored on the phone ("local:" ids) play through a native Media3 ExoPlayer instead;
 * callers don't need to know which one is playing.
 */
class PlayerController(context: Context) {
    private val appContext = context.applicationContext
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

    // ---- Native player for device files ----
    /** True while the current song is a device file played by ExoPlayer. */
    private var local = false
    private var exoPlayer: ExoPlayer? = null

    private fun exo(): ExoPlayer = exoPlayer ?: ExoPlayer.Builder(appContext)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also {
            it.addListener(exoListener)
            exoPlayer = it
        }

    private val exoListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!local) return
            val p = exoPlayer ?: return
            playing = isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
            val id = currentId
            if (isPlaying && id != null && startedFor != id) {
                startedFor = id
                listener?.onStartedPlaying(id)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!local) return
            val p = exoPlayer ?: return
            buffering = state == Player.STATE_BUFFERING
            if (p.duration != C.TIME_UNSET) duration = p.duration / 1000f
            if (state == Player.STATE_ENDED) {
                playing = false
                currentId?.let { listener?.onEnded(it) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!local) return
            Log.w(TAG, "native player error for $currentId", error)
            playing = false
            listener?.onError(currentId, FILE_ERROR)
        }
    }

    /** Position updates for the native player, twice a second like the embed's. */
    private val ticker = object : Runnable {
        override fun run() {
            val p = exoPlayer
            if (!local || p == null) return
            val seconds = p.currentPosition / 1000f
            if (p.duration != C.TIME_UNSET) duration = p.duration / 1000f
            if (!seeking) {
                position = seconds
                currentId?.let { listener?.onProgress(it, seconds, duration) }
            }
            main.postDelayed(this, 500)
        }
    }

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
        // Keep the renderer process important while hidden; by default Android may reclaim it.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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

    /** The song whose end was last reported, so ENDED and the watchdog below fire once per playthrough. */
    private var endedFor: String? = null

    private fun reportEnded(id: String, why: String) {
        if (endedFor == id) return
        endedFor = id
        Log.i(TAG, "ended ($why) $id")
        listener?.onEnded(id)
    }

    /**
     * Backup for a lost ENDED event (a throttled embed in the background can drop or delay it):
     * if the embed sits at the very end and nothing changes, treat the song as finished.
     */
    private var watchdogArmed = false
    private val endWatchdog = Runnable {
        watchdogArmed = false
        val id = currentId ?: return@Runnable
        if (!local && duration > 1f && position >= duration - 0.5f) reportEnded(id, "watchdog")
    }

    fun load(id: String, startSeconds: Float = 0f, autoplay: Boolean = true) {
        main.removeCallbacks(endWatchdog)
        watchdogArmed = false
        endedFor = null
        currentId = id
        position = startSeconds
        duration = 0f
        startedFor = null
        main.removeCallbacks(ticker)
        if (DeviceMusic.isLocal(id)) {
            // Silence the embed first; the two players never play at once.
            if (!local) js("pause()")
            local = true
            playing = false
            val p = exo()
            p.setMediaItem(MediaItem.fromUri(DeviceMusic.uri(id)), (startSeconds * 1000).toLong())
            p.setPlaybackSpeed(speed)
            p.playWhenReady = autoplay
            p.prepare()
            main.post(ticker)
            return
        }
        if (local) {
            local = false
            exoPlayer?.stop()
            playing = false
        }
        js("load(${JSONObject.quote(id)}, $startSeconds, $autoplay)")
        js("speed($speed)")
    }

    fun play() {
        if (local) exoPlayer?.play() else js("play()")
    }

    fun pause() {
        if (local) exoPlayer?.pause() else js("pause()")
    }

    fun toggle() = if (playing) pause() else play()

    fun seek(seconds: Float) {
        position = seconds
        if (local) exoPlayer?.seekTo((seconds * 1000).toLong()) else js("seek($seconds)")
    }

    fun applySpeed(rate: Float) {
        speed = rate
        Log.i(TAG, "speed $rate")
        exoPlayer?.setPlaybackSpeed(rate)
        js("speed($rate)")
    }

    /**
     * Watch-party drift correction: play briefly faster or slower to catch up without a seek.
     * Leaves [speed] alone, so [endNudge] puts the user's choice back.
     */
    fun nudge(rate: Float) {
        exoPlayer?.setPlaybackSpeed(rate)
        if (!local) js("speed($rate)")
    }

    fun endNudge() = nudge(speed)

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
        main.removeCallbacks(ticker)
        main.removeCallbacks(endWatchdog)
        exoPlayer?.release()
        exoPlayer = null
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
            // The embed sits paused while a device file plays; its reports aren't about this song.
            if (local) return@post
            Log.d(TAG, "state $state for $currentId")
            // -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
            playing = state == 1 || state == 3
            buffering = state == 3
            val id = currentId
            if (state == 1 && id != null && startedFor != id) {
                startedFor = id
                listener?.onStartedPlaying(id)
            }
            if (state == 0 && id != null) reportEnded(id, "embed")
        }

        @JavascriptInterface
        fun onError(code: Int) = main.post {
            if (local) return@post
            Log.w(TAG, "player error $code for $currentId")
            listener?.onError(currentId, code)
        }

        @JavascriptInterface
        fun onTime(seconds: Double, total: Double) = main.post {
            if (local) return@post
            if (!seeking) position = seconds.toFloat()
            if (total > 0) duration = total.toFloat()
            val id = currentId
            if (id != null && total > 1) {
                if (seconds < total - 2) {
                    endedFor = null // replaying from the start (repeat one) may end again
                    main.removeCallbacks(endWatchdog)
                    watchdogArmed = false
                } else if (seconds >= total - 0.5 && endedFor != id && !watchdogArmed) {
                    watchdogArmed = true
                    main.postDelayed(endWatchdog, 2_000)
                }
            }
            currentId?.let { if (!seeking) listener?.onProgress(it, seconds.toFloat(), duration) }
        }
    }

    companion object {
        private const val TAG = "BTMPlayer"

        /** Native player failure on a device file (not a YouTube code). */
        const val FILE_ERROR = 9001

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
            FILE_ERROR -> "That file is missing or can't be played. Skipping."
            else -> "Couldn't play that song (error $code). Skipping."
        }
    }
}
