package dev.foulfoxhacks.betteryts.music.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CoreException(message: String) : Exception(message)

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
        if (reply.optBoolean("ok")) reply.opt("data") else throw CoreException(reply.optString("error", "Something went wrong"))
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
    suspend fun played(track: Track) = libraryOp("played", args("track" to track.toJson())).first
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
    suspend fun spotifyRead(link: String): SpotifyList {
        val o = obj("spotify.read", args("link" to link))
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
        val theme: String = "ember",
        val themes: List<Pair<String, String>> = emptyList(),
        val plugins: List<PluginInfo> = emptyList(),
        val categories: List<Category> = emptyList(),
        val permissionText: Map<String, String> = emptyMap(),
        /** Never shown: artists/channels (name, @handle or ID) and songs with these words. */
        val hiddenChannels: List<String> = emptyList(),
        val hiddenWords: List<String> = emptyList(),
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
        return Settings(o.str("theme") ?: "ember", themes, plugins, categories, permissionText, strings("hiddenChannels"), strings("hiddenWords"))
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

    /** Merges a backup (from the site or the app); returns a summary and the new settings. */
    suspend fun backupImport(text: String): Pair<JSONObject, Settings> {
        val o = obj("backup.import", args("text" to text))
        return (o.optJSONObject("summary") ?: JSONObject()) to settingsFrom(o.optJSONObject("settings") ?: JSONObject())
    }

    /** A QR code as rows of "0"/"1" (core/src/qr.rs, same encoder as the website). */
    suspend fun qr(text: String): List<String> {
        val a = obj("qr", args("text" to text)).optJSONArray("rows") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    suspend fun setHidden(channels: List<String>, words: List<String>) =
        settingsFrom(obj("settings.hidden", args("channels" to org.json.JSONArray(channels), "words" to org.json.JSONArray(words))))
    suspend fun toggleHiddenArtist(name: String, id: String?) = settingsFrom(obj("settings.hideArtist", args("name" to name, "id" to id)))

    data class Segment(val start: Float, val end: Float)

    suspend fun segments(id: String): List<Segment> {
        val a = request("plugin.segments", args("id" to id)) as? org.json.JSONArray
        return a.mapObjects { Segment(it.optDouble("start").toFloat(), it.optDouble("end").toFloat()) }
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
