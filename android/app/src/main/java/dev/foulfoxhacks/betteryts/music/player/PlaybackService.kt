package dev.foulfoxhacks.betteryts.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.foulfoxhacks.betteryts.music.MainActivity
import dev.foulfoxhacks.betteryts.music.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Background play (the "Background play" plugin). Keeps the app alive while music plays with
 * the screen off or in another app, and gives it a media notification, lock-screen controls and
 * a MediaSession, so headset, Bluetooth and car buttons work.
 *
 * Note: playing YouTube's embedded player in the background goes beyond YouTube's embedded-player
 * terms, so builds with this plugin are for sideloading / F-Droid, not the Play Store.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var artFor: String? = null
    private var art: Bitmap? = null
    private var foreground = false

    private val onChange: (PlaybackHub.Now?) -> Unit = { now -> main.post { render(now) } }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls for BetterYTS Music"
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "BetterYTSMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { PlaybackHub.controls?.play() }
                override fun onPause() { PlaybackHub.controls?.pause() }
                override fun onSkipToNext() { PlaybackHub.controls?.next() }
                override fun onSkipToPrevious() { PlaybackHub.controls?.previous() }
                override fun onSeekTo(pos: Long) { PlaybackHub.controls?.seekTo(pos / 1000f) }
                override fun onStop() { PlaybackHub.controls?.pause() }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        PlaybackHub.listen(onChange)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlaybackHub.now?.let { if (it.playing) PlaybackHub.controls?.pause() else PlaybackHub.controls?.play() }
            ACTION_NEXT -> PlaybackHub.controls?.next()
            ACTION_PREVIOUS -> PlaybackHub.controls?.previous()
        }
        render(PlaybackHub.now)
        return START_NOT_STICKY
    }

    private fun render(now: PlaybackHub.Now?) {
        if (now == null) {
            // Android requires startForeground() after startForegroundService(), even if we're
            // about to stop: satisfy it with a placeholder first.
            if (!foreground) {
                val placeholder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_music).setContentTitle("BetterYTS Music").build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIFICATION_ID, placeholder)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        if (artFor != now.id) loadArt(now)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, now.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, now.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, now.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (now.duration * 1000).toLong())
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        if (now.canSkip) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (now.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (now.position * 1000).toLong(), if (now.playing) 1f else 0f)
                .build(),
        )
        val n = notification(now)
        if (now.playing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, n)
            foreground = true
        } else {
            // Paused: the notification stays (and can be swiped away), but Android may stop us.
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    private fun notification(now: PlaybackHub.Now): Notification {
        fun action(icon: Int, label: String, act: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            label,
            PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(now.title)
            .setContentText(now.artist)
            .setLargeIcon(art)
            .setContentIntent(openAppIntent())
            .setOngoing(now.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, 1))
            .addAction(action(if (now.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (now.playing) "Pause" else "Play", ACTION_PLAY_PAUSE, 2))
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun loadArt(now: PlaybackHub.Now) {
        artFor = now.id
        art = null
        val url = now.art ?: return
        io.execute {
            val bmp = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                try {
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
            main.post {
                if (artFor == now.id && bmp != null) {
                    art = bmp
                    render(PlaybackHub.now)
                }
            }
        }
    }

    /** Swiping the app away from recents ends playback with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        PlaybackHub.controls?.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        PlaybackHub.unlisten(onChange)
        session.release()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_PLAY_PAUSE = "dev.foulfoxhacks.betteryts.music.PLAY_PAUSE"
        private const val ACTION_NEXT = "dev.foulfoxhacks.betteryts.music.NEXT"
        private const val ACTION_PREVIOUS = "dev.foulfoxhacks.betteryts.music.PREVIOUS"

        /** Start (or refresh) the service. Call while the app is in the foreground. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w("BTMPlayback", "couldn't start the playback service", e)
            }
        }
    }
}
