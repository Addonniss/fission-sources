package dev.infusion.core.sync.store

import dev.infusion.core.domain.watch.WatchEventLedger
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import java.util.UUID

class InMemoryWatchEventLedger : WatchEventLedger {

    private val events = linkedMapOf<UUID, WatchEvent>()

    @Synchronized
    override fun append(event: WatchEvent): Boolean {
        if (events.containsKey(event.eventId.value)) return false
        events[event.eventId.value] = event
        return true
    }

    override fun eventsFor(key: WatchKey): List<WatchEvent> =
        synchronized(events) { events.values.filter { it.watchKey == key } }

    override fun allEvents(): List<WatchEvent> = synchronized(events) { events.values.toList() }
}
