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
import dev.foulfoxhacks.betteryts.music.core.SmartPlaylist
import dev.foulfoxhacks.betteryts.music.core.SmartRule
import dev.foulfoxhacks.betteryts.music.core.Station
import dev.foulfoxhacks.betteryts.music.core.Stations
import dev.foulfoxhacks.betteryts.music.core.Track
import dev.foulfoxhacks.betteryts.music.core.UserPlaylist
import dev.foulfoxhacks.betteryts.music.device.DeviceMusic
import dev.foulfoxhacks.betteryts.music.player.PlayerController
import dev.foulfoxhacks.betteryts.music.together.RoomClient
import dev.foulfoxhacks.betteryts.music.together.RoomState
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import dev.foulfoxhacks.betteryts.music.ui.theme.Palettes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.Locale

/** Bottom bar: Home, Explore, Library (YouTube Music's layout). Search opens from the top bar. */
enum class Tab { Home, Explore, Search, Library }

sealed interface Route {
    data class ArtistRoute(val id: String, val name: String?) : Route
    data class CollectionRoute(val collection: Collection) : Route
    data class PlaylistRoute(val id: String) : Route
    data class SmartRoute(val id: String, val name: String) : Route
    data object SettingsRoute : Route
    data object DiagnosticsRoute : Route
    data object CustomizeRoute : Route
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
    /** Look and feel choices that only the app needs: accent, album colours, start tab, autoplay. */
    private val look = app.getSharedPreferences("look", android.content.Context.MODE_PRIVATE)

    // ---- navigation ----
    var tab by mutableStateOf(runCatching { Tab.valueOf(look.getString("startTab", "Home") ?: "Home") }.getOrDefault(Tab.Home).takeIf { it != Tab.Search } ?: Tab.Home)
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

    /** A button for the next snackbar ("Undo"), shown with [message]. */
    var messageAction by mutableStateOf<Pair<String, () -> Unit>?>(null)

