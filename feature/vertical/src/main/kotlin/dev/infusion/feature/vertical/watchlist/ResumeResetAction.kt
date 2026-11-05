package dev.infusion.feature.vertical.watchlist

import dev.infusion.core.model.EventId
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant

object ResumeResetAction {

    fun reset(
        coordinator: WatchSyncCoordinator,
        watchKey: WatchKey,
        now: () -> Instant = Instant::now,
    ): WatchStatus {
        val state = coordinator.recordLocal(
            WatchEvent.Local(
                eventId = EventId.random(),
                watchKey = watchKey,
                kind = WatchEvent.Kind.RESUME_RESET,
                occurredAt = now(),
                sessionId = null,
                positionMs = 0,
                durationMs = null,
            ),
        )
        return state.status
    }
}
