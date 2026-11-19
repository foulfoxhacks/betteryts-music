package dev.foulfoxhacks.betteryts.music.core

import org.json.JSONArray
import org.json.JSONObject

// Kotlin mirrors of the Rust core's models (core/src/model.rs, queue.rs, library.rs).

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

internal inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let(f)?.let(out::add)
    return out
}

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val durationSeconds: Int? = null,
    val live: Boolean = false,
    /** "8.8M plays", when YouTube gave a view count. */
    val plays: String? = null,
) {
    /** Cover art. YouTube's 320×180 thumbnail always exists; the UI crops it square. */
    val art: String get() = thumbnail ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    /** Bigger art for the player screen. */
    val largeArt: String get() = thumbnail ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    val subtitle: String get() = listOfNotNull(artist, plays, if (live) "LIVE" else duration).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        artistId?.let { put("artistId", it) }
        thumbnail?.let { put("thumbnail", it) }
        duration?.let { put("duration", it) }
        durationSeconds?.let { put("durationSeconds", it) }
        put("live", live)
        plays?.let { put("plays", it) }
    }

    companion object {
        fun from(o: JSONObject): Track? {
            val id = o.str("id") ?: return null
            return Track(
                id = id,
                title = o.str("title") ?: id,
                artist = o.str("artist") ?: "Unknown artist",
                artistId = o.str("artistId"),
                thumbnail = o.str("thumbnail"),
                duration = o.str("duration"),
                durationSeconds = if (o.has("durationSeconds") && !o.isNull("durationSeconds")) o.optInt("durationSeconds") else null,
                live = o.optBoolean("live"),
                plays = o.str("plays"),
            )
        }

        fun list(a: JSONArray?): List<Track> = a.mapObjects(::from)
        fun array(tracks: List<Track>): JSONArray = JSONArray().apply { tracks.forEach { put(it.toJson()) } }
    }
}

data class Collection(
    val id: String,
    val collectionType: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String? = null,
    val firstVideoId: String? = null,
) {
    val isMix get() = collectionType == "mix"

    companion object {
        fun from(o: JSONObject): Collection? {
            val id = o.str("id") ?: return null
            return Collection(
                id = id,
                collectionType = o.str("collectionType") ?: "playlist",
                title = o.str("title") ?: "Playlist",
                subtitle = o.str("subtitle") ?: "",
                thumbnail = o.str("thumbnail"),
                firstVideoId = o.str("firstVideoId"),
            )
        }
    }
}

data class Artist(
    val id: String,
    val name: String,
    val handle: String? = null,
    val thumbnail: String? = null,
    val subtitle: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        handle?.let { put("handle", it) }
        thumbnail?.let { put("thumbnail", it) }
        subtitle?.let { put("subtitle", it) }
    }

    companion object {
        fun from(o: JSONObject): Artist? {
            val id = o.str("id") ?: return null
            return Artist(id, o.str("name") ?: "Unknown artist", o.str("handle"), o.str("thumbnail"), o.str("subtitle"))
        }
    }
}

sealed interface Item {
    data class TrackItem(val track: Track) : Item
    data class CollectionItem(val collection: Collection) : Item
    data class ArtistItem(val artist: Artist) : Item

    companion object {
        fun from(o: JSONObject): Item? = when (o.str("itemType")) {
            "track" -> Track.from(o)?.let(::TrackItem)
            "collection" -> Collection.from(o)?.let(::CollectionItem)
            "artist" -> Artist.from(o)?.let(::ArtistItem)
            else -> null
        }
    }
}

data class Page(val items: List<Item>, val next: String?) {
    companion object {
        fun from(o: JSONObject) = Page(o.optJSONArray("items").mapObjects(Item::from), o.str("next"))
    }
}

data class CollectionPage(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val tracks: List<Track>,
    val next: String?,
) {
    companion object {
        fun from(o: JSONObject) = CollectionPage(
            id = o.str("id") ?: "",
            title = o.str("title") ?: "Playlist",
            subtitle = o.str("subtitle") ?: "",
            thumbnail = o.str("thumbnail"),
            tracks = Track.list(o.optJSONArray("tracks")),
            next = o.str("next"),
        )
    }
}

data class ArtistPage(
    val artist: Artist,
    val banner: String?,
    val description: String?,
    val tracks: List<Track>,
    val next: String?,
) {
    companion object {
        fun from(o: JSONObject) = ArtistPage(
            artist = o.optJSONObject("artist")?.let(Artist::from) ?: Artist("", "Unknown artist"),
            banner = o.str("banner"),
            description = o.str("description"),
            tracks = Track.list(o.optJSONArray("tracks")),
            next = o.str("next"),
        )
    }
}

enum class Repeat { Off, All, One }

data class QueueState(
    val tracks: List<Track> = emptyList(),
    val position: Int = -1,
    val current: Track? = null,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** Set by queue.next when there was nothing left to play. */
    val ended: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject) = QueueState(
            tracks = Track.list(o.optJSONArray("tracks")),
            position = o.optInt("position", -1),
            current = o.optJSONObject("current")?.let(Track::from),
            shuffle = o.optBoolean("shuffle"),
            repeat = when (o.str("repeat")) {
                "all" -> Repeat.All
                "one" -> Repeat.One
                else -> Repeat.Off
            },
            hasNext = o.optBoolean("hasNext"),
            hasPrevious = o.optBoolean("hasPrevious"),
            ended = o.optBoolean("ended"),
        )
    }
}

data class UserPlaylist(val id: String, val name: String, val tracks: List<Track>, val createdAt: Long, val source: String? = null) {
    val fromYouTube get() = source?.startsWith("youtube:") == true

    companion object {
        fun from(o: JSONObject): UserPlaylist? {
            val id = o.str("id") ?: return null
            return UserPlaylist(id, o.str("name") ?: "My playlist", Track.list(o.optJSONArray("tracks")), o.optLong("createdAt"), o.str("source"))
        }
    }
}

data class Library(
    val favorites: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val followed: List<Artist> = emptyList(),
) {
    private val favoriteIds: Set<String> by lazy { favorites.mapTo(HashSet()) { it.id } }
    private val followedIds: Set<String> by lazy { followed.flatMapTo(HashSet()) { listOfNotNull(it.id, it.handle) } }

    fun isFavorite(id: String) = id in favoriteIds
    fun isFollowing(id: String?) = id != null && id in followedIds
    fun playlist(id: String) = playlists.firstOrNull { it.id == id }
    val isEmpty get() = favorites.isEmpty() && history.isEmpty() && playlists.isEmpty() && followed.isEmpty()

    companion object {
        fun from(o: JSONObject) = Library(
            favorites = Track.list(o.optJSONArray("favorites")),
            history = Track.list(o.optJSONArray("history")),
            playlists = o.optJSONArray("playlists").mapObjects(UserPlaylist::from),
            followed = o.optJSONArray("followed").mapObjects(Artist::from),
        )
    }
}

sealed interface Link {
    data class Video(val id: String) : Link
    data class Playlist(val id: String, val video: String?) : Link
    data class ArtistLink(val id: String) : Link
    data class Search(val query: String) : Link

    companion object {
        fun from(o: JSONObject?): Link? = when (o?.str("linkType")) {
            "video" -> o.str("id")?.let(::Video)
            "playlist" -> o.str("id")?.let { Playlist(it, o.str("video")) }
            "artist" -> o.str("id")?.let(::ArtistLink)
            "search" -> o.str("query")?.let(::Search)
            else -> null
        }
    }
}
