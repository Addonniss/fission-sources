package dev.infusion.core.database.tracking

import dev.infusion.core.database.dao.EditionDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.store.TitleSupersession
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundAliasTablePort
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId

class RoomTrackingInboundAliasTable(
    private val externalIdDao: ExternalIdDao,
    private val editionDao: EditionDao,
    private val supersession: TitleSupersession,
) : TrackingInboundAliasTablePort {

    override fun titleIdsFor(ids: TrackingExternalIds): Set<TitleId> {
        val candidates = LinkedHashSet<TitleId>()
        for (alias in ids.typed()) {
            titleIdFor(alias)?.let { candidates += supersession.survivorOf(it) }
        }
        return candidates
    }

    override fun aliasesOf(titleId: TitleId): List<TypedExternalId> =
        runCatching {
            externalIdDao.listByTitle(titleId.value.toString())
                .mapNotNull { row ->
                    val authority = row.authority.trim()
                    val value = row.nativeId
                    if (authority.isEmpty() || value.isBlank()) null else TypedExternalId(authority, value)
                }
        }.getOrDefault(emptyList())

    private fun titleIdFor(alias: TypedExternalId): TitleId? {
        val row = runCatching { externalIdDao.get(alias.authority, alias.value) }.getOrNull() ?: return null
        row.titleId?.let { return parse(it) }
        val editionId = row.editionId ?: return null
        val edition = runCatching { editionDao.getById(editionId) }.getOrNull() ?: return null
        return parse(edition.titleId)
    }

    private fun parse(raw: String): TitleId? = runCatching { TitleId.fromString(raw) }.getOrNull()
}
