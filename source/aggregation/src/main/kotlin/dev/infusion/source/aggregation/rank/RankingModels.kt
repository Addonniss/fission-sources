package dev.infusion.source.aggregation.rank

import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.FactConfidence
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Reachability

data class RankedCandidate(
    val candidate: PlayableCandidate,
    val rank: Int,
    val score: Double,
    val explanation: List<String>,
)

data class RankingPreferences(

    val pinnedSourceIds: Set<String> = emptySet(),

    val preferredAudioLanguage: String? = null,

    val displayResolutionCap: Int? = null,
)
