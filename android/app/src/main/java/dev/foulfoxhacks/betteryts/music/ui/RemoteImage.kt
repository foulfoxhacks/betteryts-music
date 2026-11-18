package dev.foulfoxhacks.betteryts.music.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.foulfoxhacks.betteryts.music.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Thumbnails: downloaded once, downscaled, kept in a memory cache sized to the device. */
object ImageCache {
    private const val MAX_SIDE = 720
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val bytes = try {
                if (conn.responseCode != 200) return@runCatching null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

/** Center-cropped remote image on a placeholder; `modifier` sets size and shape. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var bitmap by remember(url) { mutableStateOf(url?.let(ImageCache::peek)) }
    LaunchedEffect(url) {
        if (bitmap == null && url != null) bitmap = ImageCache.load(url)
    }
    Box(modifier.background(Brand.Raised)) {
        bitmap?.let { Image(it, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
