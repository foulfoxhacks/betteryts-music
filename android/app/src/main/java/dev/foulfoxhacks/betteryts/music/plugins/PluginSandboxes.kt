package dev.foulfoxhacks.betteryts.music.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.mutableStateMapOf
import dev.foulfoxhacks.betteryts.music.core.Core
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Runs community plugins (Player category) in sandboxes. Each plugin gets its own off-screen
 * WebView that loads assets/plugin-sandbox.html (the same runner the website uses) and runs the
 * plugin's code in a Worker inside it. The WebView has network loads blocked and every request
 * intercepted, no file or content access and no storage, and its only bridge is [Bridge.post],
 * which accepts the runner's three message shapes. A plugin only receives what its permissions
 * allow, and its panel text is shown as plain text.
 */
class PluginSandboxes(private val context: Context) {
    /** Panel text per plugin id, for the player. */
    val panels = mutableStateMapOf<String, String>()
    val errors = mutableStateMapOf<String, String>()

    private val main = Handler(Looper.getMainLooper())
    private val running = LinkedHashMap<String, Sandbox>()
    private val runner: String by lazy { context.assets.open("plugin-sandbox.html").bufferedReader().use { it.readText() } }

    /** Starts, restarts or stops sandboxes to match the active community plugins. */
    fun sync(active: List<Core.PluginInfo>) {
        val want = active.filter { it.code.isNotBlank() }.associateBy { it.id }
        for (id in running.keys.toList()) {
            val s = running.getValue(id)
            val p = want[id]
            if (p == null || p.code != s.plugin.code || p.permissions != s.plugin.permissions) {
                s.destroy()
                running.remove(id)
                panels.remove(id)
                errors.remove(id)
            }
        }
        for ((id, p) in want) if (id !in running) running[id] = Sandbox(p)
    }

    /** What plugins with "nowPlaying" get: only the song, nothing about the user. */
    fun nowPlaying(id: String, title: String, artist: String, position: Float, duration: Float, playing: Boolean) {
        if (running.isEmpty()) return
        val data = JSONObject().put("id", id).put("title", title).put("artist", artist).put("channel", artist)
            .put("position", position.toDouble()).put("duration", duration.toDouble()).put("playing", playing)
        for (s in running.values) s.nowPlaying(data)
    }

    fun destroy() {
        running.values.forEach { it.destroy() }
        running.clear()
        panels.clear()
    }

    private inner class Sandbox(val plugin: Core.PluginInfo) {
        private var ready = false
        private var pending: JSONObject? = null

        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        private val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                // The runner page is served from here; nothing else loads, and nothing leaves.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (request.isForMainFrame && request.url.toString() == SANDBOX_URL && request.method == "GET") {
                        WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(runner.toByteArray()))
                    } else {
                        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
            }
            addJavascriptInterface(Bridge(), "BTMPlugin")
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    Log.i(TAG, "sandbox ${plugin.id}: ${m.message().take(200)}")
                    return true
                }
            }
            Log.i(TAG, "starting sandbox: ${plugin.id}")
            loadUrl(SANDBOX_URL)
        }

        fun nowPlaying(data: JSONObject) {
            if ("nowPlaying" !in plugin.permissions) return
            val msg = JSONObject().put("t", "nowPlaying").put("data", data)
            if (ready) send(msg) else pending = msg
        }

        private fun send(msg: JSONObject) = web.evaluateJavascript("window.btmReceive && window.btmReceive(${JSONObject.quote(msg.toString())})", null)

        fun destroy() {
            web.removeJavascriptInterface("BTMPlugin")
            web.destroy()
        }

        /** Called by the runner on a binder thread. Only "ready", "panel" and "error" are accepted. */
        private inner class Bridge {
            @JavascriptInterface
            fun post(json: String) {
                if (json.length > 2_000) return
                val m = runCatching { JSONObject(json) }.getOrNull() ?: return
                main.post {
                    when (m.optString("t")) {
                        "ready" -> if (!ready) {
                            ready = true
                            Log.i(TAG, "sandbox ready: ${plugin.id}")
                            send(JSONObject().put("t", "start").put("code", plugin.code).put("permissions", org.json.JSONArray(plugin.permissions)))
                            pending?.let { send(it) }
                            pending = null
                        }
                        "panel" -> if ("panel" in plugin.permissions) panels[plugin.id] = m.optString("text").take(500)
                        "error" -> {
                            val text = m.optString("text").take(200)
                            Log.w(TAG, "plugin ${plugin.id}: $text")
                            errors[plugin.id] = text
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BTMPlugins"
        private const val SANDBOX_URL = "https://plugin-sandbox.invalid/"
    }
}
