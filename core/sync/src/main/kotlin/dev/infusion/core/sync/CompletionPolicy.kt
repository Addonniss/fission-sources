package dev.infusion.core.sync

object CompletionPolicy {

    const val IMPORT_COMPLETION_FRACTION = 0.95

    fun isComplete(positionMs: Long?, durationMs: Long?): Boolean =
        positionMs != null && durationMs != null && durationMs > 0 &&
            positionMs.toDouble() / durationMs >= IMPORT_COMPLETION_FRACTION

    fun fraction(positionMs: Long, durationMs: Long?): Double {
        if (durationMs == null || durationMs <= 0) return 0.0
        return (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
    }
}
