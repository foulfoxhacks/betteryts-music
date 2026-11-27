package dev.foulfoxhacks.betteryts.music.core

import org.json.JSONArray
import org.json.JSONObject

// Kotlin mirrors of the core's newer features: smart playlists and insights (core/src/smart.rs),
// stations (stations.rs), backup previews (backup.rs), VRChat send previews (inworld.rs) and
// diagnostics (diag.rs).

private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

/**
 * A smart-playlist rule, as the core reads it. Null fields mean "any". [source] is one of
 * all, favorites, history, playlists, played; [sort] one of natural, recentlyPlayed, mostPlayed,
 * leastPlayed, title, artist, shortest, longest.
 */
data class SmartRule(
    val source: String = "all",
    val minSeconds: Int? = null,
    val maxSeconds: Int? = null,
    val artist: String? = null,
    val title: String? = null,
    val minPlays: Int? = null,
    val maxPlays: Int? = null,
    val playedWithinDays: Int? = null,
    val notPlayedForDays: Int? = null,
    val excludeLive: Boolean = false,
    val excludeDevice: Boolean = false,
    val sort: String = "natural",
    val limit: Int = 100,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        minSeconds?.let { put("minSeconds", it) }
        maxSeconds?.let { put("maxSeconds", it) }
        artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        minPlays?.let { put("minPlays", it) }
        maxPlays?.let { put("maxPlays", it) }
        playedWithinDays?.let { put("playedWithinDays", it) }
        notPlayedForDays?.let { put("notPlayedForDays", it) }
        put("excludeLive", excludeLive)
        put("excludeDevice", excludeDevice)
        put("sort", sort)
        put("limit", limit)
    }

    companion object {
        fun from(o: JSONObject?): SmartRule {
            if (o == null) return SmartRule()
            return SmartRule(
                source = o.str("source") ?: "all",
                minSeconds = o.intOrNull("minSeconds"),
                maxSeconds = o.intOrNull("maxSeconds"),
                artist = o.str("artist"),
                title = o.str("title"),
                minPlays = o.intOrNull("minPlays"),
                maxPlays = o.intOrNull("maxPlays"),
                playedWithinDays = o.intOrNull("playedWithinDays"),
                notPlayedForDays = o.intOrNull("notPlayedForDays"),
                excludeLive = o.optBoolean("excludeLive"),
                excludeDevice = o.optBoolean("excludeDevice"),
                sort = o.str("sort") ?: "natural",
                limit = o.optInt("limit", 100),
            )
        }
    }
}

data class SmartPlaylist(
    val id: String,
    val name: String,
    val description: String,
    val builtIn: Boolean,
    val count: Int,
    val art: List<String>,
    val rule: SmartRule,
) {
    companion object {
        fun from(o: JSONObject): SmartPlaylist? {
            val id = o.str("id") ?: return null
            return SmartPlaylist(
                id = id,
                name = o.str("name") ?: "Smart playlist",
                description = o.str("description") ?: "",
                builtIn = o.optBoolean("builtIn"),
                count = o.optInt("count"),
                art = o.optJSONArray("art")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } ?: emptyList(),
                rule = SmartRule.from(o.optJSONObject("rule")),
            )
        }
    }
}

data class TopTrack(val track: Track, val plays: Int)

data class Insights(
    val totalPlays: Long = 0,
    val distinctTracks: Int = 0,
    val distinctArtists: Int = 0,
    val minutes: Long = 0,
    val topArtists: List<Pair<String, Int>> = emptyList(),
    val topTracks: List<TopTrack> = emptyList(),
    val hours: List<Int> = List(24) { 0 },
    val peakHour: Int? = null,
    val listener: String? = null,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val daysActive: Int = 0,
    val since: Long? = null,
) {
    companion object {
        fun from(o: JSONObject): Insights {
            val h = o.optJSONArray("hours")
            return Insights(
                totalPlays = o.optLong("totalPlays"),
                distinctTracks = o.optInt("distinctTracks"),
                distinctArtists = o.optInt("distinctArtists"),
                minutes = o.optLong("minutes"),
                topArtists = o.optJSONArray("topArtists").mapObjects { (it.str("name") ?: return@mapObjects null) to it.optInt("plays") },
                topTracks = o.optJSONArray("topTracks").mapObjects { t -> t.optJSONObject("track")?.let(Track::from)?.let { TopTrack(it, t.optInt("plays")) } },
                hours = List(24) { i -> h?.optInt(i) ?: 0 },
                peakHour = o.intOrNull("peakHour"),
                listener = o.str("listener"),
                currentStreak = o.optInt("currentStreak"),
                longestStreak = o.optInt("longestStreak"),
                daysActive = o.optInt("daysActive"),
                since = if (o.has("since") && !o.isNull("since")) o.optLong("since") else null,
            )
        }
    }
}

