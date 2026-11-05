package dev.infusion.core.model.identity

import dev.infusion.core.model.TitleId

data class CanonicalClaim(
    val titleId: TitleId,
    val declaredAtMs: Long,
)

data class ConvergencePlan(
    val survivor: TitleId,
    val losers: List<TitleId>,
)

object CanonicalIdentityConvergence {

    fun plan(
        claims: Collection<CanonicalClaim>,
        blockedByUserCorrection: Boolean = false,
    ): ConvergencePlan? {
        val distinct = claims.distinctBy { it.titleId }
        if (distinct.size < 2) return null
        if (blockedByUserCorrection) return null
        val ordered = distinct.sortedWith(CLAIM_ORDER)
        return ConvergencePlan(
            survivor = ordered.first().titleId,
            losers = ordered.drop(1).map { it.titleId },
        )
    }

    fun survivor(claims: Collection<CanonicalClaim>): CanonicalClaim? =
        claims.minWithOrNull(CLAIM_ORDER)

    private val CLAIM_ORDER: Comparator<CanonicalClaim> = compareBy(
        { it.declaredAtMs },
        { it.titleId.value.toString() },
    )
}
