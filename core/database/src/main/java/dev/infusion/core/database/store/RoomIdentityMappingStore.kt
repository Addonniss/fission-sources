package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.IdentityMappingDao
import dev.infusion.core.database.mapper.IdentityMappingMapper
import dev.infusion.core.domain.identity.IdentityMappingStore
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomIdentityMappingStore(
    private val mappingDao: IdentityMappingDao,
    private val clock: Clock = Clock.SYSTEM,
) : IdentityMappingStore {

    override fun put(mapping: IdentityMapping) {
        mappingDao.put(IdentityMappingMapper.toEntity(mapping))
    }

    override fun byExternal(external: TypedExternalId): List<IdentityMapping> =
        mappingDao.byExternal(external.authority, external.value).map(IdentityMappingMapper::toCanonical)

    override fun byTitle(titleId: TitleId): List<IdentityMapping> =
        mappingDao.byTitle(titleId.value.toString()).map(IdentityMappingMapper::toCanonical)

    override fun activeByExternal(external: TypedExternalId): IdentityMapping? =
        mappingDao.activeByExternal(external.authority, external.value)?.let(IdentityMappingMapper::toCanonical)

    override fun observeByExternal(external: TypedExternalId): Flow<List<IdentityMapping>> =
        mappingDao.observeByExternal(external.authority, external.value)
            .map { rows -> rows.map(IdentityMappingMapper::toCanonical) }

    override fun all(): List<IdentityMapping> =
        mappingDao.all().map(IdentityMappingMapper::toCanonical)
}
