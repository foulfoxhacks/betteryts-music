package dev.foulfoxhacks.betteryts.music.player

import java.util.concurrent.CopyOnWriteArraySet

/**
 * The meeting point between the app (which owns the player) and [PlaybackService] (which owns
 * the media notification, lock-screen controls and MediaSession). The app pushes what's playing;
 * the service calls back through [controls] when a notification, headset or car button is used.
 */
object PlaybackHub {
    interface Controls {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seekTo(seconds: Float)
    }

    data class Now(
        val id: String,
        val title: String,
        val artist: String,
        val art: String?,
        val playing: Boolean,
        val position: Float,
        val duration: Float,
        val canSkip: Boolean,
    )

    @Volatile
    var controls: Controls? = null

    @Volatile
    var now: Now? = null
        private set

    private val listeners = CopyOnWriteArraySet<(Now?) -> Unit>()

    fun listen(l: (Now?) -> Unit) {
        listeners.add(l)
    }

    fun unlisten(l: (Now?) -> Unit) {
        listeners.remove(l)
    }

    /** Called often (twice a second while playing); listeners only hear real changes. */
    fun update(next: Now?) {
        val prev = now
        now = next
        // Normal playback moving the position forward isn't news (the session extrapolates it);
        // a new song, play/pause, or a seek (a jump of more than a few seconds) is.
        val routine = prev != null && next != null &&
            prev.copy(position = 0f) == next.copy(position = 0f) &&
            next.position >= prev.position && next.position - prev.position < 3f
        if (!routine) listeners.forEach { it(next) }
    }
}
