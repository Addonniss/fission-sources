package dev.infusion.core.sync

import dev.infusion.core.domain.identity.IdentityMappingStore
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.MappingId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.identity.MappingStatus
import java.time.Instant

class IdentityMappingService(
    private val store: IdentityMappingStore,
    private val now: () -> Instant = Instant::now,
) {

    fun propose(
        external: TypedExternalId,
        titleId: TitleId,
        origin: MappingOrigin,
        confidence: Double,
        editionId: EditionId? = null,
    ): IdentityMapping {
        val existing = store.byExternal(external)
        val updated = IdentityMappingRules.propose(
            existing = existing,
            external = external,
            titleId = titleId,
            origin = origin,
            confidence = confidence,
            editionId = editionId,
            now = now(),
        )
        val created = if (updated == existing) {
            existing.first {
                it.status != MappingStatus.SUPERSEDED &&
                    it.canonicalTitleId == titleId && it.canonicalEditionId == editionId
            }
        } else {
            updated.last()
        }
        store.put(created)
        return created
    }

    fun confirm(mappingId: MappingId): List<IdentityMapping> =
        applyAndPersist(mappingId) { existing, id -> IdentityMappingRules.confirm(existing, id) }

    fun supersede(mappingId: MappingId): List<IdentityMapping> =
        applyAndPersist(mappingId) { existing, id -> IdentityMappingRules.supersede(existing, id) }

    fun split(mappingId: MappingId): List<IdentityMapping> =
        applyAndPersist(mappingId) { existing, id -> IdentityMappingRules.split(existing, id) }

    fun applyRefresh(incoming: List<IdentityMapping>) {

        val all = store.all()
        for ((external, mappings) in all.groupBy { it.external }) {
            val relevantIncoming = incoming.filter { it.external == external }
            if (relevantIncoming.isEmpty()) continue
            val updated = IdentityMappingRules.applyRefresh(mappings, relevantIncoming, now())
            persist(updated)
        }

        for ((external, mappings) in incoming.groupBy { it.external }) {
            if (all.none { it.external == external }) {
                val updated = IdentityMappingRules.applyRefresh(emptyList(), mappings, now())
                persist(updated)
            }
        }
    }

    private fun applyAndPersist(
        mappingId: MappingId,
        transform: (List<IdentityMapping>, MappingId) -> List<IdentityMapping>,
    ): List<IdentityMapping> {
        val target = store.all().firstOrNull { it.mappingId == mappingId } ?: return emptyList()
        val external = target.external
        val updated = transform(store.byExternal(external), mappingId)
        persist(updated)
        return updated
    }

    private fun persist(mappings: List<IdentityMapping>) = mappings.forEach(store::put)
}
