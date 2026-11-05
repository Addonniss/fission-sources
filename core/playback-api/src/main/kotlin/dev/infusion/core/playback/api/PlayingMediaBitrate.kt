package dev.infusion.core.playback.api

import java.util.Locale

enum class PlayingMediaBitrateProvenance {

    DECLARED_TOTAL_MEDIA,

    TOTAL_MEDIA_AVERAGE,

    SELECTED_VIDEO_STREAM,
}

data class PlayingMediaBitrate(
    val bitsPerSecond: Long,
    val provenance: PlayingMediaBitrateProvenance,
) {
    init {
        require(bitsPerSecond > 0) { "bitrate must be positive, was $bitsPerSecond" }
    }

    val mbps: Double get() = bitsPerSecond / 1_000_000.0

    val label: String get() = String.format(Locale.US, "%.1f %s", mbps, PlayingMediaInfo.BITRATE_UNIT)
}

object PlayingMediaBitrateEvidence {

    const val MAX_PLAUSIBLE_BITS_PER_SECOND: Long = 1_000_000_000L

    fun resolve(
        selectedVideoBitsPerSecond: Int? = null,
        declaredTotalBitsPerSecond: Long? = null,
        totalMediaBytes: Long? = null,
        fullMediaDurationMs: Long? = null,
    ): PlayingMediaBitrate? =
        declaredTotal(declaredTotalBitsPerSecond)
            ?: average(totalMediaBytes, fullMediaDurationMs)
            ?: videoStream(selectedVideoBitsPerSecond)

    fun declaredTotal(bitsPerSecond: Long?): PlayingMediaBitrate? =
        plausible(bitsPerSecond)?.let {
            PlayingMediaBitrate(it, PlayingMediaBitrateProvenance.DECLARED_TOTAL_MEDIA)
        }

    fun average(totalMediaBytes: Long?, fullMediaDurationMs: Long?): PlayingMediaBitrate? {
        val bytes = totalMediaBytes ?: return null
        val durationMs = fullMediaDurationMs ?: return null
        if (bytes <= 0L || durationMs <= 0L) return null
        val bitsPerSecond = bytes.toDouble() * 8.0 * 1_000.0 / durationMs.toDouble()
        if (!bitsPerSecond.isFinite() || bitsPerSecond <= 0.0) return null
        val rounded = kotlin.math.round(bitsPerSecond).toLong()
        return plausible(rounded)?.let {
            PlayingMediaBitrate(it, PlayingMediaBitrateProvenance.TOTAL_MEDIA_AVERAGE)
        }
    }

    fun videoStream(bitsPerSecond: Int?): PlayingMediaBitrate? =
        plausible(bitsPerSecond?.toLong())?.let {
            PlayingMediaBitrate(it, PlayingMediaBitrateProvenance.SELECTED_VIDEO_STREAM)
        }

    private fun plausible(bitsPerSecond: Long?): Long? =
        bitsPerSecond?.takeIf { it > 0L && it <= MAX_PLAUSIBLE_BITS_PER_SECOND }
}
