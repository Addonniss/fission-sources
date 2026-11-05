package dev.infusion.core.playback.api

import kotlin.math.abs
import kotlin.math.roundToInt

data class MeasuredVideoCadence(

    val frameRateMilliHz: Int,

    val sampleCount: Int,

    val isCanonical: Boolean,
) {
    init {
        require(frameRateMilliHz > 0) { "frameRateMilliHz must be > 0" }
        require(sampleCount > 0) { "sampleCount must be > 0" }
    }
}

data class MeasuredVideoEvidence(

    val frameRateMilliHz: Int? = null,

    val widthPx: Int? = null,

    val heightPx: Int? = null,
) {
    init {
        require(frameRateMilliHz == null || frameRateMilliHz > 0) { "frameRateMilliHz must be > 0 when measured" }
        require(widthPx == null || widthPx > 0) { "widthPx must be > 0 when measured" }
        require(heightPx == null || heightPx > 0) { "heightPx must be > 0 when measured" }
        require((widthPx == null) == (heightPx == null)) { "measured size must be a complete pair" }
    }

    val isEmpty: Boolean get() = frameRateMilliHz == null && widthPx == null

    companion object {

        val NONE = MeasuredVideoEvidence()
    }
}

object VideoCadenceNaming {

    const val NANOS_PER_SECOND: Long = 1_000_000_000L

    const val SNAP_TOLERANCE: Double = 5.0e-4

    const val MINIMUM_MATCHED_INTERVALS: Int = 64

    val CANONICAL_RATE_MILLI_HZ: List<Int> = listOf(
        23976,
        24000,
        25000,
        29970,
        30000,
        47952,
        48000,
        50000,
        59940,
        60000,
        100000,
        119880,
        120000,
    )

    fun canonicalIntervalNs(rateMilliHz: Int): Long =
        if (rateMilliHz <= 0) 0L else 1_000_000_000_000L / rateMilliHz.toLong()

    fun milliHzOfInterval(frameDurationNs: Long): Int? {
        if (frameDurationNs <= 0L) return null
        val milliHz = (1_000_000_000_000.0 / frameDurationNs.toDouble()).roundToInt()
        return milliHz.takeIf { it > 0 }
    }

    fun nameCadence(frameDurationNs: Long, matchedIntervals: Int): MeasuredVideoCadence? {
        if (matchedIntervals < MINIMUM_MATCHED_INTERVALS) return null
        val measuredMilliHz = milliHzOfInterval(frameDurationNs) ?: return null
        val canonical = CANONICAL_RATE_MILLI_HZ.minByOrNull { abs(it - measuredMilliHz) }
        val snapped = canonical != null &&
            abs(canonical - measuredMilliHz).toDouble() / canonical.toDouble() <= SNAP_TOLERANCE
        return MeasuredVideoCadence(
            frameRateMilliHz = if (snapped) canonical!! else measuredMilliHz,
            sampleCount = matchedIntervals,
            isCanonical = snapped,
        )
    }
}
