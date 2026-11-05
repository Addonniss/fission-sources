package dev.infusion.core.domain.identity

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.CanonicalClaim

data class CanonicalIdentityClaims(
    val claims: List<CanonicalClaim>,
    val userCorrectionConflict: Boolean = false,
)

interface CanonicalConvergenceStore {

    suspend fun claims(aliases: List<TypedExternalId>): CanonicalIdentityClaims

    suspend fun claimsOf(aliasSets: List<List<TypedExternalId>>): List<CanonicalIdentityClaims> =
        aliasSets.map { claims(it) }

    suspend fun successorOf(titleId: TitleId): TitleId?

    suspend fun successorsOf(titleIds: List<TitleId>): Map<TitleId, TitleId> =
        titleIds.distinct().associateWith { id -> successorOf(id) ?: id }

    suspend fun converge(loser: TitleId, survivor: TitleId): Boolean
}
