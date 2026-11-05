package dev.infusion.source.aggregation.match

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping

sealed interface MatchEvidence {

    data class TypedExternalId(
        val external: dev.infusion.core.model.TypedExternalId,
        val confidence: Double,
    ) : MatchEvidence

    data class NameYear(
        val normalizedName: String,
        val year: Int?,
        val candidateTitleId: TitleId,
        val confidence: Double,
    ) : MatchEvidence

    data class AddonAlias(
        val external: dev.infusion.core.model.TypedExternalId,
        val titleId: TitleId,
    ) : MatchEvidence
}

sealed interface AddonMatch {

    data class Matched(
        val titleId: TitleId,
        val confidence: Double,
        val evidence: List<MatchEvidence>,

        val mapping: IdentityMapping?,

        val isDurable: Boolean,
    ) : AddonMatch {

        val isReversible: Boolean get() = !isDurable
    }

    data object Unmatched : AddonMatch
}
