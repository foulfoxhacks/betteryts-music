package dev.foulfoxhacks.betteryts.music.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import dev.foulfoxhacks.betteryts.music.account.GoogleAccount
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.foulfoxhacks.betteryts.music.core.Artist
import dev.foulfoxhacks.betteryts.music.core.ArtistPage
import dev.foulfoxhacks.betteryts.music.core.Collection
import dev.foulfoxhacks.betteryts.music.core.CollectionPage
import dev.foulfoxhacks.betteryts.music.core.Core
import dev.foulfoxhacks.betteryts.music.core.CoreException
import dev.foulfoxhacks.betteryts.music.core.Item
import dev.foulfoxhacks.betteryts.music.core.Library
import dev.foulfoxhacks.betteryts.music.core.Link
import dev.foulfoxhacks.betteryts.music.core.QueueState
import dev.foulfoxhacks.betteryts.music.core.Repeat
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.together.RoomClient
import dev.foulfoxhacks.betteryts.music.together.RoomState
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.Palettes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/** Bottom bar: Home, Explore, Library (YouTube Music's layout). Search opens from the top bar. */
enum class Tab { Home, Explore, Search, Library }

sealed interface Route {
    data class ArtistRoute(val id: String, val name: String?) : Route
    data class CollectionRoute(val collection: Collection) : Route
    data class PlaylistRoute(val id: String) : Route
    data object SettingsRoute : Route
}

/** A home shelf filled from a search, so Home has music on day one. */
data class Shelf(val title: String, val query: String, val tracks: Load<List<Track>>)

