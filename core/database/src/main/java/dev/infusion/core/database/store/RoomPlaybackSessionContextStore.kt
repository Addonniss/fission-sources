package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.PlaySessionContextDao
import dev.infusion.core.database.entity.PlaySessionContextEntity
import dev.infusion.core.domain.sync.PlaybackSessionContext
import dev.infusion.core.domain.sync.PlaybackSessionContextStore
import java.util.UUID

class RoomPlaybackSessionContextStore(
    private val dao: PlaySessionContextDao,
    private val clock: Clock = Clock.SYSTEM,
) : PlaybackSessionContextStore {

    override fun put(context: PlaybackSessionContext) {
        val existing = dao.get(context.sourceId, context.watchSessionId)
        dao.upsert(
            PlaySessionContextEntity(
                contextId = existing?.contextId ?: UUID.randomUUID().toString(),
                sourceId = context.sourceId,
                watchSessionId = context.watchSessionId,
                itemId = context.itemId,
                mediaSourceId = context.mediaSourceId,
                playSessionId = context.playSessionId,
                createdAtMs = existing?.createdAtMs ?: clock.nowMs(),
            ),
        )
    }

    override fun get(sourceId: String, watchSessionId: String): PlaybackSessionContext? =
        dao.get(sourceId, watchSessionId)?.toCanonical()

    override fun active(sourceId: String): List<PlaybackSessionContext> =
        dao.active(sourceId).map { it.toCanonical() }

    override fun remove(sourceId: String, watchSessionId: String) {
        dao.remove(sourceId, watchSessionId)
    }

    override fun deleteBySource(sourceId: String): Int =
        dao.deleteBySource(sourceId)

    private fun PlaySessionContextEntity.toCanonical(): PlaybackSessionContext = PlaybackSessionContext(
        sourceId = sourceId,
        watchSessionId = watchSessionId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
    )
}
