package dev.infusion.feature.vertical.details

import dev.infusion.core.model.EventId
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant

object MarkWatchedActions {

    fun markWatched(
        coordinator: WatchSyncCoordinator,
        watchKey: WatchKey,
        now: () -> Instant = Instant::now,
    ): WatchStatus {
        val state = coordinator.recordLocal(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.EXPLICIT_WATCHED,
                occurredAt = now(),
                sessionId = null,
                positionMs = 0,
                durationMs = null,
            ),
        )
        return state.status
    }

    fun markUnwatched(
        coordinator: WatchSyncCoordinator,
        watchKey: WatchKey,
        now: () -> Instant = Instant::now,
    ): WatchStatus {
        val state = coordinator.recordLocal(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.EXPLICIT_RESET,
                occurredAt = now(),
                sessionId = null,
                positionMs = 0,
                durationMs = null,
            ),
        )
        return state.status
    }
}
