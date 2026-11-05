package dev.infusion.core.database.store

import dev.infusion.core.database.dao.WatchEventDao
import dev.infusion.core.database.mapper.WatchEventMapper
import dev.infusion.core.database.watch.WatchKeyCodec
import dev.infusion.core.database.watch.canonicalForConvergence
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.watch.WatchEventLedger
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey

class RoomWatchEventLedger(
    private val watchEventDao: WatchEventDao,
    private val scope: ProfileScope,
    private val supersession: TitleSupersession? = null,
) : WatchEventLedger {

    override fun append(event: WatchEvent): Boolean {
        val canonical = canonicalForConvergence(event)
        val row = WatchEventMapper.toEntity(scope.activeProfileId, canonical, receivedAtMs = event.occurredAt.toEpochMilli())
        return watchEventDao.append(row) != -1L
    }

    override fun eventsFor(key: WatchKey): List<WatchEvent> =
        watchEventDao.listForKey(scope.activeProfileId, WatchKeyCodec.encode(key.canonicalForConvergence(supersession)))
            .map(WatchEventMapper::toCanonical)

    override fun allEvents(): List<WatchEvent> =
        watchEventDao.allForProfile(scope.activeProfileId).map(WatchEventMapper::toCanonical)

    private fun canonicalForConvergence(event: WatchEvent): WatchEvent {
        val key = event.watchKey.canonicalForConvergence(supersession)
        if (key == event.watchKey) return event
        return when (event) {
            is WatchEvent.Local -> event.copy(watchKey = key)
            is WatchEvent.Imported -> event.copy(watchKey = key)
        }
    }
}
