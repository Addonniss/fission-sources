package dev.infusion.core.database.store

import dev.infusion.core.database.dao.RetirementIntentDao
import dev.infusion.core.database.entity.RetirementIntentEntity
import dev.infusion.core.domain.sync.RetirementIntentStore

class RoomRetirementIntentStore(
    private val dao: RetirementIntentDao,
) : RetirementIntentStore {

    override fun save(sourceId: String, payload: String, createdAtMs: Long) {
        dao.upsert(
            RetirementIntentEntity(
                sourceId = sourceId,
                payload = payload,
                createdAtMs = createdAtMs,
            ),
        )
    }

    override fun payloadFor(sourceId: String): String? =
        dao.getBySource(sourceId)?.payload

    override fun pendingSourceIds(): List<String> = dao.listPendingSourceIds()

    override fun hasPending(): Boolean = dao.count() > 0

    override fun clear(sourceId: String) {
        dao.deleteBySource(sourceId)
    }
}
