package dev.foulfoxhacks.betteryts.music.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * The most vivid colour in a cover, for "Album colours" (the accent follows what's playing).
 * Grey, near-black and near-white pixels are ignored; the winner is nudged bright enough to read
 * on dark backgrounds. Returns ARGB, or null for a cover with no usable colour.
 */
object AlbumColor {
    fun of(image: ImageBitmap): Long? {
        val small = Bitmap.createScaledBitmap(image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false), 24, 24, true)
        val hsv = FloatArray(3)
        // Hue buckets weighted by saturation, so one strong colour beats many dull ones.
        val weight = FloatArray(36)
        val sat = FloatArray(36)
        val value = FloatArray(36)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.2f || hsv[2] > 0.98f && hsv[1] < 0.35f) continue
            val b = (hsv[0] / 10f).toInt().coerceIn(0, 35)
            val w = hsv[1] * hsv[2]
            weight[b] += w
            sat[b] += hsv[1] * w
            value[b] += hsv[2] * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < 1.5f) return null
        val h = best * 10f + 5f
        val s = (sat[best] / weight[best]).coerceIn(0.55f, 0.95f)
        val v = (value[best] / weight[best]).coerceIn(0.8f, 1f)
        return android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL
    }
}
