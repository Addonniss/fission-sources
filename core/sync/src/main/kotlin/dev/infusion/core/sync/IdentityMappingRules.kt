package dev.infusion.core.sync

import dev.infusion.core.model.EditionId
import dev.infusion.core.model.MappingId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.identity.MappingStatus
import java.time.Instant

object IdentityMappingRules {

    const val STRONG_LINK_CONFIDENCE = 1.0

    fun propose(
        existing: List<IdentityMapping>,
        external: TypedExternalId,
        titleId: TitleId,
        origin: MappingOrigin,
        confidence: Double,
        editionId: EditionId? = null,
        now: Instant = Instant.now(),
    ): List<IdentityMapping> {
        require(confidence in 0.0..1.0) { "confidence must be in 0..1" }
        val active = existing.firstOrNull { it.status == MappingStatus.ACTIVE }
        val sameTargetAlreadyMapped = existing.any {
            it.status != MappingStatus.SUPERSEDED &&
                it.canonicalTitleId == titleId && it.canonicalEditionId == editionId
        }
        if (sameTargetAlreadyMapped) return existing

        val isUserCorrection = origin == MappingOrigin.USER_CORRECTION
        val replacesAutomatedActive =
            isUserCorrection && active != null && active.canonicalTitleId != titleId
        val autoActivate = replacesAutomatedActive ||
            (confidence >= STRONG_LINK_CONFIDENCE && active == null)

        val mapping = IdentityMapping(
            mappingId = MappingId.random(),
            external = external,
            canonicalTitleId = titleId,
            canonicalEditionId = editionId,
            status = if (autoActivate) MappingStatus.ACTIVE else MappingStatus.PROPOSED,
            confidence = confidence,
            origin = origin,
            supersedesMappingId = if (replacesAutomatedActive) active.mappingId else null,
            createdAt = now,
        )
        val demoted = if (replacesAutomatedActive) {
            existing.map {
                if (it.mappingId == active.mappingId) it.withStatus(MappingStatus.SUPERSEDED) else it
            }
        } else {
            existing
        }
        return demoted + mapping
    }

    fun confirm(existing: List<IdentityMapping>, mappingId: MappingId): List<IdentityMapping> {
        val target = existing.firstOrNull { it.mappingId == mappingId } ?: return existing
        if (target.status != MappingStatus.PROPOSED) return existing
        val replaced = existing.firstOrNull { it.status == MappingStatus.ACTIVE }
        return existing.map { m ->
            when {
                m.mappingId == mappingId -> m
                    .withStatus(MappingStatus.ACTIVE)
                    .withSupersedes(replaced?.mappingId)
                m.status == MappingStatus.ACTIVE -> m.withStatus(MappingStatus.SUPERSEDED)
                else -> m
            }
        }
    }

    fun supersede(existing: List<IdentityMapping>, mappingId: MappingId): List<IdentityMapping> =
        existing.map {
            if (it.mappingId == mappingId && it.status == MappingStatus.ACTIVE) {
                it.withStatus(MappingStatus.SUPERSEDED)
            } else {
                it
            }
        }

    fun split(existing: List<IdentityMapping>, mappingId: MappingId): List<IdentityMapping> {
        val target = existing.firstOrNull { it.mappingId == mappingId } ?: return existing
        if (target.status != MappingStatus.ACTIVE) return existing
        val predecessorId = target.supersedesMappingId
        if (predecessorId == null) {
            return existing.map {
                if (it.mappingId == mappingId) it.withStatus(MappingStatus.SUPERSEDED) else it
            }
        }
        return existing.map { m ->
            when {
                m.mappingId == mappingId -> m.withStatus(MappingStatus.SUPERSEDED)
                m.mappingId == predecessorId && m.status == MappingStatus.SUPERSEDED ->
                    m.withStatus(MappingStatus.ACTIVE)
                else -> m
            }
        }
    }

    fun applyRefresh(
        existing: List<IdentityMapping>,
        incoming: List<IdentityMapping>,
        now: Instant = Instant.now(),
    ): List<IdentityMapping> {
        var current = existing
        for (candidate in incoming) {
            current = propose(
                existing = current,
                external = candidate.external,
                titleId = candidate.canonicalTitleId,
                editionId = candidate.canonicalEditionId,
                origin = candidate.origin,
                confidence = candidate.confidence,
                now = now,
            )
        }
        return current
    }
}
