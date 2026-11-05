package dev.infusion.core.domain.watch

import dev.infusion.core.model.EventId
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey

interface WatchEventLedger {

    fun append(event: WatchEvent): Boolean

    fun eventsFor(key: WatchKey): List<WatchEvent>

    fun allEvents(): List<WatchEvent>
}
