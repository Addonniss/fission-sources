package dev.infusion.core.sync.store

import dev.infusion.core.domain.identity.IdentityMappingStore
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import dev.infusion.core.model.identity.MappingStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryIdentityMappingStore : IdentityMappingStore {

    private val mappings = mutableMapOf<UUID, IdentityMapping>()
    private val all = MutableStateFlow<List<IdentityMapping>>(emptyList())

    @Synchronized
    override fun put(mapping: IdentityMapping) {
        mappings[mapping.mappingId.value] = mapping
        all.value = mappings.values.toList()
    }

    override fun byExternal(external: TypedExternalId): List<IdentityMapping> =
        synchronized(mappings) { mappings.values.filter { it.external == external } }

    override fun byTitle(titleId: TitleId): List<IdentityMapping> =
        synchronized(mappings) { mappings.values.filter { it.canonicalTitleId == titleId } }

    override fun activeByExternal(external: TypedExternalId): IdentityMapping? =
        synchronized(mappings) {
            mappings.values.firstOrNull { it.external == external && it.status == MappingStatus.ACTIVE }
        }

    override fun observeByExternal(external: TypedExternalId): Flow<List<IdentityMapping>> =
        all.map { list -> list.filter { it.external == external } }

    override fun all(): List<IdentityMapping> = synchronized(mappings) { mappings.values.toList() }
}
