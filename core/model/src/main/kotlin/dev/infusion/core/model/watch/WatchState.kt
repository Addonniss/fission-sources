package dev.infusion.core.model.watch

import dev.infusion.core.model.EventId
import dev.infusion.core.model.SessionId
import java.time.Instant

enum class WatchStatus {
    UNSEEN,
    IN_PROGRESS,
    COMPLETED,

    EXPLICITLY_RESET,
}

enum class ConflictKind {

    STALE_OBSERVATION,

    POSITION_REGRESSION,

    RESET_VS_OBSERVATION,

    COMPLETION_VS_OBSERVATION,
}

data class WatchConflict(
    val key: String,
    val kind: ConflictKind,
    val connectorId: String?,
    val localPositionMs: Long,
    val remotePositionMs: Long?,
    val observedAt: Instant,
    val description: String,
)

data class ConnectorObservation(
    val connectorId: String,
    val status: WatchStatus,
    val positionMs: Long,
    val durationMs: Long?,
    val observedAt: Instant,
)

data class WatchState(
    val watchKey: WatchKey,
    val status: WatchStatus = WatchStatus.UNSEEN,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val completionFraction: Double = 0.0,
    val lastEventId: EventId? = null,
    val lastMeaningfulAt: Instant? = null,
    val activeSessionId: SessionId? = null,
    val anchor: ResumeAnchor? = null,
    val conflicts: List<WatchConflict> = emptyList(),
    val connectorStates: Map<String, ConnectorObservation> = emptyMap(),
) {
    init {
        require(positionMs >= 0) { "positionMs must be >= 0" }
        require(completionFraction in 0.0..1.0) { "completionFraction must be in 0..1" }
        require(durationMs == null || durationMs > 0) { "durationMs must be positive when known" }
    }

    val isContinueWatching: Boolean get() = status == WatchStatus.IN_PROGRESS
}
