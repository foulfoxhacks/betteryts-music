package dev.foulfoxhacks.betteryts.music.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import dev.foulfoxhacks.betteryts.music.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Music stored on the phone (MediaStore), played offline by the native player. These songs
 * use ids like "local:1234" so they travel through the same queue, history and playlists as
 * YouTube songs.
 */
object DeviceMusic {
    const val PREFIX = "local:"

    fun isLocal(id: String?) = id?.startsWith(PREFIX) == true

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun uri(id: String): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.removePrefix(PREFIX).toLong())

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    /** Every song MediaStore knows as music, A–Z by title. */
    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        val out = mutableListOf<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = PREFIX + c.getLong(idCol)
                val seconds = (c.getLong(durCol) / 1000).toInt()
                val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "Unknown artist"
                out += Track(
                    id = id,
                    title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Untitled",
                    artist = artist,
                    thumbnail = uri(id).toString(),
                    duration = if (seconds > 0) clock(seconds) else null,
                    durationSeconds = seconds.takeIf { it > 0 },
                    plays = "On this phone",
                )
            }
        }
        out
    }

    /** Cover art for a song's content:// URI: the system thumbnail, or the file's embedded picture. */
    fun art(context: Context, uri: String, side: Int = 512): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(u, Size(side, side), null)
        } else {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, u)
                    embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } finally {
                    release()
                }
            }
        }
    }.getOrNull()
}
