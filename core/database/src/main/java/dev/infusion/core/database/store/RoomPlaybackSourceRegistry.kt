package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.SourceDao
import dev.infusion.core.database.entity.SourceEntity
import dev.infusion.core.domain.source.PlaybackSourceRegistry
import dev.infusion.core.model.PlayableCandidate

class RoomPlaybackSourceRegistry(
    private val sourceDao: SourceDao,
    private val clock: Clock = Clock.SYSTEM,
    private val knownSource: (sourceId: String) -> SourceEntity? = { null },
) : PlaybackSourceRegistry {

    override fun ensureSourceRow(candidate: PlayableCandidate): Boolean {
        val sourceId = candidate.sourceId.value.toString()
        if (sourceDao.getById(sourceId) != null) return true
        val entity = knownSource(sourceId) ?: return false
        sourceDao.upsert(entity)
        return true
    }
}
