package dev.foulfoxhacks.betteryts.music.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A core error. [code] is stable and machine-readable (core/src/error.rs): NETWORK, TIMEOUT,
 * RATE_LIMITED, UPSTREAM, NOT_FOUND, REJECTED, BAD_RESPONSE, TOO_LARGE, INVALID_ARGUMENT, ERROR.
 */
class CoreException(
    message: String,
    val code: String = "ERROR",
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
) : Exception(message) {
    val offline get() = code == "NETWORK" || code == "TIMEOUT"
}

/**
 * The Rust core (libbetteryts_core.so). One JNI call, JSON in and out; see core/src/bridge.rs
 * for the methods. Every call runs on the IO dispatcher: some of them hit the network.
 */
object Core {
    init {
        System.loadLibrary("betteryts_core")
    }

    @JvmStatic
    private external fun call(method: String, args: String): String

    private suspend fun request(method: String, args: JSONObject = JSONObject()): Any? = withContext(Dispatchers.IO) {
        val reply = JSONObject(call(method, args.toString()))
        if (reply.optBoolean("ok")) reply.opt("data")
        else throw CoreException(
            reply.optString("error", "Something went wrong"),
            reply.optString("code", "ERROR"),
            reply.optBoolean("retryable"),
            if (reply.has("retryAfter")) reply.optLong("retryAfter") else null,
        )
    }

    private suspend fun obj(method: String, args: JSONObject = JSONObject()): JSONObject =
        request(method, args) as? JSONObject ?: JSONObject()

