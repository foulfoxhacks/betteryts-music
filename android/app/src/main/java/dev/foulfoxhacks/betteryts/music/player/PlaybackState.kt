package dev.foulfoxhacks.betteryts.music.player

import android.os.SystemClock

/**
 * The player's lifecycle as one explicit state, with every change recorded (reason + monotonic
 * time) for Settings › Diagnostics. Player callbacks are facts, so a change is never refused; one
 * the model doesn't expect is still applied and marked, which is what a bug report needs to show.
 */
enum class PlayState {
    Idle, Loading, Ready, Playing, Paused, Buffering, Seeking, Ended, Blocked, Failed, Recovering;

    /** States in which the app may say "playing" (the notification, the play button). */
    val audible get() = this == Playing || this == Buffering || this == Seeking
}

data class Transition(
    /** SystemClock.elapsedRealtime(): monotonic, unaffected by clock changes. */
    val at: Long,
    val from: PlayState,
    val to: PlayState,
    val reason: String,
    val expected: Boolean,
)

class PlaybackStateMachine(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    var state: PlayState = PlayState.Idle
        private set

    private val log = ArrayDeque<Transition>()

    /** Newest first. */
    val transitions: List<Transition> get() = log.reversed()

    // ---- buffering and start-up metrics (local only) ----
    private var loadStartedAt = 0L
    private var bufferingSince = 0L

    /** Time from asking for a song to it playing, for the last song that started. */
    var lastTimeToReadyMs: Long? = null
        private set
    var rebufferCount = 0
        private set
    var rebufferMs = 0L
        private set
    var lastError: String? = null
        private set

    /** Moves to [to]. Repeating the current state is ignored (players report some states twice). */
    fun move(to: PlayState, reason: String): Boolean {
        val from = state
        if (from == to) return false
        val now = clock()
        if (to == PlayState.Loading) {
            loadStartedAt = now
        }
        if (to == PlayState.Playing && loadStartedAt > 0) {
            lastTimeToReadyMs = now - loadStartedAt
            loadStartedAt = 0
        }
        // A stall after playback began counts as a rebuffer; buffering while loading doesn't.
        if (to == PlayState.Buffering && loadStartedAt == 0L && from == PlayState.Playing) {
            rebufferCount++
            bufferingSince = now
        }
        if (from == PlayState.Buffering && bufferingSince > 0) {
            rebufferMs += now - bufferingSince
            bufferingSince = 0
        }
        if (to == PlayState.Failed || to == PlayState.Blocked) lastError = reason
        state = to
        log.addLast(Transition(now, from, to, reason, to in ALLOWED[from].orEmpty()))
        while (log.size > MAX_LOG) log.removeFirst()
        return true
    }

    companion object {
        const val MAX_LOG = 60

        private val ALL = PlayState.entries.toSet()

        /** The transitions the model expects. Anything else is recorded as unexpected. */
        val ALLOWED: Map<PlayState, Set<PlayState>> = mapOf(
            PlayState.Idle to setOf(PlayState.Loading),
            PlayState.Loading to setOf(PlayState.Ready, PlayState.Playing, PlayState.Paused, PlayState.Buffering, PlayState.Blocked, PlayState.Failed, PlayState.Idle, PlayState.Loading),
            PlayState.Ready to setOf(PlayState.Playing, PlayState.Buffering, PlayState.Loading, PlayState.Seeking, PlayState.Paused, PlayState.Idle),
            PlayState.Playing to setOf(PlayState.Paused, PlayState.Buffering, PlayState.Seeking, PlayState.Ended, PlayState.Loading, PlayState.Failed, PlayState.Blocked, PlayState.Idle, PlayState.Recovering),
            PlayState.Paused to setOf(PlayState.Playing, PlayState.Buffering, PlayState.Seeking, PlayState.Loading, PlayState.Idle, PlayState.Ended),
            PlayState.Buffering to setOf(PlayState.Playing, PlayState.Paused, PlayState.Seeking, PlayState.Failed, PlayState.Loading, PlayState.Ended, PlayState.Idle, PlayState.Recovering, PlayState.Blocked),
            PlayState.Seeking to setOf(PlayState.Playing, PlayState.Paused, PlayState.Buffering, PlayState.Ended, PlayState.Loading, PlayState.Idle),
            PlayState.Ended to setOf(PlayState.Loading, PlayState.Playing, PlayState.Seeking, PlayState.Idle, PlayState.Paused),
            PlayState.Blocked to setOf(PlayState.Loading, PlayState.Idle),
            PlayState.Failed to setOf(PlayState.Loading, PlayState.Recovering, PlayState.Idle),
            PlayState.Recovering to ALL,
        )
    }
}
