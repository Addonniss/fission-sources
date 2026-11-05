package dev.infusion.core.playback.api

object SegmentTimecode {

    private val PATTERN = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$")

    fun parseMs(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = PATTERN.matchEntire(text) ?: return null
        val a = match.groupValues[1].toLongOrNull() ?: return null
        val b = match.groupValues[2].toLongOrNull() ?: return null
        val c = match.groupValues[3]
        val seconds: Long
        val minutes: Long
        if (c.isNullOrEmpty()) {

            minutes = a
            seconds = b
        } else {

            val secs = c.toLongOrNull() ?: return null
            if (b > 59) return null
            minutes = a * 60 + b
            seconds = secs
        }
        if (seconds > 59) return null
        return (minutes * 60 + seconds) * 1000L
    }

    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
