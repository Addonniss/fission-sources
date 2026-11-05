package dev.infusion.core.playback.api

enum class CompletionThreshold(

    val percent: Int,
) {
    P80(80),
    P85(85),
    P90(90),
    P95(95),
    ;

    val fraction: Double get() = percent / 100.0

    val storageKey: String get() = percent.toString()

    companion object {

        val DEFAULT: CompletionThreshold = P90

        fun orDefault(storageKey: String?): CompletionThreshold {
            val value = storageKey?.trim()?.toIntOrNull() ?: return DEFAULT
            return entries.firstOrNull { it.percent == value } ?: DEFAULT
        }
    }
}

data class CompletionThresholds(

    val movie: CompletionThreshold = CompletionThreshold.DEFAULT,

    val episode: CompletionThreshold = CompletionThreshold.DEFAULT,
) {

    fun forKind(isEpisode: Boolean): CompletionThreshold = if (isEpisode) episode else movie

    fun qualifies(isEpisode: Boolean, positionMs: Long?, durationMs: Long?): Boolean {
        if (positionMs == null || positionMs < 0L) return false
        if (durationMs == null || durationMs <= 0L) return false
        return positionMs.toDouble() / durationMs.toDouble() >= forKind(isEpisode).fraction
    }

    companion object {

        val DEFAULT: CompletionThresholds = CompletionThresholds()
    }
}
