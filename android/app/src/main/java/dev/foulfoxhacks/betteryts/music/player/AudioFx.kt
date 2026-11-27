package dev.foulfoxhacks.betteryts.music.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log

/**
 * Equalizer and a gentle compressor ("volume leveller") on the device-file player's audio session.
 * YouTube's embedded player runs in its own web frame, so these only apply to songs on the phone.
 * Any effect the device doesn't offer is simply missing (every call is safe without it).
 */
class AudioFx(sessionId: Int) {
    private val eq: Equalizer? = runCatching { Equalizer(0, sessionId) }.onFailure { Log.w(TAG, "no equalizer", it) }.getOrNull()
    private val dyn: DynamicsProcessing? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, true, 1, false, 0, true,
            ).build()
            DynamicsProcessing(0, sessionId, config)
        }.onFailure { Log.w(TAG, "no dynamics processing", it) }.getOrNull()
    } else null

    val available get() = eq != null
    val compressorAvailable get() = dyn != null
    val bands: Int get() = eq?.numberOfBands?.toInt() ?: 0

    /** Band gain range in millibels (e.g. -1500..1500). */
    val range: Pair<Int, Int> get() = eq?.bandLevelRange?.let { it[0].toInt() to it[1].toInt() } ?: (-1500 to 1500)

    fun centerHz(band: Int): Int = (eq?.getCenterFreq(band.toShort()) ?: 0) / 1000

    fun level(band: Int): Int = eq?.getBandLevel(band.toShort())?.toInt() ?: 0

    val presets: List<String> get() = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } } ?: emptyList()

    fun setEnabled(on: Boolean) {
        runCatching { eq?.enabled = on }
    }

    fun setLevel(band: Int, millibels: Int) {
        runCatching { eq?.setBandLevel(band.toShort(), millibels.coerceIn(range.first, range.second).toShort()) }
    }

    fun usePreset(i: Int) {
        runCatching { eq?.usePreset(i.toShort()) }
    }

    /** Evens out loud and quiet songs: compress above -24 dB at 3:1, a little make-up gain, and a limiter. */
    fun updateCompressor(on: Boolean) {
        val d = dyn ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val band = d.getMbcBandByChannelIndex(0, 0)
                band.isEnabled = on
                band.threshold = -24f
                band.ratio = 3f
                band.attackTime = 5f
                band.releaseTime = 120f
                band.postGain = if (on) 4f else 0f
                d.setMbcBandAllChannelsTo(0, band)
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, on, 0, 1f, 60f, 10f, -2f, 0f))
                d.enabled = on
            }
        }.onFailure { Log.w(TAG, "compressor", it) }
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { dyn?.release() }
    }

    companion object {
        private const val TAG = "BTMAudioFx"
    }
}