    fun say(text: String, action: Pair<String, () -> Unit>? = null) {
        messageAction = action
        message = text
    }

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
            applyPlugins()
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
        // Kept on this phone only, for the recent-searches list.
        io { library = Core.addSearch(text) }
    }

    fun removeSearch(q: String) = io { library = Core.removeSearch(q) }

    fun clearSearches() = io {
        library = Core.clearSearches()
        message = "Search history cleared"
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
        p.setVolumePercent(curve(volumeSlider))
        p.skipSilence = skipSilence
        p.onFxReady = { fx -> applyFx(fx) }
        p.fx?.let(::applyFx)
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

    // ---- Stations: endless radio from a song, artist or playlist (core/src/stations.rs) ----
    // The core owns the continuation, repeats, feedback, tuning and recovery; the app only
    // appends what it hands back to the queue.

    /** The station the queue is riding, if any. */
    var station by mutableStateOf<Station?>(null)
        private set

    /** Saved and recent stations, for Library and Home. */
    var stations by mutableStateOf(Stations())
        private set
    private var radioLoading = false

    /** What the radio is "songs like", for the player header. */
    val radioFor: String? get() = station?.label

    fun startRadio(track: Track) = startStation("track", track.id, track.title, track.art, seedTrack = track)

    fun startArtistRadio(artist: Artist) = startStation("artist", artist.id, artist.name, artist.thumbnail)

    fun startCollectionRadio(c: Collection) = startStation("playlist", c.id, c.title, c.thumbnail)

    fun startPlaylistRadio(p: UserPlaylist) = startStation("library", p.id, p.name, p.tracks.firstOrNull { !it.isLocal }?.art, ownTracks = p.tracks)

    /** Plays a saved or recent station again (fresh songs, same tuning and blocked songs). */
    fun resumeStation(st: Station) {
        if (!canStartRadio()) return
        io {
            val own = if (st.kind == "library") library.playlist(st.seedId)?.tracks else null
            applyStationPage(Core.stationResume(st.id, own), seed = null)
        }
    }

    private fun canStartRadio(): Boolean {
        if (room != null) {
            message = "Radio isn't available in a room. Add songs to the room queue instead."
            return false
        }
        return true
    }

    private fun startStation(kind: String, id: String, title: String, art: String?, seedTrack: Track? = null, ownTracks: List<Track>? = null) {
        if (seedTrack?.isLocal == true) {
            message = "Radio comes from YouTube Music, so it needs a YouTube song."
            return
        }
        if (!canStartRadio()) return
        io {
            message = "Starting radio…"
            applyStationPage(Core.stationStart(kind, id, title, art, tracks = ownTracks), seedTrack)
        }
    }

    private suspend fun applyStationPage(page: dev.foulfoxhacks.betteryts.music.core.StationPage, seed: Track?) {
        station = page.station
        // A song's radio starts with that song.
        val tracks = if (seed != null) listOf(seed) + page.tracks.filter { it.id != seed.id } else page.tracks
        if (tracks.isEmpty()) throw CoreException("No radio for this one. Try a different song.")
        applyQueue(Core.queueSet(tracks, 0, false))
        playCurrent()
        message = page.station?.label?.let { "Radio: $it" } ?: "Radio started"
        refreshStations()
    }

    fun refreshStations() = io { stations = Core.stations() }

    /** Familiar, balanced or discover: how much of what you already know the station plays. */
    fun tuneStation(mix: String) = io {
        stations = Core.stationTune(mix)
        station = stations.current
        message = when (mix) {
            "familiar" -> "More of the artists you know"
            "discover" -> "More artists you haven't heard"
            "smart" -> "Smart: ordered by genre, tempo, key and your taste"
            else -> "A balanced mix"
        }
    }

    fun saveStation() = io {
        stations = Core.stationSave()
        station = stations.current
        message = "Station saved to your library"
    }

    fun deleteStation(id: String) = io {
        stations = Core.stationDelete(id)
        library = Core.library()
    }

    /** Never this song on this station again (twice for one artist blocks the artist). */
    fun notForStation(track: Track, position: Int) {
        if (station == null) return
        io {
            val (result, all) = Core.stationFeedback(track, "notForStation")
            stations = all
            station = all.current
            message = if (result == "artist blocked") "Got it: no more ${track.artist} on this station" else "Got it: not for this station"
        }
        removeFromQueue(position)
    }

    /** The station's next songs grow from this one. */
    fun moreLikeThis(track: Track) {
        if (station == null) return
        io {
            Core.stationFeedback(track, "moreLikeThis")
            message = "Coming up: more like ${track.title}"
        }
    }

    private fun stationFeedback(track: Track, kind: String) {
        if (station == null || room != null) return
        viewModelScope.launch {
            ready.join()
            try {
                Core.stationFeedback(track, kind)
            } catch (_: CoreException) {
                // Feedback is best-effort; the station works without it.
            }
        }
    }

    private suspend fun stopStation() {
        if (station == null) return
        station = null
        stations = Core.stationStop()
    }

    /** With 3 or fewer songs left, adds more from the station (or starts one from what's playing). */
    private fun topUpRadio() {
        val q = queue
        val current = q.current ?: return
        if (!pluginOn("radio") || !autoplay || room != null || radioLoading || q.repeat != Repeat.Off) return
        // A list of device files ends where it ends, unless it's already riding a station.
        if (current.isLocal && station == null) return
        if (q.tracks.size - 1 - q.position > 3) return
        radioLoading = true
        viewModelScope.launch {
            try {
                ready.join()
                // Continue the station we're on; otherwise grow a new one from the current song.
                val page = if (station != null) Core.stationMore() else Core.stationStart("track", current.id, current.title, current.art)
                station = page.station
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

    // ---- Sound: precise and exponential volume, skip silence, equalizer, compressor ----

    /** The volume slider's position (0–1). With exponential volume on, the player gets slider³. */
    var volumeSlider by mutableFloatStateOf(playPrefs.getFloat("volume", 1f).coerceIn(0f, 1f))
        private set
    var exponentialVolume by mutableStateOf(playPrefs.getBoolean("expVolume", true))
        private set
    var skipSilence by mutableStateOf(playPrefs.getBoolean("skipSilence", false))
        private set
    var skipDisliked by mutableStateOf(playPrefs.getBoolean("skipDisliked", true))
        private set
    var eqOn by mutableStateOf(playPrefs.getBoolean("eqOn", false))
        private set
    var eqPreset by mutableStateOf(playPrefs.getInt("eqPreset", -1))
        private set
    var compressor by mutableStateOf(playPrefs.getBoolean("compressor", false))
        private set

    /** Equalizer gains in millibels, by band (empty = flat). */
    private var eqLevels: List<Int> = playPrefs.getString("eqLevels", "")!!.split(',').mapNotNull { it.toIntOrNull() }

    private fun curve(slider: Float) = if (exponentialVolume) slider * slider * slider * 100f else slider * 100f

    /** The volume the player actually uses, in percent (shown next to the slider). */
    val volumePercent: Float get() = curve(volumeSlider)

    fun setVolume(slider: Float) {
        volumeSlider = slider.coerceIn(0f, 1f)
        player?.setVolumePercent(curve(volumeSlider))
        playPrefs.edit().putFloat("volume", volumeSlider).apply()
    }

    fun updateExponentialVolume(on: Boolean) {
        exponentialVolume = on
        playPrefs.edit().putBoolean("expVolume", on).apply()
        player?.setVolumePercent(curve(volumeSlider))
    }

    fun updateSkipSilence(on: Boolean) {
        skipSilence = on
        player?.skipSilence = on
        playPrefs.edit().putBoolean("skipSilence", on).apply()
    }

    fun updateSkipDisliked(on: Boolean) {
        skipDisliked = on
        playPrefs.edit().putBoolean("skipDisliked", on).apply()
    }

    fun setEq(on: Boolean) {
        eqOn = on
        playPrefs.edit().putBoolean("eqOn", on).apply()
        player?.fx?.setEnabled(on)
    }

    fun setEqBand(band: Int, millibels: Int) {
        val fx = player?.fx ?: return
        val levels = (0 until fx.bands).map { if (it == band) millibels else eqLevels.getOrElse(it) { fx.level(it) } }
        eqLevels = levels
        eqPreset = -1
        fx.setLevel(band, millibels)
        playPrefs.edit().putString("eqLevels", levels.joinToString(",")).putInt("eqPreset", -1).apply()
    }

    fun useEqPreset(i: Int) {
        val fx = player?.fx ?: return
        fx.usePreset(i)
        eqPreset = i
        eqLevels = (0 until fx.bands).map { fx.level(it) }
        playPrefs.edit().putInt("eqPreset", i).putString("eqLevels", eqLevels.joinToString(",")).apply()
    }

    fun updateCompressor(on: Boolean) {
        compressor = on
        playPrefs.edit().putBoolean("compressor", on).apply()
        player?.fx?.updateCompressor(on)
    }

    private fun applyFx(fx: dev.foulfoxhacks.betteryts.music.player.AudioFx) {
        if (eqPreset >= 0) fx.usePreset(eqPreset) else eqLevels.forEachIndexed { b, mb -> if (b < fx.bands) fx.setLevel(b, mb) }
        fx.setEnabled(eqOn)
        fx.updateCompressor(compressor)
    }

    /** Android's own output picker (speaker, Bluetooth, cast…), where the system offers one. */
    fun openOutputSwitcher() {
        val app = getApplication<Application>()
        val ok = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                android.media.MediaRouter2.getInstance(app).showSystemOutputSwitcher()
            } else {
                val i = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", app.packageName)
                app.sendBroadcast(i)
                true
            }
        }.getOrDefault(false)
        if (!ok) {
            runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { message = "This phone doesn't offer an output picker" }
        }
    }

    // ---- Dislikes, album actions, sharing ----

    fun toggleDislike(track: Track): Job = io {
        val (lib, on) = Core.toggleDislike(track)
        library = lib
        if (on == true) {
            say("Disliked: you won't hear it on stations${if (skipDisliked) " and it's skipped in the queue" else ""}", "Undo" to { toggleDislike(track); Unit })
            if (queue.current?.id == track.id && skipDisliked && room == null) next()
        } else {
            message = "Dislike removed"
        }
    }

    /** "Like all" on an album or playlist page. */
    fun likeAll(tracks: List<Track>) = io {
        val (lib, n) = Core.likeMany(tracks.filterNot { it.isLocal })
        library = lib
        val added = (n as? Number)?.toInt() ?: 0
        message = if (added == 0) "Already in your liked songs" else "Liked $added song${if (added == 1) "" else "s"}"
    }

    /** Shares a clean music.youtube.com link (no `si=` or other tracking). */
    fun share(track: Track) = io {
        if (track.isLocal) throw CoreException("Songs on your phone can't be shared as a link")
        val link = Core.cleanShare("https://youtu.be/${track.id}") ?: "https://music.youtube.com/watch?v=${track.id}"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${track.title} · ${track.artist}\n$link")
        getApplication<Application>().startActivity(Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Scrobbling (ListenBrainz, opt-in) ----

    var scrobbleUser by mutableStateOf(playPrefs.getString("scrobbleUser", null))
        private set

    fun connectScrobbling(token: String) = io {
        val (user, s) = Core.scrobbleConnect(token)
        settings = s
        scrobbleUser = user
        playPrefs.edit().putString("scrobbleUser", user).apply()
        message = "Scrobbling to ListenBrainz${if (user.isNotBlank()) " as $user" else ""}"
    }

    fun disconnectScrobbling() = io {
        settings = Core.scrobbleDisconnect()
        scrobbleUser = null
        playPrefs.edit().remove("scrobbleUser").apply()
        message = "Scrobbling off. The token was removed from this phone."
    }

    private fun scrobble(block: suspend () -> Unit) {
        if (!settings.scrobbling || room != null) return
        viewModelScope.launch {
            ready.join()
            try {
                block()
            } catch (_: CoreException) {
                // A missed scrobble isn't worth interrupting the music for.
            }
        }
    }

    // ---- SponsorBlock categories ----

    fun setSponsorCategory(id: String, on: Boolean) = io {
        val cur = settings.sponsorCategories
        val next = if (on) (cur + id).distinct() else cur - id
        settings = Core.setSponsorCategories(next.ifEmpty { listOf("music_offtopic") })
        segmentsFor.clear()
    }

    /** What SponsorBlock skipped in this session, newest first: (song, category, seconds saved). */
    val skipLog = mutableStateListOf<Triple<String, String, Float>>()

    // ---- Launcher shortcuts ----

    fun openShortcut(target: String?) {
        when (target) {
            "search" -> selectTab(Tab.Search)
            "library" -> selectTab(Tab.Library)
            "explore" -> selectTab(Tab.Explore)
            "radio" -> {
                selectTab(Tab.Home)
                (stations.saved.firstOrNull() ?: stations.recent.firstOrNull())?.let(::resumeStation)
                    ?: library.history.firstOrNull { !it.isLocal }?.let(::startRadio)
            }
        }
    }

    /** Player › Sound panel. */
    var soundOpen by mutableStateOf(false)

    // ---- Player gestures: a short on-screen hint ("+10 s", "Volume 40%") ----
    var gestureHint by mutableStateOf<String?>(null)
        private set
    private var hintJob: Job? = null

    fun showHint(text: String) {
        gestureHint = text
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            delay(700)
            gestureHint = null
        }
    }

    fun seekBy(seconds: Float) {
        val p = player ?: return
        if (nowPlaying?.live == true) return
        val to = (p.position + seconds).coerceIn(0f, (p.duration - 1f).coerceAtLeast(0f))
        seek(to)
        showHint(if (seconds > 0) "+${seconds.toInt()} s" else "${seconds.toInt()} s")
    }

    private fun playCurrent() {
        val t = queue.current ?: return
        endedId = null
        if (lanHost && !t.isLocal) {
            // Everyone cues the song now and starts it together a moment later.
            val start = resumePosition(t)
            player?.load(t.id, start, autoplay = false)
            lanBroadcast(JSONObject().put("type", "custom").put("data", JSONObject().put("title", t.title).put("artist", t.artist).toString()), leadMs = 0) {}
            lanBroadcast(JSONObject().put("type", "load").put("id", t.id).put("position", start.toDouble()), leadMs = 0) {}
            lanBroadcast(lanCommand("play", start), leadMs = 700) { player?.play() }
            onTrackChanged(t)
            return
        }
        player?.load(t.id, resumePosition(t))
        onTrackChanged(t)
    }

    // ---------------------------------------------------------------------------------------
    // Link nearby devices (BTLink): one phone hosts, others on the same Wi-Fi follow it to the
    // millisecond. Encrypted; only devices with the invite (QR or link) can join.
    // ---------------------------------------------------------------------------------------

    data class LanState(val role: String, val invite: String?, val locked: Boolean, val peers: List<Pair<String, String>>, val rttMs: Double?)

    var lan by mutableStateOf<LanState?>(null)
        private set
    var lanFingerprint by mutableStateOf<String?>(null)
        private set
    var lanOpen by mutableStateOf(false)
    private var lanTrack by mutableStateOf<Track?>(null)
    private var lanJob: Job? = null
    private val lanHost get() = lan?.role == "host"
    private val lanMember get() = lan?.role == "member"
    private val lanMain = android.os.Handler(android.os.Looper.getMainLooper())

    private fun lanCommand(type: String, position: Float) = JSONObject().put("type", type).put("position", position.toDouble())

    fun hostLan() = io {
        if (room != null) throw CoreException("Leave the Listen Together room first")
        val (invite, fp) = Core.lanHost(displayName)
        lanFingerprint = fp
        lan = LanState("host", invite, false, emptyList(), null)
        startLanPolling()
        message = "Hosting on this network. Share the invite."
    }

    fun joinLan(invite: String) = io {
        if (room != null) throw CoreException("Leave the Listen Together room first")
        lanFingerprint = Core.lanJoin(invite.trim(), displayName)
        lan = LanState("member", null, false, emptyList(), null)
        player?.pause()
        startLanPolling()
        message = "Connecting to the host…"
    }

    fun leaveLan() = io {
        lanJob?.cancel()
        lanMain.removeCallbacksAndMessages(null)
        Core.lanLeave()
        lan = null
        lanTrack = null
        message = "Left the link"
    }

    fun setLanLocked(locked: Boolean) = io { Core.lanLock(locked) }

    /** Share text for the invite: the deep link (opens the app) plus the raw invite. */
    fun lanInviteLink(invite: String) = "betterytsmusic://link?i=" + android.net.Uri.encode(invite)

    private fun lanBroadcast(cmd: JSONObject, leadMs: Int = 150, local: () -> Unit) {
        viewModelScope.launch {
            try {
                Core.lanSend(cmd, leadMs)
            } catch (_: CoreException) {
            }
        }
        if (leadMs <= 0) local() else lanMain.postDelayed({ local() }, leadMs.toLong())
    }

    private fun lanRequest(cmd: JSONObject) {
        viewModelScope.launch {
            try {
                Core.lanSend(cmd, 150)
            } catch (e: CoreException) {
                message = e.message
            }
        }
    }

    private fun startLanPolling() {
        lanJob?.cancel()
        lanJob = viewModelScope.launch {
            while (true) {
                val o = try { Core.lanPoll() } catch (_: CoreException) { break }
                if (!o.optBoolean("active")) {
                    lan = null
                    break
                }
                val peers = o.optJSONArray("peers").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i)?.let { (it.optString("name")) to it.optString("fingerprint") } } }
                lan = LanState(o.optString("role"), o.optString("invite").ifBlank { null }, o.optBoolean("locked"), peers, if (o.isNull("rttMs")) null else o.optDouble("rttMs"))
                val events = o.optJSONArray("events")
                for (i in 0 until (events?.length() ?: 0)) events?.optJSONObject(i)?.let(::onLanEvent)
                delay(30)
            }
        }
    }

    private fun onLanEvent(e: JSONObject) {
        when (e.optString("type")) {
            "connected" -> message = if (lanHost) "${e.optString("name")} joined" else "Linked with ${e.optString("name")}"
            "disconnected" -> message = "${e.optString("name").ifBlank { "A device" }} left"
            "denied" -> message = "The host has locked the controls"
            "control" -> {
                val cmd = e.optJSONObject("command") ?: return
                val inMs = e.optLong("inMs")
                if (lanMember) applyLanCommand(cmd, inMs) else hostApplyRequest(cmd)
            }
        }
    }

    /** Member: do what the host said, at the moment it said (late commands catch up by seeking). */
    private fun applyLanCommand(cmd: JSONObject, inMs: Long) {
        val p = player ?: return
        val pos = cmd.optDouble("position", 0.0).toFloat()
        val late = (-inMs).coerceAtLeast(0) / 1000f
        val run: () -> Unit = when (cmd.optString("type")) {
            "custom" -> {
                runCatching { JSONObject(cmd.optString("data")) }.getOrNull()?.let { m -> lanTrack = Track(lanTrack?.id ?: "", m.optString("title"), m.optString("artist")) }
                return
            }
            "load" -> {
                val id = cmd.optString("id")
                lanTrack = (lanTrack ?: Track(id, "Linked", "")).copy(id = id)
                p.load(id, pos, autoplay = false)
                return
            }
            "play" -> ({ p.seek(pos + late); p.play() })
            "pause" -> ({ p.pause(); p.seek(pos) })
            "seek" -> ({ p.seek(pos + if (p.playing) late else 0f) })
            "stop" -> ({ p.pause() })
            else -> return
        }
        if (inMs > 0) lanMain.postDelayed(run, inMs) else run()
    }

    /** Host: a member asked for play/pause/seek (the room isn't locked, or the core would refuse). */
    private fun hostApplyRequest(cmd: JSONObject) {
        val p = player ?: return
        when (cmd.optString("type")) {
            "play" -> if (!p.playing) togglePlay()
            "pause" -> if (p.playing) togglePlay()
            "seek" -> seek(cmd.optDouble("position", 0.0).toFloat())
        }
    }

    fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (room != null) {
            val t = tracks[start.coerceIn(0, tracks.lastIndex)]
            if (t.isLocal) {
                message = "Songs on your phone can't be shared to a room."
                return
            }
            if (roomSend("load", "video" to room!!.video(t.id, t.title))) message = "Playing for everyone: ${t.title}"
            return
        }
        io {
            // A new list you chose; when it runs out, a new station grows from its last song.
            stopStation()
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
        if (lanMember) {
            lanRequest(lanCommand(if (p.playing) "pause" else "play", p.position))
            return
        }
        if (lanHost && p.currentId != null) {
            val play = !p.playing
            lanBroadcast(lanCommand(if (play) "play" else "pause", p.position)) { if (play) p.play() else p.pause() }
            return
        }
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
        val skipped = queue.current
        if (skipped != null && (player?.position ?: 0f) < 30f) stationFeedback(skipped, "skip")
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
        if (room != null && track.isLocal) {
            message = "Songs on your phone can't be shared to a room."
            return
        }
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
            if (canControlRoom()) tracks.filterNot { it.isLocal }.take(50).forEach { t -> room!!.send("queue_add", "video" to room!!.video(t.id, t.title)) }
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

    /** "Save queue as playlist": asks for a name (the New playlist dialog), pre-filled with the queue. */
    fun saveQueueAsPlaylist() {
        val tracks = if (room != null) roomQueue else queue.tracks
        if (tracks.isEmpty()) {
            message = "The queue is empty"
            return
        }
        newPlaylistWith = tracks
    }

    /** Clears the queue; the snackbar offers Undo for a few seconds. */
    fun clearQueue() {
        if (!notInRoom("Clearing the queue")) return
        val before = queue
        if (before.tracks.isEmpty()) return
        val at = player?.position ?: 0f
        io {
            stopStation()
            player?.pause()
            applyQueue(Core.queueClear())
            val undo: () -> Unit = {
                io {
                    applyQueue(Core.queueSet(before.tracks, before.position.coerceAtLeast(0), shuffle = false))
                    queue.current?.let { player?.load(it.id, at, autoplay = false) }
                }
            }
            say("Queue cleared", "Undo" to undo)
        }
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
        if (lanMember) {
            lanRequest(lanCommand("seek", seconds))
            return
        }
        if (lanHost) lanBroadcast(lanCommand("seek", seconds), leadMs = 60) {}
        if (room != null) {
            roomSend("seek", "position" to seconds)
        } else {
            if (seconds < 1f) queue.current?.let { playPrefs.edit().remove("pos:" + it.id).apply() }
            player?.seek(seconds)
        }
    }

    override fun onStartedPlaying(id: String) {
        val t = queue.current?.takeIf { it.id == id } ?: return
        val now = java.time.ZonedDateTime.now()
        if (skipDisliked && library.isDisliked(t.id) && room == null) {
            message = "Skipped a song you disliked"
            next()
            return
        }
        io { library = Core.played(t, now.hour, now.toLocalDate().toEpochDay()) }
        stationFeedback(t, "played")
        playTrack = t
        playStartedAt = System.currentTimeMillis()
        playListened = 0f
        scrobble { Core.scrobbleNowPlaying(t) }
    }

    // ---- Listening time, for local insights: counted from player progress, reported per song ----
    private var listenId: String? = null
    private var listenAt = -1f
    private var listenSeconds = 0f

    /** The play being timed for scrobbling: song, start time, seconds listened. */
    private var playTrack: Track? = null
    private var playStartedAt = 0L
    private var playListened = 0f

    private fun finishPlay() {
        val t = playTrack ?: return
        val listened = playListened.toLong()
        val started = playStartedAt
        playTrack = null
        playListened = 0f
        if (listened > 30) scrobble { Core.scrobbleListen(t, started, listened) }
    }

    private fun trackListening(id: String, seconds: Float) {
        if (id != listenId) {
            if (playTrack?.id != id) finishPlay()
            flushListening()
            listenId = id
            listenAt = seconds
            return
        }
        val step = seconds - listenAt
        listenAt = seconds
        // Ticks come every half second; anything bigger is a seek, not listening.
        if (player?.playing == true && step > 0f && step <= 2.5f) {
            listenSeconds += step
            if (playTrack?.id == id) playListened += step
        }
        if (listenSeconds >= 120f) flushListening(keep = true)
    }

    private fun flushListening(keep: Boolean = false) {
        val id = listenId ?: return
        val secs = listenSeconds.toLong()
        listenSeconds = 0f
        if (!keep) listenId = null
        if (secs <= 0) return
        viewModelScope.launch {
            ready.join()
            try {
                Core.listened(id, secs)
            } catch (_: CoreException) {
            }
        }
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
        // Access to the phone's music was taken away: every device song would fail the same
        // way, so stop here instead of skipping through all of them.
        if (DeviceMusic.isLocal(id) && !DeviceMusic.hasPermission(getApplication())) {
            deviceSongs = null
            message = "BetterYTS Music can't read your music anymore. Allow access in Library → On this phone."
            return
        }
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

    fun removeFromHistory(t: Track) = io {
        library = Core.removeHistory(t.id)
        message = "Removed from history"
    }

    // ---- Pins, batch playlist editing ----

    /** Pins or unpins "playlist:<id>", "artist:<id>", "station:<id>" or "smart:<id>". */
    fun togglePin(key: String, name: String) = io {
        val (lib, on) = Core.pin(key)
        library = lib
        message = if (on == true) "Pinned $name" else if (lib.pinned.size >= 24) "You can pin up to 24 things" else "Unpinned $name"
    }

    fun movePlaylistTrack(id: String, from: Int, to: Int) = io { library = Core.movePlaylistTrack(id, from, to) }

    fun removeManyFromPlaylist(id: String, indexes: List<Int>) = io {
        val (lib, n) = Core.removeManyFromPlaylist(id, indexes)
        library = lib
        message = "Removed ${(n as? Number)?.toInt() ?: 0} songs"
    }

    fun addManyToPlaylist(id: String, tracks: List<Track>) = io {
        val (lib, n) = Core.addManyToPlaylist(id, tracks)
        library = lib
        val added = (n as? Number)?.toInt() ?: 0
        message = if (added == 0) "Those songs are already there" else "Added $added songs to ${lib.playlist(id)?.name ?: "the playlist"}"
    }

    fun dedupePlaylist(id: String) = io {
        val (lib, n) = Core.dedupePlaylist(id)
        library = lib
        val removed = (n as? Number)?.toInt() ?: 0
        message = if (removed == 0) "No repeats found" else "Removed $removed repeat${if (removed == 1) "" else "s"}"
    }

    fun mergePlaylists(target: String, source: String, deleteSource: Boolean) = io {
        val (lib, n) = Core.mergePlaylists(target, source, deleteSource)
        library = lib
        if (deleteSource) stack.removeAll { it is Route.PlaylistRoute && it.id == source }
        message = "Merged: ${(n as? Number)?.toInt() ?: 0} new songs in ${lib.playlist(target)?.name ?: "the playlist"}"
    }

    fun duplicatePlaylist(id: String) = io {
        val (lib, copy) = Core.duplicatePlaylist(id)
        library = lib
        val newId = (copy as? JSONObject)?.optString("id")
        if (newId.isNullOrEmpty()) message = "You've reached the playlist limit" else {
            message = "Copied"
            openUserPlaylist(newId)
        }
    }

    // ---- Smart playlists and insights (worked out on the phone) ----

    var smartPlaylists by mutableStateOf<List<SmartPlaylist>>(emptyList())
        private set
    val smartTracks = mutableStateMapOf<String, Load<List<Track>>>()

    fun refreshSmart() = io { smartPlaylists = Core.smartList() }

    fun openSmart(sp: SmartPlaylist) {
        playerExpanded = false
        stack.add(Route.SmartRoute(sp.id, sp.name))
        loadSmart(sp.id)
    }

    fun loadSmart(id: String) = io {
        smartTracks[id] = Load.Loading
        smartTracks[id] = try {
            Load.Ready(Core.smartTracks(id))
        } catch (e: CoreException) {
            Load.Failed(e.message ?: "Couldn't build this playlist")
        }
    }

    /** For the rule editor: how many songs match, a few of them, and the rule in words. */
    suspend fun previewSmart(rule: SmartRule): Triple<Int, List<Track>, String> {
        ready.join()
        return Core.smartPreview(rule)
    }

    fun saveSmart(name: String, rule: SmartRule) = io {
        val (lib, id) = Core.smartSave(name, rule)
        library = lib
        if (id == null) message = "You can have up to 30 smart playlists" else {
            message = "Smart playlist saved"
            smartPlaylists = Core.smartList()
        }
    }

    fun deleteSmart(id: String) = io {
        library = Core.smartDelete(id)
        smartPlaylists = Core.smartList()
        stack.removeAll { it is Route.SmartRoute && it.id == id }
        message = "Smart playlist deleted"
    }

    var insights by mutableStateOf<Load<dev.foulfoxhacks.betteryts.music.core.Insights>?>(null)
        private set

    fun loadInsights() = io {
        insights = Load.Loading
        insights = try {
            Load.Ready(Core.insights(java.time.LocalDate.now().toEpochDay()))
        } catch (e: CoreException) {
            Load.Failed(e.message ?: "Couldn't work out your stats")
        }
    }

    fun clearListeningStats() = io {
        library = Core.clearStats()
        message = "Listening stats cleared"
        loadInsights()
        smartPlaylists = Core.smartList()
    }

    // ---------------------------------------------------------------------------------------
    // Settings, theme and plugins
    // ---------------------------------------------------------------------------------------

    fun openSettings() {
        playerExpanded = false
        if (stack.lastOrNull() != Route.SettingsRoute) stack.add(Route.SettingsRoute)
    }

    fun setTheme(id: String) {
        if (Brand.look.useCustom) {
            Brand.look = Brand.look.copy(useCustom = false)
            Brand.look.save(look)
        }
        Brand.palette = withLook(Palettes.byId(id)) // instant; saved below
        io { settings = Core.setTheme(id) }
    }

    fun setPlugin(id: String, enabled: Boolean) = io {
        settings = Core.setPlugin(id, enabled)
        applyPlugins()
        val t = queue.current
        if (enabled && t != null) onTrackChanged(t)
        if (!pluginOn("sleep")) cancelSleep()
    }

    /** The whole-category switch: every plugin in it stops (each keeps its own switch for later). */
    fun setCategory(id: String, enabled: Boolean) = io {
        settings = Core.setCategory(id, enabled)
        applyPlugins()
        val t = queue.current
        if (enabled && t != null) onTrackChanged(t)
        if (!pluginOn("sleep")) cancelSleep()
    }

    /** Set when a pasted manifest is refused, with the reason. */
    var communityError by mutableStateOf<String?>(null)
        private set

    fun installPlugin(manifest: String, onInstalled: () -> Unit) = viewModelScope.launch {
        ready.join()
        communityError = null
        try {
            settings = Core.installPlugin(manifest.trim())
            applyPlugins()
            message = "Plugin installed"
            onInstalled()
        } catch (e: CoreException) {
            communityError = e.message
        }
    }

    fun removePlugin(id: String) = io {
        settings = Core.removePlugin(id)
        applyPlugins()
    }

    /** Community plugins run in sandboxes (plugins/PluginSandboxes.kt); created on first use. */
    private var sandboxHost by mutableStateOf<dev.foulfoxhacks.betteryts.music.plugins.PluginSandboxes?>(null)
    val sandboxes: dev.foulfoxhacks.betteryts.music.plugins.PluginSandboxes
        get() = sandboxHost ?: dev.foulfoxhacks.betteryts.music.plugins.PluginSandboxes(getApplication()).also { sandboxHost = it }

    /** Player panels from community plugins (empty until one runs). */
    val pluginPanels: Map<String, String> get() = sandboxHost?.panels ?: emptyMap()
    val sandboxErrors: Map<String, String> get() = sandboxHost?.errors ?: emptyMap()

    override fun onCleared() {
        sandboxHost?.destroy()
        super.onCleared()
    }
    private var lastNowPlayingAt = 0L

    /** Brings the theme (Look plugins) and the sandboxes (Player plugins) in line with settings. */
    private fun applyPlugins() {
        Brand.palette = withLook(Palettes.byId(settings.theme))
        val active = settings.activeCommunity
        if (active.any { it.code.isNotBlank() } || lastNowPlayingAt != 0L) {
            sandboxes.sync(active)
            sendNowPlaying(force = true)
        }
    }

    // ---- Personalize: accent colour, colours from the album art, start tab, autoplay ----

    // ---- Customize: style, layout, Home sections, tabs, song menu, custom theme ----

    init {
        Brand.look = dev.foulfoxhacks.betteryts.music.ui.theme.Look.load(look)
    }

    /** Changes the Look, saves it and re-applies the palette. */
    fun updateLook(change: (dev.foulfoxhacks.betteryts.music.ui.theme.Look) -> dev.foulfoxhacks.betteryts.music.ui.theme.Look) {
        val next = change(Brand.look)
        Brand.look = next
        next.save(look)
        Brand.palette = withLook(Palettes.byId(settings.theme))
    }

    /** Saves a custom theme from the editor and switches to it (refused if it's hard to read). */
    fun saveCustomTheme(theme: dev.foulfoxhacks.betteryts.music.ui.theme.CustomTheme): Boolean {
        dev.foulfoxhacks.betteryts.music.ui.theme.contrastProblem(theme.palette())?.let {
            message = it
            return false
        }
        updateLook { it.copy(custom = theme, useCustom = true) }
        message = "Theme “${theme.name}” applied"
        return true
    }

    fun importTheme(text: String) {
        dev.foulfoxhacks.betteryts.music.ui.theme.CustomTheme.parse(text)
            .onSuccess { saveCustomTheme(it) }
            .onFailure { message = it.message ?: "That theme couldn't be read" }
    }

    /** A chosen accent (ARGB), or null for the theme's own. */
    var accent by mutableStateOf<Long?>(look.getLong("accent", 0L).takeIf { it != 0L })
        private set

    /** The accent follows the cover of what's playing. */
    var albumColors by mutableStateOf(look.getBoolean("albumColors", false))
        private set
    private var albumAccent: Long? = null

    var startTab by mutableStateOf(tab)
        private set

    /** When the queue runs out, keep going with a station (YouTube Music's autoplay). */
    var autoplay by mutableStateOf(look.getBoolean("autoplay", true))
        private set

    fun chooseAccent(argb: Long?) {
        accent = argb
        look.edit().putLong("accent", argb ?: 0L).apply()
        Brand.palette = withLook(Palettes.byId(settings.theme))
    }

    fun updateAlbumColors(on: Boolean) {
        albumColors = on
        look.edit().putBoolean("albumColors", on).apply()
        if (!on) albumAccent = null
        Brand.palette = withLook(Palettes.byId(settings.theme))
        nowPlaying?.let(::updateAlbumColor)
    }

    fun chooseStartTab(t: Tab) {
        startTab = t
        look.edit().putString("startTab", t.name).apply()
    }

    fun updateAutoplay(on: Boolean) {
        autoplay = on
        look.edit().putBoolean("autoplay", on).apply()
        message = if (on) "Autoplay on: a station keeps the music going" else "Autoplay off: playback stops when the queue ends"
    }

    private fun updateAlbumColor(t: Track) {
        if (!albumColors) return
        viewModelScope.launch {
            val img = ImageCache.load(t.art) ?: return@launch
            val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { dev.foulfoxhacks.betteryts.music.ui.theme.AlbumColor.of(img) }.getOrNull() }
            if (nowPlaying?.id != t.id) return@launch
            albumAccent = c
            Brand.palette = withLook(Palettes.byId(settings.theme))
        }
    }

    /** Active Look plugins' colours over a theme; later plugins win. Colours were checked by the core. */
    private fun withLook(theme: dev.foulfoxhacks.betteryts.music.ui.theme.Palette): dev.foulfoxhacks.betteryts.music.ui.theme.Palette {
        fun color(hex: String?) = hex?.let { runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        val chosen = Brand.look.custom?.takeIf { Brand.look.useCustom }?.palette() ?: theme
        val tinted = Palettes.withAccent(chosen, (if (albumColors) albumAccent else null) ?: accent.takeIf { !Brand.look.useCustom })
        // Glass: cards and bars let the ambient backdrop show through.
        val base = if (Brand.look.style == "glass") tinted.copy(surface = tinted.surface.copy(alpha = 0.62f), raised = tinted.raised.copy(alpha = 0.92f)) else tinted
        var p = base
        for (plugin in settings.activeCommunity.filter { "palette" in it.permissions }) {
            color(plugin.palette["accent"])?.let { p = p.copy(accent = it, lineAccent = it.copy(alpha = 0.26f)) }
            color(plugin.palette["hot"])?.let { p = p.copy(hot = it) }
        }
        return p
    }

    private fun sendNowPlaying(force: Boolean) {
        val t = nowPlaying ?: return
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastNowPlayingAt < 5_000) return
        lastNowPlayingAt = now
        sandboxes.nowPlaying(t.id, t.title, t.artist, p.position, p.duration, p.playing)
    }

    /** In-app updates (sideload build): checks every few hours, downloads, verifies, installs. */
    val updater = dev.foulfoxhacks.betteryts.music.update.Updater(app).also { dev.foulfoxhacks.betteryts.music.update.Updater.current = it }

    fun checkForUpdate() = viewModelScope.launch { updater.check() }

    fun installUpdate() = viewModelScope.launch { updater.install() }

    /** Play Store builds leave out background play (it goes beyond YouTube's embed terms). */
    fun pluginOn(id: String) = settings.on(id) && !(id == "background" && dev.foulfoxhacks.betteryts.music.BuildConfig.PLAY_STORE)

    /** Library › Send to a VRChat world. */
    data class WorldSend(
        val prefill: String = "",
        val checkedCode: String? = null,
        val service: String = "youtube",
        val capabilities: List<String> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
        val sent: String? = null,
        val preview: dev.foulfoxhacks.betteryts.music.core.WorldPreview? = null,
    )

    var worldSend by mutableStateOf(WorldSend())
        private set

    /** From a yts.foulfoxhacks.dev/inworld/…/auth?code=… link: open the Library with the code ready. */
    fun openWorldSend(code: String?) {
        worldSend = WorldSend(prefill = code.orEmpty())
        playerExpanded = false
        selectTab(Tab.Library)
        if (!code.isNullOrBlank()) checkWorldCode(code)
    }

    fun resetWorldSend() {
        worldSend = WorldSend()
    }

    fun checkWorldCode(code: String) = viewModelScope.launch {
        ready.join()
        worldSend = worldSend.copy(busy = true, error = null, prefill = code)
        worldSend = try {
            val w = Core.inworldCheck(code)
            worldSend.copy(busy = false, checkedCode = w.code, service = w.service, capabilities = w.capabilities, prefill = w.code)
        } catch (e: CoreException) {
            worldSend.copy(busy = false, checkedCode = null, error = e.message)
        }
    }

    private var worldPreviewJob: Job? = null

    /** Recounts what a send would contain whenever the selection changes. */
    fun previewWorldSend(playlistIds: List<String>, liked: Boolean, queue: Boolean) {
        worldPreviewJob?.cancel()
        worldPreviewJob = viewModelScope.launch {
            ready.join()
            delay(150)
            worldSend = try {
                worldSend.copy(preview = Core.inworldPreview(playlistIds, liked, queue))
            } catch (e: CoreException) {
                worldSend.copy(preview = null, error = e.message)
            }
        }
    }

    fun forgetWorldSends() = io {
        library = Core.inworldForget()
        message = "Send history cleared"
    }

    fun sendToWorld(playlistIds: List<String>, liked: Boolean, save: Boolean, queue: Boolean = false) = viewModelScope.launch {
        ready.join()
        val code = worldSend.checkedCode ?: return@launch
        worldSend = worldSend.copy(busy = true, error = null)
        worldSend = try {
            val (lists, songs, lib) = Core.inworldSend(code, worldSend.service, playlistIds, liked, save, queue)
            lib?.let { library = it }
            val kept = if (save) " Saved for your next visit for up to 30 days; Unlink in the world deletes it." else " Just this visit: nothing is kept once the code expires."
            worldSend.copy(busy = false, sent = "Sent $lists playlist${if (lists == 1) "" else "s"} ($songs songs). Look at the world: they show up within a few seconds.$kept")
        } catch (e: CoreException) {
            worldSend.copy(busy = false, error = e.message)
        }
    }

    /** Settings › Backup: write the backup to a file the user picked (Storage Access Framework). */
    fun exportBackup(uri: android.net.Uri) = io {
        val app = getApplication<Application>()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""
        val text = Core.backupExport(version ?: "")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw CoreException("Couldn't write the file")
        }
        message = "Backup saved. It also imports on the website (Settings › Backup)."
    }

    /** A backup file that has been read and checked, waiting for the user to confirm. */
    data class PendingBackup(val text: String, val preview: dev.foulfoxhacks.betteryts.music.core.BackupPreview, val replace: Boolean)

    var pendingBackup by mutableStateOf<PendingBackup?>(null)
        private set

    /** Reads the file and shows what importing it would change; nothing changes until [confirmBackup]. */
    fun importBackup(uri: android.net.Uri) = io {
        val app = getApplication<Application>()
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 5_000_000 }?.toString(Charsets.UTF_8) }
        } ?: throw CoreException("That file is too big to be a BetterYTS backup")
        pendingBackup = PendingBackup(text, Core.backupPreview(text, replace = false), replace = false)
    }

    /** Switches the pending import between merge and replace (and shows what each would do). */
    fun setBackupReplace(replace: Boolean) {
        val p = pendingBackup ?: return
        io { pendingBackup = p.copy(preview = Core.backupPreview(p.text, replace), replace = replace) }
    }

    fun cancelBackup() {
        pendingBackup = null
    }

    fun confirmBackup() {
        val p = pendingBackup ?: return
        pendingBackup = null
        applyBackup(p.text, p.replace)
    }

    private fun applyBackup(text: String, replace: Boolean) = io {
        val (sum, newSettings) = Core.backupImport(text, replace)
        settings = newSettings
        applyPlugins()
        library = Core.library()
        refreshFeed()
        smartPlaylists = Core.smartList()
        val parts = listOf("favoritesAdded" to "liked songs", "playlistsAdded" to "playlists", "playlistsUpdated" to "playlists updated", "artistsAdded" to "artists", "historyAdded" to "history", "pluginsInstalled" to "plugins", "smartAdded" to "smart playlists")
            .mapNotNull { (k, label) -> sum.optInt(k).takeIf { it > 0 }?.let { "$it $label" } }
        message = if (parts.isEmpty()) "Backup checked: nothing new to add" else "Imported " + parts.joinToString(", ")
    }

    /** Settings › Hidden: comma- or line-separated lists, cleaned by the core. */
    fun setHidden(channels: String, words: String) = io {
        fun split(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        settings = Core.setHidden(split(channels), split(words))
        message = "Hidden lists saved"
        refreshFeed()
    }

    fun toggleHideArtist(artist: Artist) = io {
        settings = Core.toggleHiddenArtist(artist.name, artist.id)
        message = if (settings.hidesArtist(artist.name, artist.id)) "${artist.name} is hidden from search, radio and your feed" else "${artist.name} is shown again"
        refreshFeed()
    }

    /** Per-song plugin data, keyed by video ID. */
    private val segmentsFor = HashMap<String, List<Core.Segment>>()
    private var skippedUntil = 0f
    var dislikes by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    val lyrics = mutableStateMapOf<String, Load<Core.Lyrics?>>()

    private fun onTrackChanged(t: Track) {
        skippedUntil = 0f
        updateAlbumColor(t)
        if (settings.activeCommunity.isNotEmpty()) roomMain.post { sendNowPlaying(force = true) }
        dislikes = null
        // SponsorBlock and dislike counts are YouTube data; device files have neither.
        if (t.isLocal) return
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
        if (room != null) roomDriftTick(id, seconds)
        trackListening(id, seconds)
        if (settings.activeCommunity.isNotEmpty()) sendNowPlaying(force = false)
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
        val label = settings.sponsorCatalog.firstOrNull { it.first == seg.category }?.second?.lowercase()?.removeSuffix("s") ?: "non-music part"
        skipLog.add(0, Triple(queue.current?.title ?: id, seg.category, seg.end - seconds))
        while (skipLog.size > 50) skipLog.removeAt(skipLog.lastIndex)
        if (duration > 0 && seg.end >= duration - 1f) {
            message = "Skipped the end ($label)"
            onEnded(id)
        } else {
            player?.seek(seg.end)
            say(if (seg.start < 1f) "Skipped the start ($label)" else "Skipped: $label", "Undo" to { player?.seek(seconds); Unit })
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
        // Mood searches on YouTube are dominated by hour-long mixes and live radio streams, which
        // the shelf filter rejects (they came back empty), so ask for short videos to begin with.
        val picks = listOf(
            when (hour) {
                in 5..11 -> "Morning energy" to "upbeat morning music duration:short"
                in 12..17 -> "Afternoon focus" to "focus music instrumental duration:short"
                in 18..22 -> "Evening chill" to "chill evening music duration:short"
                else -> "Late night" to "late night lofi duration:short"
            },
            "Lo-fi beats" to "lofi hip hop song -mix -radio duration:short",
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
                    // Still nothing (YouTube's results drift): one retry for single songs, without mixes.
                    if (songs.isEmpty()) {
                        val base = q.replace(Regex("""\s+(-\S+|\w+:\S+)"""), "").trim()
                        songs = Core.search("$base song -mix -radio -live duration:short", "songs").items.filterIsInstance<Item.TrackItem>().map { it.track }.filter(::isSingleSong)
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
            // Radio needs YouTube songs, so mixes grow from those only.
            val history = library.history.filterNot { it.isLocal }
            val favorites = library.favorites.filterNot { it.isLocal }
            if (history.isEmpty() && favorites.isEmpty()) return emptyList()
            val out = mutableListOf("Supermix" to (favorites.firstOrNull() ?: history.first()))
            recap.take(4).forEach { (artist, _) -> history.firstOrNull { it.artist == artist }?.let { out += "$artist Mix" to it } }
            return out.distinctBy { it.second.id }
        }

    // ---- Device files: music stored on the phone, played offline ----
    var deviceSongs by mutableStateOf<Load<List<Track>>?>(null)
        private set

    /** Reads the phone's music (needs the audio permission); null until first asked. */
    fun loadDeviceSongs() {
        val ctx = getApplication<Application>()
        if (!DeviceMusic.hasPermission(ctx)) {
            deviceSongs = null
            return
        }
        deviceSongs = Load.Loading
        viewModelScope.launch {
            deviceSongs = try {
                Load.Ready(DeviceMusic.scan(ctx))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't read the music on this phone")
            }
        }
    }

    /** "Analyse tempo & key" for songs on the phone: progress text, or null when idle. */
    var analyzing by mutableStateOf<String?>(null)
        private set

    /** Decodes each device song (in the core) to find its tempo and key, for Smart stations and mixes. */
    fun analyzeDeviceSongs() {
        val songs = (deviceSongs as? Load.Ready)?.value.orEmpty()
        if (songs.isEmpty() || analyzing != null) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            ready.join()
            var done = 0
            var failed = 0
            for (t in songs) {
                analyzing = "Analysing ${done + 1} of ${songs.size}…"
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openFileDescriptor(DeviceMusic.uri(t.id), "r")?.use { pfd ->
                            Core.analyzeFile(t.id, "/proc/self/fd/${pfd.fd}")
                        }
                    }.isSuccess
                }
                if (ok) done++ else failed++
            }
            analyzing = null
            message = "Analysed $done songs${if (failed > 0) " ($failed couldn't be read)" else ""}. Smart stations now use their tempo and key."
        }
    }

    /** Library chip: all, songs, playlists, artists, device or history. */
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

    // ---------------------------------------------------------------------------------------
    // Import from Spotify
    // ---------------------------------------------------------------------------------------

    data class SpotifyImport(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val summary: String? = null,
        val note: String? = null,
        val unsure: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val error: String? = null,
    )

    var spotify by mutableStateOf(SpotifyImport())
    private var spotifyJob: Job? = null

    /** Reads the playlist, matches 4 songs at a time (with progress), saves it as a playlist. */
    // ---- Sign in with Spotify: pick playlists (and Liked Songs); the token lives only in memory ----
    data class SpotifyAccountState(val lists: List<Core.SpotifyRemoteList> = emptyList(), val busy: String? = null, val error: String? = null)

    var spotifyAccount by mutableStateOf(SpotifyAccountState())
        private set
    private var spotifyToken: String? = null
    private val oauthPrefs by lazy { getApplication<Application>().getSharedPreferences("spotify_oauth", android.content.Context.MODE_PRIVATE) }

    /** Opens Spotify's consent page (through the BetterYTS Worker, which adds the client ID). */
    fun spotifySignInIntent(): android.content.Intent {
        fun rnd(n: Int) = android.util.Base64.encodeToString(ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val state = rnd(24)
        val verifier = rnd(48)
        oauthPrefs.edit().putString("state", state).putString("verifier", verifier).putLong("at", System.currentTimeMillis()).apply()
        val challenge = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        val uri = android.net.Uri.parse("${RoomClient.BASE}/auth/spotify/start").buildUpon().appendQueryParameter("state", state).appendQueryParameter("code_challenge", challenge).build()
        return android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
    }

    /** betterytsmusic://oauth/spotify?code=…&state=… */
    fun finishSpotifySignIn(uri: android.net.Uri) = viewModelScope.launch {
        ready.join()
        selectTab(Tab.Library)
        val state = oauthPrefs.getString("state", null)
        val verifier = oauthPrefs.getString("verifier", null)
        val fresh = System.currentTimeMillis() - oauthPrefs.getLong("at", 0) < 15 * 60_000
        oauthPrefs.edit().clear().apply()
        spotifyAccount = SpotifyAccountState(busy = "Signing in to Spotify…")
        spotifyAccount = try {
            uri.getQueryParameter("error")?.let { throw CoreException(if (it == "access_denied") "Sign-in cancelled" else "Spotify sign-in failed ($it)") }
            if (state == null || verifier == null || !fresh || uri.getQueryParameter("state") != state) throw CoreException("That sign-in link expired. Try again.")
            val code = uri.getQueryParameter("code") ?: throw CoreException("Spotify sign-in failed")
            val token = Core.spotifyExchange(Core.spotifyClientId(), "${RoomClient.BASE}/auth/spotify/callback", code, verifier)
            spotifyToken = token
            SpotifyAccountState(lists = Core.spotifyPlaylists(token))
        } catch (e: CoreException) {
            SpotifyAccountState(error = e.message)
        }
    }

    /** Imports the picked Spotify playlists one after another (each becomes a library playlist). */
    fun importSpotifyPicked(ids: List<String>) {
        val token = spotifyToken ?: return
        if (spotify.running) return
        val picked = spotifyAccount.lists.filter { it.id in ids }
        spotifyJob = viewModelScope.launch {
            for (l in picked) {
                val list = try {
                    Core.spotifyTracks(token, l.id, l.name)
                } catch (e: CoreException) {
                    spotify = SpotifyImport(error = "Couldn't read “${l.name}”: ${e.message}")
                    return@launch
                }
                importSpotifyList(list)
            }
        }
    }

    fun importFromSpotify(link: String) {
        if (spotify.running || link.isBlank()) return
        spotifyJob = viewModelScope.launch {
            val list = try {
                ready.join()
                Core.spotifyRead(link.trim())
            } catch (e: CoreException) {
                spotify = SpotifyImport(error = "Couldn't import: ${e.message}")
                return@launch
            }
            importSpotifyList(list)
        }
    }

    /** Matches a Spotify list to YouTube and saves it as a playlist. */
    private suspend fun importSpotifyList(list: Core.SpotifyList) {
            ready.join()
            spotify = SpotifyImport(running = true)
            try {
                spotify = spotify.copy(total = list.tracks.size)
                val permits = Semaphore(4)
                val matches = coroutineScope {
                    list.tracks.map { t ->
                        async {
                            permits.withPermit {
                                val m = try {
                                    Core.spotifyMatch(t)
                                } catch (_: CoreException) {
                                    null
                                }
                                spotify = spotify.copy(done = spotify.done + 1) // resumes on Main: no races
                                t to m
                            }
                        }
                    }.awaitAll()
                }
                val tracks = matches.mapNotNull { (_, m) -> m?.track }
                val unsure = matches.filter { (_, m) -> m?.track != null && m.confidence == "low" }.map { (t, m) -> "${t.label} → ${m?.videoTitle ?: "?"}" }
                val missing = matches.filter { (_, m) -> m?.track == null }.map { (t, _) -> t.label }
                if (tracks.isEmpty()) throw CoreException("None of the songs could be found on YouTube")
                val (lib, created) = Core.createPlaylist(list.name, tracks)
                library = lib
                if (created == null || created == JSONObject.NULL) throw CoreException("You've reached the playlist limit. Delete one and try again.")
                spotify = SpotifyImport(
                    summary = "Saved “${list.name}”: ${tracks.size} of ${list.tracks.size} songs found.",
                    note = if (list.mayBeTruncated) "Spotify only shows the first 100 songs of long playlists, so later songs weren't imported." else null,
                    unsure = unsure,
                    missing = missing,
                )
                message = "Imported “${list.name}” from Spotify"
            } catch (e: CancellationException) {
                spotify = SpotifyImport(error = "Import cancelled.")
                throw e
            } catch (e: CoreException) {
                spotify = SpotifyImport(error = "Couldn't import: ${e.message}")
            }
    }

    fun cancelSpotifyImport() {
        spotifyJob?.cancel()
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
        get() = when {
            room != null -> roomState?.video?.let { Track(it.id, it.title, "Listening together") }
            lan?.role == "member" -> lanTrack
            else -> queue.current
        }

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
        roomMain.removeCallbacks(roomStart)
        endNudge()
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

    private val roomMain = android.os.Handler(android.os.Looper.getMainLooper())
    private val roomStart = Runnable { player?.play() }
    /** +1 while playing fast to catch up, −1 while playing slow, 0 at normal speed. */
    private var nudgeDir = 0

    private fun endNudge() {
        if (nudgeDir != 0) player?.endNudge()
        nudgeDir = 0
    }

    /**
     * Makes the local player match the room. The server schedules starts a moment ahead
     * (state.at in the future), so every member waits for the same instant before playing.
     */
    private fun applyRoomState(s: RoomState) {
        val p = player ?: return
        val r = room ?: return
        roomMain.removeCallbacks(roomStart)
        endNudge()
        val v = s.video
        if (v == null) {
            p.pause()
            return
        }
        val wait = r.msUntil(s)
        val scheduled = s.playing && wait > 30
        if (p.currentId != v.id) {
            if (scheduled) {
                p.load(v.id, s.position, autoplay = false)
                roomMain.postDelayed(roomStart, wait)
            } else {
                p.load(v.id, r.positionOf(s), autoplay = s.playing)
            }
            onTrackChanged(Track(v.id, v.title, "Listening together"))
            return
        }
        if (scheduled && !p.playing) {
            p.seek(s.position)
            roomMain.postDelayed(roomStart, wait)
            return
        }
        val target = r.positionOf(s)
        if (kotlin.math.abs(p.position - target) > 1f) p.seek(target)
        if (s.playing && !p.playing) p.play() else if (!s.playing && p.playing) p.pause()
    }

    /** Small drift is fixed by playing at 1.25×/0.75× for a moment; only big jumps seek. */
    private fun roomDriftTick(id: String, seconds: Float) {
        val r = room ?: return
        val s = roomState ?: return
        val p = player ?: return
        if (!s.playing || s.video?.id != id || !p.playing || r.msUntil(s) > 0) {
            endNudge()
            return
        }
        val target = r.positionOf(s)
        val drift = target - seconds // > 0: we are behind
        roomDrift = drift
        val off = kotlin.math.abs(drift)
        if (off > 2f) {
            endNudge()
            p.seek(target)
            return
        }
        val dir = if (drift > 0) 1 else -1
        if (nudgeDir != 0) {
            if (off < 0.08f || dir != nudgeDir) endNudge()
        } else if (off > 0.25f) {
            nudgeDir = dir
            p.nudge(if (dir > 0) 1.25f else 0.75f)
        }
    }

    /** Last measured difference from the room's position (seconds; > 0 means behind). */
    var roomDrift by mutableFloatStateOf(0f)
        private set

    // ---------------------------------------------------------------------------------------
    // Diagnostics (Settings › Diagnostics): what the player and the network did, shareable
    // without tokens, room codes, searches or what you listened to.
    // ---------------------------------------------------------------------------------------

    var networkDiagnostics by mutableStateOf<dev.foulfoxhacks.betteryts.music.core.NetworkDiagnostics?>(null)
        private set

    val playerForDiagnostics: PlayerController? get() = player

    fun openDiagnostics() {
        playerExpanded = false
        stack.add(Route.DiagnosticsRoute)
        loadDiagnostics()
    }

    fun loadDiagnostics() = io { networkDiagnostics = Core.diagnostics() }

    fun clearDiagnostics() = io {
        Core.clearDiagnostics()
        networkDiagnostics = Core.diagnostics()
    }

    /** A plain-text report for a bug report. Leaves out song IDs, searches, tokens and room codes. */
    fun diagnosticsReport(): String {
        val app = getApplication<Application>()
        val p = player
        val n = networkDiagnostics
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val webview = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(app)?.versionName }.getOrNull() ?: "unknown"
        return buildString {
            appendLine("BetterYTS Music diagnostics")
            appendLine("App ${version} (${if (dev.foulfoxhacks.betteryts.music.BuildConfig.PLAY_STORE) "play" else "sideload"}), core ${n?.coreVersion ?: "?"}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), WebView $webview")
            if (p != null) {
                val m = p.machine
                appendLine("Player: ${m.state}, ${if (p.currentId == null) "nothing loaded" else if (DeviceMusic.isLocal(p.currentId)) "device file" else "YouTube embed"}, speed ${p.speed}x, PiP ${inPip}")
                appendLine("Time to ready: ${m.lastTimeToReadyMs?.let { "$it ms" } ?: "n/a"} · rebuffers: ${m.rebufferCount} (${m.rebufferMs} ms) · last error: ${m.lastError ?: "none"}")
                appendLine("Background play: ${pluginOn("background")} · resume: $resumeEnabled · queue: ${queue.tracks.size} songs${if (station != null) " · on a station (${station?.mix})" else ""}")
                if (room != null) appendLine("Room: connected=${roomStatus.isEmpty()} host=$roomIsHost drift=${"%.2f".format(roomDrift)}s")
                appendLine("Recent player changes (newest first):")
                val now = android.os.SystemClock.elapsedRealtime()
                m.transitions.take(25).forEach { t -> appendLine("  -${(now - t.at) / 1000}s ${t.from} → ${t.to} (${t.reason})${if (t.expected) "" else " [unexpected]"}") }
            }
            if (n != null) {
                appendLine("Network: ${n.requests} requests, ${n.failures} failed, ${n.cacheHits} from cache, ${n.retries} retries, median ${n.medianMs} ms, slowest ${n.maxMs} ms")
                n.lastError?.let { appendLine("Last network error: $it") }
                n.recent.take(25).forEach { r -> appendLine("  ${r.method} ${r.route} ${if (r.cached) "cache" else "${r.status} ${r.ms}ms"}${if (r.retries > 0) " retries=${r.retries}" else ""}${r.error?.let { " $it" } ?: ""}${r.requestId?.let { " id=$it" } ?: ""}") }
            }
        }
    }

    // Last, so every property above is initialised first (Kotlin runs initialisers in order).
    init {
        loadShelves()
        viewModelScope.launch {
            ready.join()
            try {
                stations = Core.stations()
                // The restored queue was riding a station: keep riding it.
                if (queue.current != null) station = stations.current
                smartPlaylists = Core.smartList()
            } catch (_: CoreException) {
            }
            updater.check(auto = true)
        }
    }
}
