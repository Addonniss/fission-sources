package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.MappingOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomMediaIdentityStore(
    private val externalIdDao: ExternalIdDao,
    private val titleDao: TitleDao,
    private val clock: Clock = Clock.SYSTEM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transaction: (block: () -> Unit) -> Unit = { block -> block() },
) : MediaIdentityStore {

    private val supersession = TitleSupersession(titleDao)

    override suspend fun externalIdsFor(titleId: TitleId): List<TypedExternalId> =
        withContext(ioDispatcher) {
            externalIdDao.listByTitle(supersession.survivorOf(titleId).value.toString())
                .filter { it.editionId == null }
                .map { TypedExternalId(it.authority, it.nativeId) }
        }

    override suspend fun externalIdsForAll(titleIds: List<TitleId>): Map<TitleId, List<TypedExternalId>> =
        withContext(ioDispatcher) {
            val distinct = titleIds.distinct()
            if (distinct.isEmpty()) return@withContext emptyMap()
            val survivors = supersession.survivorsOf(distinct)
            val rows = externalIdDao.listByTitleIdsChunked(survivors.values.map { it.value.toString() })
                .filter { it.editionId == null }
                .groupBy { it.titleId }
            distinct.associateWith { id ->
                rows[survivors.getValue(id).value.toString()].orEmpty()
                    .map { TypedExternalId(it.authority, it.nativeId) }
            }
        }

    override suspend fun titleIdFor(external: TypedExternalId): TitleId? =
        withContext(ioDispatcher) {
            externalIdDao.get(external.authority, external.value)
                ?.titleId
                ?.let { raw -> runCatching { TitleId.fromString(raw) }.getOrNull() }
                ?.let(supersession::survivorOf)
        }

    override suspend fun canonicalKindOf(titleId: TitleId): dev.infusion.core.model.TitleKind? =
        withContext(ioDispatcher) {
            val surviving = supersession.survivorOf(titleId)
            titleDao.getById(surviving.value.toString())
                ?.kind
                ?.name
                ?.let { raw -> runCatching { dev.infusion.core.model.TitleKind.valueOf(raw) }.getOrNull() }
        }

    override suspend fun canonicalKindsOf(
        titleIds: List<TitleId>,
    ): Map<TitleId, dev.infusion.core.model.TitleKind?> = withContext(ioDispatcher) {
        val distinct = titleIds.distinct()
        if (distinct.isEmpty()) return@withContext emptyMap()
        val survivors = supersession.survivorsOf(distinct)
        val rows = titleDao.listByIdsChunked(survivors.values.map { it.value.toString() })
            .associateBy { it.titleId }
        distinct.associateWith { id ->
            rows[survivors.getValue(id).value.toString()]
                ?.kind
                ?.name
                ?.let { raw -> runCatching { dev.infusion.core.model.TitleKind.valueOf(raw) }.getOrNull() }
        }
    }

    override suspend fun externalIdsForSource(
        sourceId: SourceId,
        nativeItemId: String,
    ): List<TypedExternalId> = withContext(ioDispatcher) {
        val row = externalIdDao.get(sourceId.value.toString(), nativeItemId) ?: return@withContext emptyList()
        listOf(TypedExternalId(row.authority, row.nativeId))
    }

    override suspend fun bind(external: TypedExternalId, titleId: TitleId, origin: MappingOrigin) {
        withContext(ioDispatcher) {
            transaction {

                if (externalIdDao.get(external.authority, external.value) != null) return@transaction

                if (titleDao.getById(titleId.value.toString()) == null) return@transaction

                externalIdDao.upsert(
                    ExternalIdEntity(
                        authority = external.authority,
                        nativeId = external.value,
                        titleId = titleId.value.toString(),
                        editionId = null,
                        createdAtMs = clock.nowMs(),
                    ),
                )
            }
        }
    }
}