/** A radio station. [kind] is track, artist, playlist (YouTube) or library (your playlist); [mix] is familiar, balanced or discover. */
data class Station(
    val id: String,
    val kind: String,
    val seedId: String,
    val title: String,
    val art: String?,
    val mix: String,
    val saved: Boolean,
    val played: Int,
    val skips: Int,
    val blocked: Int,
    val lastPlayedAt: Long,
) {
    val label: String
        get() = when (kind) {
            "artist" -> "$title radio"
            "playlist", "library" -> "Radio from $title"
            else -> "Songs like $title"
        }

    companion object {
        fun from(o: JSONObject?): Station? {
            if (o == null) return null
            val seed = o.optJSONObject("seed") ?: return null
            return Station(
                id = o.str("id") ?: return null,
                kind = seed.str("kind") ?: "track",
                seedId = seed.str("id") ?: return null,
                title = seed.str("title") ?: "Radio",
                art = seed.str("art"),
                mix = o.str("mix") ?: "balanced",
                saved = o.optBoolean("saved"),
                played = o.optInt("played"),
                skips = o.optInt("skips"),
                blocked = o.optInt("blocked"),
                lastPlayedAt = o.optLong("lastPlayedAt"),
            )
        }
    }
}

data class Stations(val current: Station? = null, val saved: List<Station> = emptyList(), val recent: List<Station> = emptyList()) {
    companion object {
        fun from(o: JSONObject?): Stations {
            if (o == null) return Stations()
            return Stations(
                current = Station.from(o.optJSONObject("current")),
                saved = o.optJSONArray("saved").mapObjects(Station::from),
                recent = o.optJSONArray("recent").mapObjects(Station::from),
            )
        }
    }
}

/** A station page: new songs, and whether it had to start over (expired continuation). */
data class StationPage(val station: Station?, val tracks: List<Track>, val recovered: Boolean)

/** What a backup file holds and what importing it would change, before anything is applied. */
data class BackupPreview(
    val madeBy: String,
    val appVersion: String?,
    val exportedAt: String?,
    val favorites: Int,
    val playlists: Int,
    val tracksInPlaylists: Int,
    val artists: Int,
    val history: Int,
    val plugins: Int,
    val summary: JSONObject,
) {
    companion object {
        fun from(o: JSONObject): BackupPreview {
            val i = o.optJSONObject("info") ?: JSONObject()
            return BackupPreview(
                madeBy = i.str("madeBy") ?: "website",
                appVersion = i.str("appVersion"),
                exportedAt = i.str("exportedAt"),
                favorites = i.optInt("favorites"),
                playlists = i.optInt("playlists"),
                tracksInPlaylists = i.optInt("tracksInPlaylists"),
                artists = i.optInt("artists"),
                history = i.optInt("history"),
                plugins = i.optInt("plugins"),
                summary = o.optJSONObject("summary") ?: JSONObject(),
            )
        }
    }
}

/** Exactly what a VRChat send would contain. */
data class WorldPreview(
    val lists: List<Pair<String, Int>>,
    val totalTracks: Int,
    val unsupported: Int,
    val duplicates: Int,
    val overLimit: Int,
    val playlistsLeftOut: Int,
    val bytes: Int,
    val sends: String,
) {
    companion object {
        fun from(o: JSONObject) = WorldPreview(
            lists = o.optJSONArray("playlists").mapObjects { (it.str("name") ?: return@mapObjects null) to it.optInt("tracks") },
            totalTracks = o.optInt("totalTracks"),
            unsupported = o.optInt("unsupported"),
            duplicates = o.optInt("duplicates"),
            overLimit = o.optInt("overLimit"),
            playlistsLeftOut = o.optInt("playlistsLeftOut"),
            bytes = o.optInt("bytes"),
            sends = o.str("sends") ?: "",
        )
    }
}

data class RequestRecord(val at: Long, val method: String, val route: String, val status: Int, val ms: Long, val cached: Boolean, val retries: Int, val error: String?, val requestId: String?)

data class NetworkDiagnostics(
    val requests: Int,
    val failures: Int,
    val cacheHits: Int,
    val retries: Int,
    val medianMs: Long,
    val maxMs: Long,
    val lastError: String?,
    val coreVersion: String,
    val recent: List<RequestRecord>,
) {
    companion object {
        fun from(o: JSONObject): NetworkDiagnostics {
            val s = o.optJSONObject("summary") ?: JSONObject()
            return NetworkDiagnostics(
                requests = s.optInt("requests"),
                failures = s.optInt("failures"),
                cacheHits = s.optInt("cacheHits"),
                retries = s.optInt("retries"),
                medianMs = s.optLong("medianMs"),
                maxMs = s.optLong("maxMs"),
                lastError = s.str("lastError"),
                coreVersion = o.str("coreVersion") ?: "",
                recent = o.optJSONArray("requests").mapObjects {
                    RequestRecord(it.optLong("at"), it.str("method") ?: "GET", it.str("route") ?: "", it.optInt("status"), it.optLong("ms"), it.optBoolean("cached"), it.optInt("retries"), it.str("error"), it.str("requestId"))
                },
            )
        }
    }
}

internal fun intArray(values: List<Int>) = JSONArray().apply { values.forEach { put(it) } }
