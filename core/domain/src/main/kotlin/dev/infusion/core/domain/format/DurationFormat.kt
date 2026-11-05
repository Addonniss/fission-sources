package dev.infusion.core.domain.format

import kotlin.math.roundToInt

object DurationFormat {

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 3_600_000L

    private const val ZERO_SECONDS = "0s"

    private const val MIN_PERCENT = 1

    private const val MAX_PERCENT = 99

    fun formatDuration(totalMs: Long): String = when {
        totalMs <= 0L -> ZERO_SECONDS
        totalMs < MILLIS_PER_MINUTE -> "${totalMs / MILLIS_PER_SECOND}s"
        totalMs < MILLIS_PER_HOUR -> "${totalMs / MILLIS_PER_MINUTE}m"
        else -> {
            val hours = totalMs / MILLIS_PER_HOUR
            val minutes = (totalMs % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
            "${hours}h ${minutes.toString().padStart(2, '0')}m"
        }
    }

    fun formatResume(positionMs: Long, completionFraction: Double): String {
        val elapsed = formatDuration(positionMs)
        val percent = completionPercentOrNull(positionMs, completionFraction)
        return if (percent == null) "Resume ($elapsed)" else "Resume $percent% ($elapsed)"
    }

    fun formatResumePercent(completionFraction: Double): String? {

        if (completionFraction <= 0.0) return null
        return completionPercentOrNull(completionFraction)?.let { "$it%" }
    }

    fun formatPositionClock(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0L)) / MILLIS_PER_SECOND
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return "${hours.toString().padStart(2, '0')}:" +
            "${minutes.toString().padStart(2, '0')}:" +
            "${seconds.toString().padStart(2, '0')}"
    }

    private fun completionPercentOrNull(positionMs: Long, completionFraction: Double): Int? =
        if (positionMs <= 0L) null else completionPercentOrNull(completionFraction)

    private fun completionPercentOrNull(completionFraction: Double): Int? {
        if (!completionFraction.isFinite()) return null
        if (completionFraction < 0.0 || completionFraction > 1.0) return null
        return (completionFraction * 100.0).roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
    }
}
