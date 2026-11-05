package dev.infusion.core.playback.api

data class StingerEvidence(
    val hasMidCredits: Boolean = false,
    val hasPostCredits: Boolean = false,
    val hasBloopers: Boolean = false,

    val source: String,

    val definitive: Boolean,
) {

    val isPositive: Boolean get() = hasMidCredits || hasPostCredits || hasBloopers

    val isDefinitiveNegative: Boolean get() = definitive && !isPositive
}

sealed interface StingerSourceOutcome {
    data class Evidence(val evidence: StingerEvidence) : StingerSourceOutcome
    data object Absent : StingerSourceOutcome
    data class Failed(val reason: String) : StingerSourceOutcome
}

data class StingerLookupIdentity(
    val tmdbId: Long? = null,
    val imdbId: String? = null,

    val title: String? = null,

    val year: Int? = null,
) {

    val hasTmdbCoordinates: Boolean get() = tmdbId != null || !imdbId.isNullOrBlank()

    val hasTitleCoordinates: Boolean get() = !title.isNullOrBlank()
}

interface StingerEvidenceSource {

    val id: String

    suspend fun fetch(identity: StingerLookupIdentity): StingerSourceOutcome
}

object StingerReconciliation {

    fun reconcile(outcomes: List<StingerSourceOutcome>): StingerEvidence? {
        var merged: StingerEvidence? = null
        for (outcome in outcomes) {
            val evidence = (outcome as? StingerSourceOutcome.Evidence)?.evidence ?: continue
            if (evidence.definitive) return evidence
            merged = if (merged == null) {
                evidence.copy(source = "Aggregated")
            } else {
                StingerEvidence(
                    hasMidCredits = merged.hasMidCredits || evidence.hasMidCredits,
                    hasPostCredits = merged.hasPostCredits || evidence.hasPostCredits,
                    hasBloopers = merged.hasBloopers || evidence.hasBloopers,
                    source = "Aggregated",
                    definitive = false,
                )
            }
        }
        return merged
    }

    fun stingerTriggerMs(durationMs: Long?, chapters: List<ChapterMark>, percent: Int): Long? {
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        val usable = chapters
            .filter { it.startMs > 0L && it.startMs < duration - CHAPTER_TAIL_GUARD_MS }
            .sortedBy { it.startMs }
        if (usable.size >= 3) return usable[usable.size - 2].startMs
        val clampedPercent = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return duration * clampedPercent / 100L
    }

    const val CHAPTER_TAIL_GUARD_MS: Long = 5_000L

    const val MIN_PERCENT: Int = 50
    const val MAX_PERCENT: Int = 99
}