    private fun args(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

    suspend fun init(dataDir: String, region: String) {
        request("init", args("dataDir" to dataDir, "region" to region))
    }

    // ---- browse ----
    suspend fun search(query: String, filter: String, page: String? = null) = Page.from(obj("search", args("query" to query, "filter" to filter, "page" to page)))

    suspend fun suggest(query: String): List<String> {
        val a = request("suggest", args("query" to query)) as? org.json.JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    data class RadioPage(val tracks: List<Track>, val next: String?)

    /** YouTube Music's song radio; pass `next` back for more. */
    suspend fun radio(id: String, next: String? = null): RadioPage {
        val o = obj("radio", args("id" to id, "next" to next))
        return RadioPage(Track.list(o.optJSONArray("tracks")), o.str("next"))
    }

    suspend fun video(id: String) = Track.from(obj("video", args("id" to id))) ?: throw CoreException("Couldn't load that song")
    suspend fun playlist(id: String, page: String? = null) = CollectionPage.from(obj("playlist", args("id" to id, "page" to page)))
    suspend fun artist(id: String, page: String? = null) = ArtistPage.from(obj("artist", args("id" to id, "page" to page)))

    suspend fun feed(): Pair<List<Track>, Int> {
        val o = obj("feed")
        return Track.list(o.optJSONArray("tracks")) to o.optInt("failed")
    }

    suspend fun resolveLink(text: String): Link? = Link.from(request("resolveLink", args("text" to text)) as? JSONObject)

    // ---- queue ----
    private suspend fun queue(method: String, args: JSONObject = JSONObject()) = QueueState.from(obj("queue.$method", args))

    suspend fun queueGet() = queue("get")
    suspend fun queueSet(tracks: List<Track>, start: Int, shuffle: Boolean) = queue("set", args("tracks" to Track.array(tracks), "start" to start, "shuffle" to shuffle))
    suspend fun queueNext(user: Boolean) = queue("next", args("user" to user))
    suspend fun queuePrevious() = queue("previous")
    suspend fun queueJump(position: Int) = queue("jump", args("position" to position))
    suspend fun queueAdd(track: Track) = queue("add", args("track" to track.toJson()))
    suspend fun queueAddAll(tracks: List<Track>) = queue("addAll", args("tracks" to Track.array(tracks)))
    suspend fun queuePlayNext(track: Track) = queue("playNext", args("track" to track.toJson()))
    suspend fun queueRemove(position: Int) = queue("remove", args("position" to position))
    suspend fun queueMove(from: Int, to: Int) = queue("move", args("from" to from, "to" to to))
    suspend fun queueShuffle() = queue("shuffle")
    suspend fun queueRepeat() = queue("repeat")
    suspend fun queueClear() = queue("clear")

    // ---- library ----
    suspend fun library() = Library.from(obj("library.get"))

    /** Library changes return the updated library plus the operation's own result. */
    private suspend fun libraryOp(method: String, args: JSONObject = JSONObject()): Pair<Library, Any?> {
        val o = obj("library.$method", args)
        return Library.from(o.optJSONObject("library") ?: JSONObject()) to o.opt("result")
    }

    suspend fun toggleFavorite(track: Track) = libraryOp("favorite", args("track" to track.toJson()))
    /** Counts a play. [hour] and [day] are the phone's local hour and epoch day, for insights. */
    suspend fun played(track: Track, hour: Int, day: Long) = libraryOp("played", args("track" to track.toJson(), "hour" to hour, "day" to day)).first

    /** Time actually listened to a song, reported when it stops. */
    suspend fun listened(id: String, seconds: Long) {
        request("library.listened", args("id" to id, "seconds" to seconds))
    }

    suspend fun removeHistory(id: String) = libraryOp("removeHistory", args("id" to id)).first
    suspend fun clearStats() = libraryOp("clearStats").first
    suspend fun addSearch(query: String) = libraryOp("addSearch", args("query" to query)).first
    suspend fun removeSearch(query: String) = libraryOp("removeSearch", args("query" to query)).first
    suspend fun clearSearches() = libraryOp("clearSearches").first

    /** Pins or unpins "playlist:<id>", "artist:<id>", "station:<id>" or "smart:<id>"; returns (library, pinned now). */
    suspend fun pin(key: String) = libraryOp("pin", args("key" to key))
    suspend fun movePlaylistTrack(id: String, from: Int, to: Int) = libraryOp("movePlaylistTrack", args("id" to id, "from" to from, "to" to to)).first
    suspend fun removeManyFromPlaylist(id: String, indexes: List<Int>) = libraryOp("removeManyFromPlaylist", args("id" to id, "indexes" to intArray(indexes)))
    suspend fun addManyToPlaylist(id: String, tracks: List<Track>) = libraryOp("addManyToPlaylist", args("id" to id, "tracks" to Track.array(tracks)))
    suspend fun dedupePlaylist(id: String) = libraryOp("dedupePlaylist", args("id" to id))
    suspend fun mergePlaylists(target: String, source: String, deleteSource: Boolean) = libraryOp("mergePlaylists", args("target" to target, "source" to source, "deleteSource" to deleteSource))
    suspend fun duplicatePlaylist(id: String) = libraryOp("duplicatePlaylist", args("id" to id))

    // ---- smart playlists and insights (core/src/smart.rs) ----

    suspend fun smartList(): List<SmartPlaylist> = (request("smart.list") as? org.json.JSONArray).mapObjects(SmartPlaylist::from)

    suspend fun smartTracks(id: String): List<Track> = Track.list(obj("smart.tracks", args("id" to id)).optJSONArray("tracks"))

    /** (how many songs match, the first few, the rule in words). */
    suspend fun smartPreview(rule: SmartRule): Triple<Int, List<Track>, String> {
        val o = obj("smart.preview", args("rule" to rule.toJson()))
        return Triple(o.optInt("count"), Track.list(o.optJSONArray("tracks")), o.str("description") ?: "")
    }

    suspend fun smartSave(name: String, rule: SmartRule): Pair<Library, String?> {
        val o = obj("smart.save", args("name" to name, "rule" to rule.toJson()))
        return Library.from(o.optJSONObject("library") ?: JSONObject()) to o.optJSONObject("result")?.str("id")
    }

    suspend fun smartDelete(id: String): Library = Library.from(obj("smart.delete", args("id" to id)).optJSONObject("library") ?: JSONObject())

    suspend fun insights(today: Long) = Insights.from(obj("insights", args("today" to today)))

    // ---- stations (core/src/stations.rs) ----

    private fun stationPage(o: JSONObject) = StationPage(Station.from(o.optJSONObject("station")), Track.list(o.optJSONArray("tracks")), o.optBoolean("recovered"))

    /** Starts a station from a song, artist, YouTube playlist or one of your playlists ([tracks]). */
    suspend fun stationStart(kind: String, id: String, title: String, art: String?, mix: String? = null, tracks: List<Track>? = null) =
        stationPage(obj("station.start", args("kind" to kind, "id" to id, "title" to title, "art" to art, "mix" to mix, "tracks" to tracks?.let { Track.array(it) })))

    suspend fun stationResume(id: String, tracks: List<Track>? = null) = stationPage(obj("station.resume", args("id" to id, "tracks" to tracks?.let { Track.array(it) })))
    suspend fun stationMore() = stationPage(obj("station.more"))

    /** [kind]: skip, played, notForStation, moreLikeThis. */
    suspend fun stationFeedback(track: Track, kind: String): Pair<String?, Stations> {
        val o = obj("station.feedback", args("track" to track.toJson(), "kind" to kind))
        return o.str("result") to Stations.from(o.optJSONObject("stations"))
    }

    suspend fun stationTune(mix: String) = Stations.from(obj("station.tune", args("mix" to mix)))
    suspend fun stationSave() = Stations.from(obj("station.save"))
    suspend fun stationDelete(id: String) = Stations.from(obj("station.delete", args("id" to id)))
    suspend fun stationStop() = Stations.from(obj("station.stop"))
    suspend fun stations() = Stations.from(obj("stations.list"))

    // ---- BTLink: encrypted local-network sync (core/src/link) ----

    /** Starts hosting; returns (invite, this device's fingerprint). */
    suspend fun lanHost(name: String): Pair<String, String> {
        val o = obj("lan.host", args("name" to name))
        return (o.str("invite") ?: "") to (o.str("fingerprint") ?: "")
    }

    /** Joins from an invite (raw or a betterytsmusic://link?i=… link); returns this device's fingerprint. */
    suspend fun lanJoin(invite: String, name: String): String = obj("lan.join", args("invite" to invite, "name" to name)).str("fingerprint") ?: ""

    suspend fun lanSend(command: JSONObject, leadMs: Int) {
        request("lan.send", args("command" to command, "leadMs" to leadMs))
    }

    suspend fun lanLock(locked: Boolean) {
        request("lan.lock", args("locked" to locked))
    }

    suspend fun lanPoll(): JSONObject = obj("lan.poll")
    suspend fun lanLeave() {
        request("lan.leave")
    }

    // ---- audio analysis (songs on the device) ----

    /** Tempo and key of a device file; [path] may be /proc/self/fd/N for a content URI. Returns "120 BPM · A minor (8A)". */
    suspend fun analyzeFile(id: String, path: String): String {
        val o = obj("analysis.file", args("id" to id, "path" to path))
        val bpm = if (o.has("bpm") && !o.isNull("bpm")) "${o.optDouble("bpm").toInt()} BPM" else null
        val key = o.str("key")?.let { k -> o.str("camelot")?.let { "$k ($it)" } ?: k }
        return listOfNotNull(bpm, key).joinToString(" · ")
    }

    suspend fun analyzedCount(): Int = (request("analysis.count") as? Number)?.toInt() ?: 0

    // ---- diagnostics (core/src/diag.rs) ----

    suspend fun diagnostics() = NetworkDiagnostics.from(obj("diagnostics.get"))
    suspend fun clearDiagnostics() {
        request("diagnostics.clear")
    }
    suspend fun clearHistory() = libraryOp("clearHistory").first
    suspend fun createPlaylist(name: String, tracks: List<Track>) = libraryOp("createPlaylist", args("name" to name, "tracks" to Track.array(tracks)))
    suspend fun renamePlaylist(id: String, name: String) = libraryOp("renamePlaylist", args("id" to id, "name" to name))
    suspend fun deletePlaylist(id: String) = libraryOp("deletePlaylist", args("id" to id)).first
    suspend fun addToPlaylist(id: String, track: Track) = libraryOp("addToPlaylist", args("id" to id, "track" to track.toJson()))
    suspend fun removeFromPlaylist(id: String, index: Int) = libraryOp("removeFromPlaylist", args("id" to id, "index" to index)).first
    suspend fun toggleFollow(artist: Artist) = libraryOp("follow", args("artist" to artist.toJson()))

    // ---- Spotify import (core/src/spotify.rs) ----

    data class SpotifyTrack(val title: String, val artists: List<String>, val durationMs: Long?) {
        val label get() = "${artists.joinToString(", ").ifBlank { "Unknown" }} – $title"
        fun toJson() = JSONObject().apply {
            put("title", title)
            put("artists", org.json.JSONArray(artists))
            if (durationMs != null) put("durationMs", durationMs)
        }
    }

    data class SpotifyList(val name: String, val owner: String?, val tracks: List<SpotifyTrack>, val mayBeTruncated: Boolean)

    data class SpotifyMatch(val track: Track?, val confidence: String, val videoTitle: String?)

    /** A public Spotify playlist or album, read through the BetterYTS Worker. */
    suspend fun spotifyRead(link: String): SpotifyList = spotifyListFrom(obj("spotify.read", args("link" to link)))

    // ---- Sign in with Spotify (core/src/spotify_account.rs) ----

    data class SpotifyRemoteList(val id: String, val name: String, val count: Int)

    suspend fun spotifyClientId(): String = obj("spotify.clientId").optString("clientId")

    suspend fun spotifyExchange(clientId: String, redirectUri: String, code: String, verifier: String): String =
        obj("spotify.exchange", args("clientId" to clientId, "redirectUri" to redirectUri, "code" to code, "verifier" to verifier)).optString("token")

    suspend fun spotifyPlaylists(token: String): List<SpotifyRemoteList> {
        val a = request("spotify.playlists", args("token" to token)) as? org.json.JSONArray
        return a.mapObjects { SpotifyRemoteList(it.str("id") ?: return@mapObjects null, it.str("name") ?: "Playlist", it.optInt("count")) }
    }

    suspend fun spotifyTracks(token: String, id: String, name: String): SpotifyList = spotifyListFrom(obj("spotify.tracks", args("token" to token, "id" to id, "name" to name)))

    private fun spotifyListFrom(o: JSONObject): SpotifyList {
        val a = o.optJSONArray("tracks") ?: org.json.JSONArray()
        val tracks = (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val artists = t.optJSONArray("artists")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) } } ?: emptyList()
            t.str("title")?.let { SpotifyTrack(it, artists, t.optLong("durationMs").takeIf { ms -> ms > 0 }) }
        }
        return SpotifyList(o.str("name") ?: "Spotify playlist", o.str("owner"), tracks, o.optBoolean("mayBeTruncated"))
    }

    /** The YouTube video for one Spotify song (named as Spotify names it), or no track. */
    suspend fun spotifyMatch(track: SpotifyTrack): SpotifyMatch {
        val o = obj("spotify.match", args("track" to track.toJson()))
        return SpotifyMatch(o.optJSONObject("track")?.let(Track::from), o.str("confidence") ?: "none", o.str("videoTitle"))
    }

    // ---- settings and plugins (core/src/plugins.rs) ----

    /**
     * [enabled] is the plugin's own switch; [active] means it runs (its category is on too).
     * Community plugins also carry their manifest: permissions, sandboxed code, palette.
     */
    data class PluginInfo(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val enabled: Boolean,
        val active: Boolean = enabled,
        val community: Boolean = false,
        val author: String = "",
        val version: String = "",
        val permissions: List<String> = emptyList(),
        val code: String = "",
        val palette: Map<String, String> = emptyMap(),
    )

    data class Category(val id: String, val name: String, val description: String, val community: Boolean, val enabled: Boolean)

    data class Settings(
        val theme: String = "betteryts",
        val themes: List<Pair<String, String>> = emptyList(),
        val plugins: List<PluginInfo> = emptyList(),
        val categories: List<Category> = emptyList(),
        val permissionText: Map<String, String> = emptyMap(),
        /** Never shown: artists/channels (name, @handle or ID) and songs with these words. */
        val hiddenChannels: List<String> = emptyList(),
        val hiddenWords: List<String> = emptyList(),
        val sponsorCategories: List<String> = listOf("music_offtopic"),
        val sponsorCatalog: List<Pair<String, String>> = emptyList(),
        val scrobbling: Boolean = false,
    ) {
        private fun compact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        fun hidesArtist(name: String, id: String?) = hiddenChannels.any { c -> compact(c).let { it.isNotEmpty() && (it == compact(name) || (id != null && it == compact(id))) } }
        fun on(id: String) = plugins.any { it.id == id && it.active }
        val activeCommunity get() = plugins.filter { it.community && it.active }
    }

    private fun settingsFrom(o: JSONObject): Settings {
        val themes = o.optJSONArray("themes").mapObjects { (it.str("id") ?: return@mapObjects null) to (it.str("name") ?: "") }
        val plugins = o.optJSONArray("plugins").mapObjects {
            val enabled = it.optBoolean("enabled")
            val perms = it.optJSONArray("permissions")
            val pal = it.optJSONObject("palette")
            PluginInfo(
                id = it.str("id") ?: return@mapObjects null,
                name = it.str("name") ?: "",
                description = it.str("description") ?: "",
                category = it.str("category") ?: "",
                enabled = enabled,
                active = if (it.has("active")) it.optBoolean("active") else enabled,
                community = it.optBoolean("community"),
                author = it.str("author") ?: "",
                version = it.str("version") ?: "",
                permissions = perms?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList(),
                code = it.str("code") ?: "",
                palette = pal?.let { m -> m.keys().asSequence().associateWith { k -> m.optString(k) } } ?: emptyMap(),
            )
        }
        val categories = o.optJSONArray("categories").mapObjects {
            Category(it.str("id") ?: return@mapObjects null, it.str("name") ?: "", it.str("description") ?: "", it.optBoolean("community"), it.optBoolean("enabled", true))
        }
        val permissionText = o.optJSONArray("permissions").mapObjects { (it.str("id") ?: return@mapObjects null) to (it.str("text") ?: "") }.toMap()
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return Settings(
            o.str("theme") ?: "betteryts", themes, plugins, categories, permissionText, strings("hiddenChannels"), strings("hiddenWords"),
            sponsorCategories = strings("sponsorCategories").ifEmpty { listOf("music_offtopic") },
            sponsorCatalog = o.optJSONArray("sponsorCatalog").mapObjects { (it.str("id") ?: return@mapObjects null) to (it.str("name") ?: "") },
            scrobbling = o.optBoolean("scrobbling"),
        )
    }

    suspend fun settings() = settingsFrom(obj("settings.get"))
    suspend fun setTheme(id: String) = settingsFrom(obj("settings.theme", args("theme" to id)))
    suspend fun setPlugin(id: String, enabled: Boolean) = settingsFrom(obj("plugins.set", args("id" to id, "enabled" to enabled)))
    suspend fun setCategory(id: String, enabled: Boolean) = settingsFrom(obj("plugins.category", args("id" to id, "enabled" to enabled)))

    /** Installs a community plugin from its manifest (JSON text); throws [CoreException] with the reason if refused. */
    suspend fun installPlugin(manifest: String) = settingsFrom(obj("plugins.install", args("manifest" to manifest)))
    suspend fun removePlugin(id: String) = settingsFrom(obj("plugins.remove", args("id" to id)))
    /** The library and settings as a backup file (same format as the website's Settings › Backup). */
    suspend fun backupExport(appVersion: String): String = obj("backup.export", args("appVersion" to appVersion)).optString("text")

    /** Reads a backup and says what importing it would change, without changing anything. */
    suspend fun backupPreview(text: String, replace: Boolean) = BackupPreview.from(obj("backup.preview", args("text" to text, "mode" to if (replace) "replace" else "merge")))

    /** Imports a backup (from the site or the app): merges, or replaces the library; returns a summary and the new settings. */
    suspend fun backupImport(text: String, replace: Boolean = false): Pair<JSONObject, Settings> {
        val o = obj("backup.import", args("text" to text, "mode" to if (replace) "replace" else "merge"))
        return (o.optJSONObject("summary") ?: JSONObject()) to settingsFrom(o.optJSONObject("settings") ?: JSONObject())
    }

    data class WorldCode(val code: String, val service: String, val capabilities: List<String>, val expiresIn: Long?)

    /** Checks an activation code shown in a VRChat world. */
    suspend fun inworldCheck(code: String): WorldCode {
        val o = obj("inworld.check", args("code" to code))
        val caps = o.optJSONArray("capabilities")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return WorldCode(o.str("code") ?: code, o.str("service") ?: "youtube", caps, if (o.has("expiresIn")) o.optLong("expiresIn") else null)
    }

    /** What a send would contain (counts, songs left out, size), before anything is sent. */
    suspend fun inworldPreview(playlistIds: List<String>, liked: Boolean, queue: Boolean) =
        WorldPreview.from(obj("inworld.preview", args("playlistIds" to org.json.JSONArray(playlistIds), "liked" to liked, "queue" to queue)))

    /** Sends library playlists (by ID, plus liked songs and the queue) to that world: (playlists, songs) sent, and the library with the send remembered. */
    suspend fun inworldSend(code: String, service: String, playlistIds: List<String>, liked: Boolean, save: Boolean, queue: Boolean = false): Triple<Int, Int, Library?> {
        val o = obj("inworld.send", args("code" to code, "service" to service, "playlistIds" to org.json.JSONArray(playlistIds), "liked" to liked, "save" to save, "queue" to queue))
        return Triple(o.optInt("playlists"), o.optInt("tracks"), o.optJSONObject("library")?.let(Library::from))
    }

    suspend fun inworldForget(): Library = Library.from(obj("inworld.forget").optJSONObject("library") ?: JSONObject())

    /** A QR code as rows of "0"/"1" (core/src/qr.rs, same encoder as the website). */
    suspend fun qr(text: String): List<String> {
        val a = obj("qr", args("text" to text)).optJSONArray("rows") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    suspend fun setHidden(channels: List<String>, words: List<String>) =
        settingsFrom(obj("settings.hidden", args("channels" to org.json.JSONArray(channels), "words" to org.json.JSONArray(words))))
    suspend fun toggleHiddenArtist(name: String, id: String?) = settingsFrom(obj("settings.hideArtist", args("name" to name, "id" to id)))

    data class Segment(val start: Float, val end: Float, val category: String = "music_offtopic")

    suspend fun segments(id: String): List<Segment> {
        val a = request("plugin.segments", args("id" to id)) as? org.json.JSONArray
        return a.mapObjects { Segment(it.optDouble("start").toFloat(), it.optDouble("end").toFloat(), it.str("category") ?: "music_offtopic") }
    }

    suspend fun setSponsorCategories(ids: List<String>) = settingsFrom(obj("settings.sponsorCategories", args("categories" to org.json.JSONArray(ids))))

    // ---- dislikes, album actions, sharing, scrobbling ----

    /** Returns (library, disliked now). */
    suspend fun toggleDislike(track: Track) = libraryOp("dislike", args("track" to track.toJson()))
    suspend fun likeMany(tracks: List<Track>) = libraryOp("likeMany", args("tracks" to Track.array(tracks)))

    /** A share link without tracking parameters, or null. */
    suspend fun cleanShare(text: String): String? = request("share.clean", args("text" to text)) as? String

    /** Checks and stores a ListenBrainz token; returns (user name, settings). */
    suspend fun scrobbleConnect(token: String): Pair<String, Settings> {
        val o = obj("scrobble.connect", args("token" to token))
        return (o.str("user") ?: "") to settingsFrom(o.optJSONObject("settings") ?: JSONObject())
    }

    suspend fun scrobbleDisconnect() = settingsFrom(obj("scrobble.disconnect"))
    suspend fun scrobbleNowPlaying(track: Track) {
        request("scrobble.nowPlaying", args("track" to track.toJson()))
    }

    /** Submits a finished listen if it passes the scrobble rule (half the song or 4 minutes). */
    suspend fun scrobbleListen(track: Track, startedAtMs: Long, listenedSeconds: Long) {
        request("scrobble.listen", args("track" to track.toJson(), "startedAt" to startedAtMs, "listened" to listenedSeconds))
    }

    /** (likes, dislikes), or null when there's no vote data. */
    suspend fun dislikes(id: String): Pair<Long, Long>? {
        val o = request("plugin.dislikes", args("id" to id)) as? JSONObject ?: return null
        return if (o.has("likes") && o.has("dislikes")) o.optLong("likes") to o.optLong("dislikes") else null
    }

    data class LyricLine(val time: Float, val text: String)
    data class Lyrics(val synced: List<LyricLine>, val plain: String?, val instrumental: Boolean)

    suspend fun lyrics(track: Track): Lyrics? {
        val o = request("plugin.lyrics", args("track" to track.toJson())) as? JSONObject ?: return null
        return Lyrics(
            synced = o.optJSONArray("synced").mapObjects { LyricLine(it.optDouble("time").toFloat(), it.optString("text")) },
            plain = o.str("plain"),
            instrumental = o.optBoolean("instrumental"),
        )
    }

    /** A new watch-party room: (code, hostKey). */
    suspend fun roomsNew(): Pair<String?, String?> {
        val o = obj("rooms.new")
        return o.str("code") to o.str("hostKey")
    }

    suspend fun protvPlaylist(name: String, tracks: List<Track>): String =
        request("plugin.protv", args("name" to name, "tracks" to Track.array(tracks))) as? String ?: ""

    // ---- Google account (optional, read-only YouTube import) ----

    /** The core's error for "this Google token doesn't work any more" (google.rs AUTH_ERROR). */
    const val GOOGLE_AUTH_EXPIRED = "google_auth_expired"

    data class Tokens(val accessToken: String, val expiresIn: Long, val refreshToken: String?)

    private fun tokens(o: JSONObject) = Tokens(o.optString("accessToken"), o.optLong("expiresIn", 3600), o.str("refreshToken"))

    suspend fun googleExchange(code: String, verifier: String) = tokens(obj("google.exchange", args("code" to code, "verifier" to verifier)))
    suspend fun googleRefresh(refreshToken: String) = tokens(obj("google.refresh", args("refreshToken" to refreshToken)))
    suspend fun googleRevoke(token: String) {
        request("google.revoke", args("token" to token))
    }

    suspend fun googleProfile(accessToken: String): String? = request("google.profile", args("accessToken" to accessToken)) as? String

    data class ImportSummary(val playlistsAdded: Int, val playlistsUpdated: Int, val playlistsSkipped: Int, val favoritesAdded: Int, val artistsAdded: Int)

    suspend fun googleImport(accessToken: String, playlists: Boolean, liked: Boolean, subscriptions: Boolean): Pair<Library, ImportSummary> {
        val o = obj("google.import", args("accessToken" to accessToken, "playlists" to playlists, "liked" to liked, "subscriptions" to subscriptions))
        val r = o.optJSONObject("result") ?: JSONObject()
        val summary = ImportSummary(
            playlistsAdded = r.optInt("playlistsAdded"),
            playlistsUpdated = r.optInt("playlistsUpdated"),
            playlistsSkipped = r.optInt("playlistsSkipped"),
            favoritesAdded = r.optInt("favoritesAdded"),
            artistsAdded = r.optInt("artistsAdded"),
        )
        return Library.from(o.optJSONObject("library") ?: JSONObject()) to summary
    }
}
