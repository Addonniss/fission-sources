package dev.infusion.core.playback.api

object SkipIntroProjection {

    const val DEFAULT_WINDOW_FRACTION: Double = 0.2

    const val WALL_CLOCK_CAP_MS: Long = 10_000L

    const val LABEL: String = "Skip Intro"

    fun openingSegment(timeline: PlaybackSegmentTimeline?): PlaybackSegment? =
        timeline?.segments?.firstOrNull { segment ->
            (segment.type == SegmentType.INTRO || segment.type == SegmentType.RECAP) && segment.isClosedRange
        }

    fun windowEndMs(segment: PlaybackSegment, persistent: Boolean): Long {
        val end = segment.endMs ?: return segment.startMs
        val span = end - segment.startMs
        if (span <= 0L) return end
        val fraction = if (persistent) 1.0 else DEFAULT_WINDOW_FRACTION
        return segment.startMs + (span * fraction).toLong()
    }

    fun isEligible(segment: PlaybackSegment, positionMs: Long, persistent: Boolean): Boolean {
        val end = segment.endMs ?: return false
        if (!segment.isClosedRange) return false
        if (positionMs < segment.startMs) return false
        if (positionMs >= end) return false
        return positionMs < windowEndMs(segment, persistent)
    }

    fun targetKey(segment: PlaybackSegment): String = "${segment.type.name}@${segment.startMs}"

    fun targetMs(segment: PlaybackSegment): Long? = segment.endMs?.takeIf { it > segment.startMs }
}

data class SkipIntroUi(
    val label: String = SkipIntroProjection.LABEL,
    val targetMs: Long,

    val countdownSeconds: Int? = null,

    val fullscreenDismissed: Boolean = false,
) {
    val isCountingDown: Boolean get() = countdownSeconds != null
}