/** Loading state for a page. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T, val loadingMore: Boolean = false) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class AppViewModel(app: Application) : AndroidViewModel(app), PlayerController.Listener {
    // ---- navigation ----
    var tab by mutableStateOf(Tab.Home)
        private set
    val stack = mutableStateListOf<Route>()
    var playerExpanded by mutableStateOf(false)

    /** True while the activity is in picture-in-picture: the app draws only the video then. */
    var inPip by mutableStateOf(false)

    // ---- data ----
    var library by mutableStateOf(Library())
        private set
    var queue by mutableStateOf(QueueState())
        private set
    var message by mutableStateOf<String?>(null)

    /** Dialogs: "Add to playlist" for a track, and "New playlist" (optionally pre-filled). */
    var playlistPickerFor by mutableStateOf<Track?>(null)
    var newPlaylistWith by mutableStateOf<List<Track>?>(null)

    var feed by mutableStateOf<Load<List<Track>>>(Load.Ready(emptyList()))
        private set

    var query by mutableStateOf("")
        private set
    var submittedQuery by mutableStateOf("")
        private set
    var filter by mutableStateOf("all")
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var results by mutableStateOf<Load<List<Item>>?>(null)
        private set
    private var resultsNext: String? = null

    val artistPages = mutableStateMapOf<String, Load<ArtistPage>>()
    val collectionPages = mutableStateMapOf<String, Load<CollectionPage>>()

    private var player: PlayerController? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    /** Playback preferences that don't belong in the Rust core: speed and resume. */
    private val playPrefs = app.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)

    /** Speed for every song (0.5x–2x). YouTube starts each new video at 1x, so the player re-applies it. */
    var speed by mutableFloatStateOf(1f)
        private set
    var resumeEnabled by mutableStateOf(playPrefs.getBoolean("resume", true))
        private set

    /** Resume positions are saved twice a second by the player; only write every few seconds. */
    private var lastProgressAt = 0L
    private var endedId: String? = null

    var settings by mutableStateOf(Core.Settings())
        private set

    private val ready: Job = viewModelScope.launch {
        try {
            Core.init(app.filesDir.absolutePath, Locale.getDefault().country)
            settings = Core.settings()
            Brand.palette = Palettes.byId(settings.theme)
            library = Core.library()
            queue = Core.queueGet()
            restoreQueue()
        } catch (e: CoreException) {
            message = e.message
        }
    }

    init {
        refreshFeed()
    }

    /** Runs after the core is initialised; core errors become a snackbar message. */
    private fun io(block: suspend () -> Unit) = viewModelScope.launch {
        ready.join()
        try {
            block()
        } catch (e: CoreException) {
            message = e.message
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    /** Switching tabs (or tapping the current one) returns to that tab's top level. */
    fun selectTab(t: Tab) {
        tab = t
        stack.clear()
        playerExpanded = false
    }

    /** Back: collapse the player, then pop a page, then go home. Returns false if there's nothing to do. */
    fun back(): Boolean = when {
        playerExpanded -> { playerExpanded = false; true }
        stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
        tab != Tab.Home -> { tab = Tab.Home; true }
        else -> false
    }

    fun openArtist(id: String?, name: String? = null) {
        if (id.isNullOrBlank()) {
            message = "No artist page for this song"
            return
        }
        playerExpanded = false
        stack.add(Route.ArtistRoute(id, name))
        if (artistPages[id] !is Load.Ready) loadArtist(id)
    }

    fun loadArtist(id: String) = io {
        artistPages[id] = Load.Loading
        artistPages[id] = try {
            Load.Ready(Core.artist(id))
        } catch (e: CoreException) {
            Load.Failed(e.message ?: "Couldn't load this artist")
        }
    }

    fun loadMoreArtist(id: String) {
        val cur = artistPages[id] as? Load.Ready ?: return
        val next = cur.value.next ?: return
        if (cur.loadingMore) return
        artistPages[id] = cur.copy(loadingMore = true)
        io {
            try {
                val more = Core.artist(id, next)
                artistPages[id] = Load.Ready(cur.value.copy(tracks = cur.value.tracks + more.tracks, next = more.next))
            } catch (e: CoreException) {
                artistPages[id] = cur
                throw e
            }
        }
    }

    fun openCollection(c: Collection) {
        playerExpanded = false
        stack.add(Route.CollectionRoute(c))
        if (collectionPages[c.id] !is Load.Ready) loadCollection(c)
    }

    fun loadCollection(c: Collection) = io {
        collectionPages[c.id] = Load.Loading
        collectionPages[c.id] = try {
            Load.Ready(Core.playlist(c.id))
        } catch (e: CoreException) {
            Load.Failed(e.message ?: "Couldn't load this playlist")
        }
    }

    fun loadMoreCollection(id: String) {
        val cur = collectionPages[id] as? Load.Ready ?: return
        val next = cur.value.next ?: return
        if (cur.loadingMore) return
        collectionPages[id] = cur.copy(loadingMore = true)
        io {
            try {
                val more = Core.playlist(id, next)
                collectionPages[id] = Load.Ready(cur.value.copy(tracks = cur.value.tracks + more.tracks, next = more.next))
            } catch (e: CoreException) {
                collectionPages[id] = cur
                throw e
            }
        }
    }

    fun openUserPlaylist(id: String) {
        stack.add(Route.PlaylistRoute(id))
    }

    /** A link shared into the app, or opened from music.youtube.com / BetterYTS. */
    fun openLink(text: String) = io {
        when (val link = Core.resolveLink(text)) {
            is Link.Video -> playTracks(listOf(Core.video(link.id)), 0)
            is Link.Playlist -> openCollection(Collection(link.id, "playlist", "Playlist", ""))
            is Link.ArtistLink -> openArtist(link.id)
            is Link.Search -> {
                selectTab(Tab.Search)
                submitSearch(link.query)
            }
            null -> message = "That link isn't a song, playlist or artist"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Home feed
    // ---------------------------------------------------------------------------------------

    fun refreshFeed() = io {
        if (library.followed.isEmpty()) {
            feed = Load.Ready(emptyList())
            return@io
        }
        feed = Load.Loading
        val (tracks, failed) = Core.feed()
        feed = Load.Ready(tracks)
        if (failed > 0) message = "$failed artist${if (failed == 1) "" else "s"} couldn't be loaded"
    }

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    fun onQueryChange(q: String) {
        query = q
        suggestJob?.cancel()
        if (q.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(180)
            ready.join()
            suggestions = try {
                Core.suggest(q)
            } catch (_: CoreException) {
                emptyList()
            }
        }
    }

    fun submitSearch(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        submittedQuery = text
        suggestions = emptyList()
        suggestJob?.cancel()
        runSearch()
    }

    fun chooseFilter(f: String) {
        if (filter == f) return
        filter = f
        if (submittedQuery.isNotEmpty()) runSearch()
    }

    /** From the home screen's mood chips. */
    fun searchFor(q: String, f: String = "songs") {
        selectTab(Tab.Search)
        filter = f
        submitSearch(q)
    }

    private fun runSearch() {
        searchJob?.cancel()
        results = Load.Loading
        searchJob = io {
            try {
                val page = Core.search(submittedQuery, filter)
                resultsNext = page.next
                results = Load.Ready(page.items)
            } catch (e: CoreException) {
                results = Load.Failed(e.message ?: "Search failed")
            }
        }
    }

    fun loadMoreResults() {
        val cur = results as? Load.Ready ?: return
        val next = resultsNext ?: return
        if (cur.loadingMore) return
        results = cur.copy(loadingMore = true)
        resultsNext = null
        io {
            try {
                val page = Core.search(submittedQuery, filter, next)
                resultsNext = page.next
                val seen = cur.value.mapTo(HashSet()) { key(it) }
                results = Load.Ready(cur.value + page.items.filter { key(it) !in seen })
            } catch (e: CoreException) {
                results = cur
                throw e
            }
        }
    }

    private fun key(i: Item) = when (i) {
        is Item.TrackItem -> "t" + i.track.id
        is Item.CollectionItem -> "c" + i.collection.id
        is Item.ArtistItem -> "a" + i.artist.id
    }

    // ---------------------------------------------------------------------------------------
    // Playback (queue in the Rust core, audio/video in the YouTube player)
    // ---------------------------------------------------------------------------------------

    fun attach(p: PlayerController) {
        player = p
        p.listener = this
        speed = playPrefs.getFloat("speed", 1f).takeIf { it in 0.5f..2f } ?: 1f
        p.applySpeed(speed)
        // The activity was recreated with something in the queue: cue it, don't blast it.
        queue.current?.let { p.load(it.id, resumePosition(it), autoplay = false) }
    }

    fun detach(p: PlayerController) {
        if (player === p) player = null
    }

    /** Where a song should start: the saved position for long listens, otherwise the beginning. */
    private fun resumePosition(t: Track): Float {
        if (!resumeEnabled || room != null || t.live) return 0f
        val saved = playPrefs.getFloat("pos:" + t.id, 0f)
        if (saved < 15f) return 0f
        val total = t.durationSeconds?.toFloat() ?: 0f
        // Almost at the end: the user finished it, so play it from the top next time.
        return if (total > 0f && saved >= total - 20f) 0f else saved
    }

    /**
     * Brings the queue back after a cold start. The current song is cued paused at its saved
     * position, so opening the app never starts audio by itself.
     */
    private suspend fun restoreQueue() {
        if (queue.current != null) return
        val saved = playPrefs.getString("queue", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val tracks = Track.list(saved.optJSONArray("tracks"))
        if (tracks.isEmpty()) return
        val position = saved.optInt("position", 0).coerceIn(0, tracks.lastIndex)
        var q = Core.queueSet(tracks, position, shuffle = false)
        // The core cycles repeat, so step it to the saved mode.
        val mode = runCatching { Repeat.valueOf(saved.optString("repeat", "Off")) }.getOrDefault(Repeat.Off)
        repeat(
            when (mode) {
                Repeat.Off -> 0
                Repeat.All -> 1
                Repeat.One -> 2
            }
        ) { q = Core.queueRepeat() }
        // The saved list is already in play order, so only re-shuffle what is still ahead.
        if (saved.optBoolean("shuffle")) q = Core.queueShuffle()
        applyQueue(q)
        val t = q.current ?: return
        if (player?.currentId == null) player?.load(t.id, resumePosition(t), autoplay = false)
    }

    /** Queue changes go to the screen and to disk, so the next launch can pick up here. */
    private fun applyQueue(q: QueueState) {
        queue = q
        saveQueue(q)
        topUpRadio()
    }

    // ---- Endless radio: YouTube Music's song radio, started on purpose or when the queue runs low ----
    /** The song the current radio grew from, and where its next page starts. */
    private var radioSeed: String? = null
    private var radioNext: String? = null
    private var radioLoading = false
    var radioFor by mutableStateOf<String?>(null)
        private set

    fun startRadio(track: Track) {
        if (room != null) {
            message = "Radio isn't available in a room. Add songs to the room queue instead."
            return
        }
        io {
            val page = Core.radio(track.id)
            radioSeed = track.id
            radioNext = page.next
            radioFor = track.title
            val tracks = listOf(track) + page.tracks.filter { it.id != track.id }
            applyQueue(Core.queueSet(tracks, 0, false))
            playCurrent()
            message = "Radio: songs like " + track.title
        }
    }

    /** With 3 or fewer songs left, adds more like what's playing. */
    private fun topUpRadio() {
        val q = queue
        val current = q.current ?: return
        if (!pluginOn("radio") || room != null || radioLoading || q.repeat != Repeat.Off) return
        if (q.tracks.size - 1 - q.position > 3) return
        radioLoading = true
        viewModelScope.launch {
            try {
                // Continue the radio we're on; otherwise grow a new one from the current song.
                val page = if (radioSeed != null && radioNext != null) Core.radio(radioSeed!!, radioNext)
                else Core.radio(current.id).also { radioSeed = current.id; radioFor = current.title }
                radioNext = page.next
                val have = queue.tracks.map { it.id }.toSet()
                val fresh = page.tracks.filter { it.id !in have && !it.live }.distinctBy { it.id }
                if (fresh.isNotEmpty() && room == null) {
                    queue = Core.queueAddAll(fresh).also(::saveQueue)
                    // The queue had already run out: carry on with the first new song.
                    if (endedId == current.id && queue.current?.id == current.id && !(player?.playing ?: false)) {
                        applyQueue(Core.queueNext(user = false))
                        playCurrent()
                    }
                }
            } catch (_: CoreException) {
                // No radio this time; the queue just ends as before.
            } finally {
                radioLoading = false
            }
        }
    }

    private fun saveQueue(q: QueueState) {
        if (q.tracks.isEmpty()) {
            playPrefs.edit().remove("queue").apply()
            return
        }
        val saved = JSONObject()
            .put("tracks", Track.array(q.tracks))
            .put("position", q.position)
            .put("shuffle", q.shuffle)
            .put("repeat", q.repeat.name)
        playPrefs.edit().putString("queue", saved.toString()).apply()
    }

    /** Saved speed applies to every song, now and after a restart. */
    fun chooseSpeed(rate: Float) {
        val v = rate.coerceIn(0.5f, 2f)
        speed = v
        player?.applySpeed(v)
        playPrefs.edit().putFloat("speed", v).apply()
    }

    fun setResume(on: Boolean) {
        resumeEnabled = on
        playPrefs.edit().putBoolean("resume", on).apply()
        message = if (on) "Songs will resume where you left off" else "Songs always start from the beginning"
    }

    private fun playCurrent() {
        val t = queue.current ?: return
        endedId = null
        player?.load(t.id, resumePosition(t))
        onTrackChanged(t)
    }

    fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (room != null) {
            val t = tracks[start.coerceIn(0, tracks.lastIndex)]
            if (roomSend("load", "video" to room!!.video(t.id, t.title))) message = "Playing for everyone: ${t.title}"
            return
        }
        io {
            // A new list you chose; when it runs out, radio grows from its last song.
            radioSeed = null
            radioNext = null
            radioFor = null
            applyQueue(Core.queueSet(tracks, start, shuffle))
            playCurrent()
        }
    }

    fun playCollection(c: Collection) {
        if (c.isMix && c.firstVideoId != null) {
            // Mixes are generated per listener and can't be listed; start the radio from its first song.
            io {
                playTracks(listOf(Core.video(c.firstVideoId)), 0)
            }
        } else {
            openCollection(c)
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (room != null) {
            roomSend(if (roomState?.playing == true) "pause" else "play", "position" to p.position)
            return
        }
        if (p.currentId == null) playCurrent() else p.toggle()
    }

    fun next() {
        if (room != null) {
            roomSend("next")
            return
        }
        nextLocal()
    }

    private fun nextLocal() = io {
        val q = Core.queueNext(user = true)
        applyQueue(q)
        if (!q.ended) playCurrent()
    }

    fun previous() {
        val p = player
        if (room != null) {
            roomSend("seek", "position" to 0)
            return
        }
        if (p != null && p.position > 4f) {
            p.seek(0f)
            return
        }
        io {
            applyQueue(Core.queuePrevious())
            playCurrent()
        }
    }

    fun jumpTo(position: Int) {
        if (room != null) {
            val t = roomQueue.getOrNull(position) ?: return
            if (roomSend("load", "video" to room!!.video(t.id, t.title))) roomSend("queue_remove", "index" to position)
            return
        }
        jumpLocal(position)
    }

    private fun jumpLocal(position: Int) = io {
        applyQueue(Core.queueJump(position))
        playCurrent()
    }

    fun playNext(track: Track) {
        if (room != null) return addToQueue(track)
        playNextLocal(track)
    }

    private fun playNextLocal(track: Track) = io {
        val wasEmpty = queue.current == null
        applyQueue(Core.queuePlayNext(track))
        if (wasEmpty) playCurrent() else message = "Playing next: ${track.title}"
    }

    fun addToQueue(track: Track) {
        if (room != null) {
            if (roomSend("queue_add", "video" to room!!.video(track.id, track.title))) message = "Added to the room's queue"
            return
        }
        addLocal(track)
    }

    private fun addLocal(track: Track) = io {
        val wasEmpty = queue.current == null
        applyQueue(Core.queueAdd(track))
        if (wasEmpty) playCurrent() else message = "Added to queue"
    }

    fun addAllToQueue(tracks: List<Track>) {
        if (room != null) {
            if (canControlRoom()) tracks.take(50).forEach { t -> room!!.send("queue_add", "video" to room!!.video(t.id, t.title)) }
            message = "Added ${minOf(tracks.size, 50)} songs to the room's queue"
            return
        }
        addAllLocal(tracks)
    }

    private fun addAllLocal(tracks: List<Track>) = io {
        val wasEmpty = queue.current == null
        applyQueue(Core.queueAddAll(tracks))
        if (wasEmpty) playCurrent() else message = "Added ${tracks.size} songs to the queue"
    }

    fun removeFromQueue(position: Int) {
        if (room != null) {
            roomSend("queue_remove", "index" to position)
            return
        }
        removeLocal(position)
    }

    private fun removeLocal(position: Int) = io {
        val before = queue.current?.id
        applyQueue(Core.queueRemove(position))
        if (queue.current?.id != before) {
            if (queue.current == null) player?.pause() else playCurrent()
        }
    }

    private fun notInRoom(what: String): Boolean {
        if (room != null) message = "$what isn't available while listening together"
        return room == null
    }

    fun moveInQueue(from: Int, to: Int) {
        if (notInRoom("Reordering")) io { applyQueue(Core.queueMove(from, to)) }
    }

    fun toggleShuffle() {
        if (notInRoom("Shuffle")) io { applyQueue(Core.queueShuffle()) }
    }

    fun cycleRepeat() {
        if (notInRoom("Repeat")) io { applyQueue(Core.queueRepeat()) }
    }

    fun seek(seconds: Float) {
        if (room != null) {
            roomSend("seek", "position" to seconds)
        } else {
            if (seconds < 1f) queue.current?.let { playPrefs.edit().remove("pos:" + it.id).apply() }
            player?.seek(seconds)
        }
    }

    override fun onStartedPlaying(id: String) {
        val t = queue.current?.takeIf { it.id == id } ?: return
        io { library = Core.played(t) }
    }

    override fun onEnded(id: String) {
        if (room != null) {
            // Every member reports the end; the server only advances once.
            room?.send("ended", "id" to id)
            return
        }
        endedId = id
        playPrefs.edit().remove("pos:" + id).apply()
        if (queue.current?.id != id) return
        if (sleepAtEndOfSong) {
            cancelSleep()
            message = "Sleep timer: goodnight"
            return
        }
        io {
            val q = Core.queueNext(user = false)
            applyQueue(q)
            if (!q.ended) playCurrent()
        }
    }

    override fun onError(id: String?, code: Int) {
        message = PlayerController.describe(code)
        // In a room, one member's player failing mustn't skip the song for everyone.
        if (room != null) {
            message = "This video can't play here (error $code). The room keeps going."
            return
        }
        if (id == null || queue.current?.id != id) return
        viewModelScope.launch {
            delay(1500)
            if (queue.current?.id == id) next()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------------------------

    fun toggleFavorite(track: Track) = io {
        val (lib, on) = Core.toggleFavorite(track)
        library = lib
        message = if (on == true) "Added to favorites" else "Removed from favorites"
    }

    fun toggleFollow(artist: Artist) = io {
        val (lib, on) = Core.toggleFollow(artist)
        library = lib
        message = if (on == true) "Following ${artist.name}" else "Unfollowed ${artist.name}"
        refreshFeed()
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), open: Boolean = false) = io {
        val (lib, result) = Core.createPlaylist(name, tracks)
        library = lib
        val id = (result as? org.json.JSONObject)?.optString("id")
        if (id.isNullOrEmpty()) {
            message = "You've reached the playlist limit"
        } else {
            message = "Playlist created"
            if (open) openUserPlaylist(id)
        }
    }

    fun addToPlaylist(id: String, track: Track) = io {
        val (lib, ok) = Core.addToPlaylist(id, track)
        library = lib
        message = if (ok == true) "Added to ${lib.playlist(id)?.name ?: "playlist"}" else "Already in that playlist"
    }

    fun removeFromPlaylist(id: String, index: Int) = io { library = Core.removeFromPlaylist(id, index) }
    fun renamePlaylist(id: String, name: String) = io { library = Core.renamePlaylist(id, name).first }

    fun deletePlaylist(id: String) = io {
        library = Core.deletePlaylist(id)
        stack.removeAll { it is Route.PlaylistRoute && it.id == id }
        message = "Playlist deleted"
    }

    fun clearHistory() = io { library = Core.clearHistory() }

    // ---------------------------------------------------------------------------------------
    // Settings, theme and plugins
    // ---------------------------------------------------------------------------------------

    fun openSettings() {
        playerExpanded = false
        if (stack.lastOrNull() != Route.SettingsRoute) stack.add(Route.SettingsRoute)
    }

    fun setTheme(id: String) {
        Brand.palette = Palettes.byId(id) // instant; saved below
        io { settings = Core.setTheme(id) }
    }

    fun setPlugin(id: String, enabled: Boolean) = io {
        settings = Core.setPlugin(id, enabled)
        val t = queue.current
        if (enabled && t != null) onTrackChanged(t)
        if (id == "sleep" && !enabled) cancelSleep()
    }

    fun pluginOn(id: String) = settings.on(id)

    /** Per-song plugin data, keyed by video ID. */
    private val segmentsFor = HashMap<String, List<Core.Segment>>()
    private var skippedUntil = 0f
    var dislikes by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    val lyrics = mutableStateMapOf<String, Load<Core.Lyrics?>>()

    private fun onTrackChanged(t: Track) {
        skippedUntil = 0f
        dislikes = null
        if (pluginOn("sponsorblock") && t.id !in segmentsFor) {
            viewModelScope.launch {
                segmentsFor[t.id] = try { Core.segments(t.id) } catch (_: CoreException) { emptyList() }
            }
        }
        if (pluginOn("dislikes")) {
            viewModelScope.launch {
                val d = try { Core.dislikes(t.id) } catch (_: CoreException) { null }
                if (queue.current?.id == t.id) dislikes = d
            }
        }
    }

    /** SponsorBlock for music: jump over non-music parts; a trailing outro ends the song. */
    override fun onProgress(id: String, seconds: Float, duration: Float) {
        // Remember where playback stopped. Skip finished songs and the first moments of a cue,
        // which report 0 before YouTube knows the real position.
        if (room == null && id == endedId && seconds < 3f) endedId = null
        if (room == null && resumeEnabled && id != endedId && seconds >= 0.5f && duration > 5f && seconds < duration - 3f) {
            val now = System.currentTimeMillis()
            if (now - lastProgressAt > 5_000) {
                lastProgressAt = now
                playPrefs.edit().putFloat("pos:" + id, seconds).apply()
            }
        }
        // In a room the room decides where playback is, so local skipping is off.
        if (room != null || !pluginOn("sponsorblock") || queue.current?.id != id) return
        val seg = segmentsFor[id]?.firstOrNull { seconds >= it.start && seconds < it.end - 0.5f && it.end > skippedUntil } ?: return
        skippedUntil = seg.end
        if (duration > 0 && seg.end >= duration - 1f) {
            message = "Skipped the outro"
            onEnded(id)
        } else {
            player?.seek(seg.end)
            message = if (seg.start < 1f) "Skipped the intro" else "Skipped a non-music part"
        }
    }

    fun loadLyrics(t: Track) {
        val state = lyrics[t.id]
        if (state is Load.Ready || state == Load.Loading) return
        lyrics[t.id] = Load.Loading
        viewModelScope.launch {
            ready.join()
            lyrics[t.id] = try {
                Load.Ready(Core.lyrics(t))
            } catch (e: CoreException) {
                Load.Failed(e.message ?: "Couldn't load lyrics")
            }
        }
    }

    // ---- Sleep timer ----
    var sleepAt by mutableStateOf<Long?>(null)
        private set
    var sleepAtEndOfSong by mutableStateOf(false)
        private set
    private var sleepJob: Job? = null

    fun sleepIn(minutes: Int) {
        cancelSleep()
        val at = System.currentTimeMillis() + minutes * 60_000L
        sleepAt = at
        sleepJob = viewModelScope.launch {
            delay(at - System.currentTimeMillis())
            player?.pause()
            sleepAt = null
            message = "Sleep timer: goodnight"
        }
        message = "Music stops in $minutes min"
    }

    fun sleepAfterThisSong() {
        cancelSleep()
        sleepAtEndOfSong = true
        message = "Music stops after this song"
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepAt = null
        sleepAtEndOfSong = false
    }

    // ---- VRChat bridge ----
    /** Link for any VRChat video player: BetterYTS redirects it to the video. */
    fun vrchatLink(t: Track) = "https://yts.foulfoxhacks.dev/v/${t.id}"

    suspend fun protvQueue(): String = Core.protvPlaylist("BetterYTS Music queue", queue.tracks)

    // ---- "Mixed for you" shelves (fetched once per session) ----
    var shelves by mutableStateOf<List<Shelf>>(emptyList())
        private set

    fun retryShelves() = loadShelves()

    private fun loadShelves() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val picks = listOf(
            when (hour) {
                in 5..11 -> "Morning energy" to "upbeat morning music"
                in 12..17 -> "Afternoon focus" to "focus music instrumental"
                in 18..22 -> "Evening chill" to "chill evening music"
                else -> "Late night" to "late night lofi"
            },
            "Lo-fi beats" to "lofi hip hop",
            "Today's hits" to "top hits this week official music video",
        )
        fillShelves(picks, get = { shelves }, set = { shelves = it })
    }

    /** Shelves want songs, not hour-long "Top Hits 2026 ~ Mix" compilations or live streams. */
    private val COMPILATION = Regex("""\b(mix|mixes|playlist|compilation|full album|nonstop|non-stop|top \d+|hits \d{4}|best songs|greatest hits|\d+ hours?)\b""", RegexOption.IGNORE_CASE)

    private fun isSingleSong(t: Track) = !t.live && (t.durationSeconds ?: 0) in 1..600 && !COMPILATION.containsMatchIn(t.title)

    /** Fills shelves from song searches, each one as soon as its search answers. */
    private fun fillShelves(picks: List<Pair<String, String>>, get: () -> List<Shelf>, set: (List<Shelf>) -> Unit, size: Int = 12) {
        set(picks.map { (title, q) -> Shelf(title, q, Load.Loading) })
        picks.forEachIndexed { i, (title, q) ->
            viewModelScope.launch {
                ready.join()
                val result: Load<List<Track>> = try {
                    val first = Core.search(q, "songs")
                    var songs = first.items.filterIsInstance<Item.TrackItem>().map { it.track }.filter(::isSingleSong)
                    // Chart searches are full of compilations; one more page usually fills the shelf.
                    if (songs.size < size && first.next != null) {
                        songs = songs + Core.search(q, "songs", first.next).items.filterIsInstance<Item.TrackItem>().map { it.track }.filter(::isSingleSong)
                    }
                    Load.Ready(songs.distinctBy { it.id }.take(size))
                } catch (e: CoreException) {
                    Load.Failed(e.message ?: "Couldn't load")
                }
                set(get().toMutableList().also { if (i < it.size) it[i] = Shelf(title, q, result) })
            }
        }
    }

    // ---- Explore: new releases, charts for your region, trending (loaded the first time it opens) ----
    var exploreShelves by mutableStateOf<List<Shelf>>(emptyList())
        private set

    fun loadExplore(force: Boolean = false) {
        if (exploreShelves.isNotEmpty() && !force) return
        val country = Locale.getDefault().displayCountry.ifBlank { "the world" }
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        fillShelves(
            listOf(
                "New releases" to "new music this week $year",
                "Charts · $country" to "top hits this week official music video $year",
                "Trending" to "trending music $year",
                "Viral right now" to "viral songs $year",
            ),
            { exploreShelves },
            { exploreShelves = it },
            size = 20,
        )
    }

    /** "Your mixes": a Supermix from your favourite song, then one per top artist. Each one is a radio. */
    val mixes: List<Pair<String, Track>>
        get() {
            val history = library.history
            if (history.isEmpty() && library.favorites.isEmpty()) return emptyList()
            val out = mutableListOf("Supermix" to (library.favorites.firstOrNull() ?: history.first()))
            recap.take(4).forEach { (artist, _) -> history.firstOrNull { it.artist == artist }?.let { out += "$artist Mix" to it } }
            return out.distinctBy { it.second.id }
        }

    /** Library chip: all, songs, playlists, artists or history. */
    var libraryTab by mutableStateOf("all")

    // ---- Account sheet & recap ----
    var accountOpen by mutableStateOf(false)

    /** Your most-played artists and how many of your recent songs are theirs (from history). */
    val recap: List<Pair<String, Int>>
        get() = library.history.groupingBy { it.artist }.eachCount().entries
            .sortedByDescending { it.value }.take(5).map { it.key to it.value }

    /** Name for the account sheet and Speed dial header. */
    val displayName: String get() = googleChannel ?: "You"

    // ---------------------------------------------------------------------------------------
    // Google account (optional): read-only import of the user's YouTube library
    // ---------------------------------------------------------------------------------------

    private val google = GoogleAccount(app)
    var googleConnected by mutableStateOf(google.isConnected)
        private set
    var googleChannel by mutableStateOf(google.channel)
        private set
    /** What the Google card is doing right now ("Connecting…", "Importing…"), or null. */
    var googleBusy by mutableStateOf<String?>(null)
        private set

    fun googleSignInIntent(): Intent = google.signInIntent()

    fun finishGoogleSignIn(uri: Uri) = io {
        selectTab(Tab.Library)
        googleBusy = "Connecting…"
        try {
            google.complete(uri)
            googleConnected = google.isConnected
            googleChannel = google.channel
            message = "Connected${googleChannel?.let { " as $it" } ?: ""}. Import your library below."
        } finally {
            googleBusy = null
        }
    }

    fun importFromGoogle(playlists: Boolean, liked: Boolean, subscriptions: Boolean) = io {
        googleBusy = "Importing your YouTube library…"
        try {
            val (lib, s) = google.withToken { Core.googleImport(it, playlists, liked, subscriptions) }
            library = lib
            message = buildList {
                if (s.playlistsAdded + s.playlistsUpdated > 0) add("${s.playlistsAdded + s.playlistsUpdated} playlists")
                if (s.favoritesAdded > 0) add("${s.favoritesAdded} liked songs")
                if (s.artistsAdded > 0) add("${s.artistsAdded} artists")
            }.let { if (it.isEmpty()) "Already up to date" else "Imported " + it.joinToString(", ") } +
                if (s.playlistsSkipped > 0) " (${s.playlistsSkipped} playlists skipped: playlist limit reached)" else ""
            if (s.artistsAdded > 0) refreshFeed()
        } finally {
            googleConnected = google.isConnected
            googleBusy = null
        }
    }

    fun disconnectGoogle() = io {
        googleBusy = "Disconnecting…"
        try {
            google.disconnect()
            message = "Disconnected. Your imported music stays in the app."
        } finally {
            googleConnected = google.isConnected
            googleChannel = null
            googleBusy = null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Listen Together: BetterYTS watch-party rooms (same protocol as the website and VRChat)
    // ---------------------------------------------------------------------------------------

    var room by mutableStateOf<RoomClient?>(null)
        private set
    var roomState by mutableStateOf<RoomState?>(null)
        private set
    var roomIsHost by mutableStateOf(false)
        private set
    var roomMembers by mutableStateOf("")
        private set
    var roomStatus by mutableStateOf("")
        private set
    var joinRoomDialog by mutableStateOf(false)

    private val roomKeys = app.getSharedPreferences("rooms", android.content.Context.MODE_PRIVATE)

    /** What's playing: the room's video while in a room, otherwise the local queue's. */
    val nowPlaying: Track?
        get() = if (room != null) roomState?.video?.let { Track(it.id, it.title, "Listening together") } else queue.current

    val canSkip: Boolean get() = if (room != null) roomState?.queue?.isNotEmpty() == true else queue.hasNext

    /** The room's queue shown as tracks (rooms only carry IDs and titles). */
    val roomQueue: List<Track> get() = roomState?.queue.orEmpty().map { Track(it.id, it.title, "Up next in the room") }

    private fun canControlRoom(): Boolean {
        val s = roomState
        if (s?.locked == true && !roomIsHost) {
            message = "The host controls playback in this room"
            return false
        }
        return true
    }

    /** Sends a playback/queue change to the room (returns false when the room is host-only). */
    private fun roomSend(type: String, vararg pairs: Pair<String, Any?>): Boolean {
        val r = room ?: return false
        if (!canControlRoom()) return false
        r.send(type, *pairs)
        return true
    }

    fun startTogether() = io {
        val o = Core.roomsNew()
        val code = o.first ?: throw CoreException("Couldn't create a room")
        o.second?.let { roomKeys.edit().putString(code, it).apply() }
        // Bring what's playing (and what's next) into the new room.
        val seed = listOfNotNull(queue.current) + queue.tracks.drop(queue.position + 1).take(30)
        val start = player?.position ?: 0f
        joinRoom(code)
        val r = room ?: return@io
        seed.forEachIndexed { i, t -> r.send(if (i == 0) "load" else "queue_add", "video" to r.video(t.id, t.title)) }
        if (start > 3f && seed.isNotEmpty()) r.send("seek", "position" to start)
        message = "Room $code is open. Share the invite link!"
    }

    fun joinRoom(rawCode: String) {
        val code = rawCode.trim().uppercase()
        if (!RoomClient.CODE.matches(code)) {
            message = "Room codes are 6 letters and numbers"
            return
        }
        room?.close()
        roomState = null
        roomIsHost = false
        roomMembers = ""
        val client = RoomClient(code, roomKeys.getString(code, null), "Guest", object : RoomClient.Events {
            override fun onState(state: RoomState) {
                roomState = state
                applyRoomState(state)
            }
            override fun onMembers(count: Int, names: List<String>, hosts: List<String>) {
                roomMembers = "$count listening" + if (hosts.isNotEmpty()) " · host: ${hosts.first()}" else ""
            }
            override fun onRole(host: Boolean) {
                roomIsHost = host
            }
            override fun onDenied(reason: String) {
                message = if (reason == "not_host") "Only the host can change that" else "The host controls playback in this room"
            }
            override fun onStatus(status: String) {
                roomStatus = when (status) {
                    "connected" -> ""
                    "full" -> "Room is full"
                    else -> "$status…"
                }
            }
        })
        room = client
        client.connect()
        playerExpanded = true
    }

    fun leaveRoom() {
        room?.close()
        room = null
        roomState = null
        roomIsHost = false
        player?.pause()
        message = "You left the room"
    }

    fun toggleRoomLock() {
        val r = room ?: return
        if (!roomIsHost) return
        r.send("lock", "locked" to !(roomState?.locked ?: false))
    }

    fun roomInviteLink(code: String) = "${RoomClient.BASE}/?room=$code"
    fun roomVrchatLink(code: String) = "${RoomClient.BASE}/room/$code"

    /** Makes the local player match the room. */
    private fun applyRoomState(s: RoomState) {
        val p = player ?: return
        val r = room ?: return
        val v = s.video
        if (v == null) {
            p.pause()
            return
        }
        val target = r.positionOf(s)
        if (p.currentId != v.id) {
            p.load(v.id, target, autoplay = s.playing)
            onTrackChanged(Track(v.id, v.title, "Listening together"))
            return
        }
        if (kotlin.math.abs(p.position - target) > 2f) p.seek(target)
        if (s.playing && !p.playing) p.play() else if (!s.playing && p.playing) p.pause()
    }

    // Last, so every property above is initialised first (Kotlin runs initialisers in order).
    init {
        loadShelves()
    }
}
