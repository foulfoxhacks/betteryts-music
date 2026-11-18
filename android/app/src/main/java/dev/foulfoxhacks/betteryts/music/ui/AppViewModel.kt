package dev.foulfoxhacks.betteryts.music.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import dev.foulfoxhacks.betteryts.music.account.GoogleAccount
import androidx.compose.runtime.getValue
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
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

enum class Tab { Home, Search, Library }

sealed interface Route {
    data class ArtistRoute(val id: String, val name: String?) : Route
    data class CollectionRoute(val collection: Collection) : Route
    data class PlaylistRoute(val id: String) : Route
}

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

    private val ready: Job = viewModelScope.launch {
        try {
            Core.init(app.filesDir.absolutePath, Locale.getDefault().country)
            library = Core.library()
            queue = Core.queueGet()
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
        // The activity was recreated with something in the queue: cue it, don't blast it.
        queue.current?.let { p.load(it.id, autoplay = false) }
    }

    fun detach(p: PlayerController) {
        if (player === p) player = null
    }

    private fun playCurrent() {
        val t = queue.current ?: return
        player?.load(t.id)
    }

    fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        io {
            queue = Core.queueSet(tracks, start, shuffle)
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
        if (p.currentId == null) playCurrent() else p.toggle()
    }

    fun next() = io {
        val q = Core.queueNext(user = true)
        queue = q
        if (!q.ended) playCurrent()
    }

    fun previous() {
        val p = player
        if (p != null && p.position > 4f) {
            p.seek(0f)
            return
        }
        io {
            queue = Core.queuePrevious()
            playCurrent()
        }
    }

    fun jumpTo(position: Int) = io {
        queue = Core.queueJump(position)
        playCurrent()
    }

    fun playNext(track: Track) = io {
        val wasEmpty = queue.current == null
        queue = Core.queuePlayNext(track)
        if (wasEmpty) playCurrent() else message = "Playing next: ${track.title}"
    }

    fun addToQueue(track: Track) = io {
        val wasEmpty = queue.current == null
        queue = Core.queueAdd(track)
        if (wasEmpty) playCurrent() else message = "Added to queue"
    }

    fun addAllToQueue(tracks: List<Track>) = io {
        val wasEmpty = queue.current == null
        queue = Core.queueAddAll(tracks)
        if (wasEmpty) playCurrent() else message = "Added ${tracks.size} songs to the queue"
    }

    fun removeFromQueue(position: Int) = io {
        val before = queue.current?.id
        queue = Core.queueRemove(position)
        if (queue.current?.id != before) {
            if (queue.current == null) player?.pause() else playCurrent()
        }
    }

    fun moveInQueue(from: Int, to: Int) = io { queue = Core.queueMove(from, to) }
    fun toggleShuffle() = io { queue = Core.queueShuffle() }
    fun cycleRepeat() = io { queue = Core.queueRepeat() }

    fun seek(seconds: Float) = player?.seek(seconds)

    override fun onStartedPlaying(id: String) {
        val t = queue.current?.takeIf { it.id == id } ?: return
        io { library = Core.played(t) }
    }

    override fun onEnded(id: String) {
        if (queue.current?.id != id) return
        io {
            val q = Core.queueNext(user = false)
            queue = q
            if (!q.ended) playCurrent()
        }
    }

    override fun onError(id: String?, code: Int) {
        message = PlayerController.describe(code)
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
}
