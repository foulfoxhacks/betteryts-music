package dev.foulfoxhacks.betteryts.music.together

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A BetterYTS watch-party room, as the server sends it (src/rooms/protocol.ts). */
data class RoomVideo(val id: String, val title: String)

data class RoomState(
    val video: RoomVideo?,
    val playing: Boolean,
    /** Position (seconds) at [at] (server epoch ms). */
    val position: Float,
    val at: Long,
    val queue: List<RoomVideo>,
    val locked: Boolean,
    val rev: Long,
) {
    companion object {
        private fun video(o: JSONObject?): RoomVideo? = o?.optString("id")?.takeIf { it.length == 11 }?.let { RoomVideo(it, o.optString("title", it)) }

        fun from(o: JSONObject): RoomState {
            val q = o.optJSONArray("queue") ?: JSONArray()
            return RoomState(
                video = video(o.optJSONObject("video")),
                playing = o.optBoolean("playing"),
                position = o.optDouble("position", 0.0).toFloat(),
                at = o.optLong("at"),
                queue = (0 until q.length()).mapNotNull { video(q.optJSONObject(it)) },
                locked = o.optBoolean("locked"),
                rev = o.optLong("rev"),
            )
        }
    }
}

/**
 * One WebSocket to wss://yts.foulfoxhacks.dev/api/rooms/{code}/ws with keep-alive pings,
 * reconnects with backoff, and server-clock correction — the app's twin of the website's
 * web/room.js. Callbacks run on the main thread.
 */
class RoomClient(
    val code: String,
    private val hostKey: String?,
    private var name: String,
    private val events: Events,
) {
    interface Events {
        fun onState(state: RoomState)
        fun onMembers(count: Int, names: List<String>, hosts: List<String>)
        fun onRole(host: Boolean)
        fun onDenied(reason: String)
        fun onStatus(status: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var closed = false
    private var retry = 0

    /** serverTime − localTime, from the last state message. */
    private var clockOffset = 0L
    var isHost = false
        private set

    fun connect() {
        if (closed) return
        events.onStatus("connecting")
        val req = Request.Builder().url("$BASE/api/rooms/$code/ws").header("User-Agent", "BetterYTSMusic/0.1").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    retry = 0
                    events.onStatus("connected")
                    hello()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post { handle(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { reconnect(reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { reconnect(if (response?.code == 429) "Room is full" else t.message ?: "error") }
            }
        })
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "state" -> {
                if (msg.has("serverTime")) clockOffset = msg.optLong("serverTime") - System.currentTimeMillis()
                msg.optJSONObject("state")?.let { events.onState(RoomState.from(it)) }
            }
            "members" -> {
                fun list(key: String): List<String> = msg.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                events.onMembers(msg.optInt("count"), list("names"), list("hosts"))
            }
            "you" -> {
                isHost = msg.optBoolean("host")
                events.onRole(isHost)
            }
            "denied" -> events.onDenied(msg.optString("reason"))
        }
    }

    private fun reconnect(reason: String) {
        socket = null
        if (closed) return
        if (reason == "Room is full") {
            events.onStatus("full")
            return
        }
        events.onStatus("reconnecting")
        val delay = minOf(15_000L, 500L shl minOf(retry++, 5))
        main.postDelayed({ connect() }, delay)
    }

    private fun hello() = send(JSONObject().put("t", "hello").put("name", name).apply { hostKey?.let { put("key", it) } })

    fun setName(n: String) {
        name = n.take(32).ifBlank { "Guest" }
        hello()
    }

    fun send(msg: JSONObject) {
        socket?.send(msg.toString())
    }

    fun send(type: String, vararg pairs: Pair<String, Any?>) = send(JSONObject().put("t", type).apply { pairs.forEach { (k, v) -> put(k, v) } })

    fun video(id: String, title: String) = JSONObject().put("id", id).put("title", title)

    /** Where the room's playback is right now, in seconds, corrected for clock skew. */
    fun positionOf(s: RoomState): Float =
        if (!s.playing) s.position else s.position + ((System.currentTimeMillis() + clockOffset - s.at).coerceAtLeast(0) / 1000f)

    fun close() {
        closed = true
        socket?.close(1000, "leave")
        socket = null
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        const val BASE = "https://yts.foulfoxhacks.dev"
        val CODE = Regex("^[A-HJ-NP-Z2-9]{6}$")
    }
}
