package dev.infusion.core.sync

import dev.infusion.core.model.watch.ConnectorObservation
import dev.infusion.core.model.watch.ConflictKind
import dev.infusion.core.model.watch.ResumeAnchor
import dev.infusion.core.model.watch.WatchConflict
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import java.time.Instant

object WatchEventReducer {

    val EventOrdering: Comparator<WatchEvent> =
        compareBy<WatchEvent> { it.occurredAt }.thenBy { it.eventId.value }

    fun initialState(key: WatchKey): WatchState = WatchState(watchKey = key)

    fun reduce(
        key: WatchKey,
        events: List<WatchEvent>,
        removedSourceIds: Set<String> = emptySet(),
    ): WatchState =
        events.sortedWith(EventOrdering).fold(initialState(key)) { state, event ->
            apply(state, event, removedSourceIds)
        }

    fun apply(
        state: WatchState,
        event: WatchEvent,
        removedSourceIds: Set<String> = emptySet(),
    ): WatchState = when (event) {
        is WatchEvent.Local -> applyLocal(state, event, removedSourceIds)
        is WatchEvent.Imported -> applyImported(state, event)
    }

    private fun applyLocal(
        state: WatchState,
        event: WatchEvent.Local,
        removedSourceIds: Set<String>,
    ): WatchState {

        val eventAnchor = event.anchor
        val effectiveAnchor = if (eventAnchor != null && eventAnchor.nativeItemId.sourceId.value.toString() in removedSourceIds) {
            null
        } else {
            eventAnchor
        }
        return when (event.kind) {
            WatchEvent.Kind.START -> start(state, event, effectiveAnchor)
            WatchEvent.Kind.PROGRESS, WatchEvent.Kind.PAUSE -> progress(state, event, effectiveAnchor)
            WatchEvent.Kind.COMPLETED, WatchEvent.Kind.EXPLICIT_WATCHED -> complete(state, event)
            WatchEvent.Kind.EXPLICIT_RESET -> reset(state, event)
            WatchEvent.Kind.RESUME_RESET -> resumeReset(state, event)
            WatchEvent.Kind.CANCELLED -> cancelled(state, event, effectiveAnchor)
            WatchEvent.Kind.SOURCE_FAILURE, WatchEvent.Kind.HANDOFF -> state
        }
    }

    private fun cancelled(
        state: WatchState,
        event: WatchEvent.Local,
        anchor: ResumeAnchor?,
    ): WatchState {
        val position = event.positionMs ?: return state
        if (state.status == WatchStatus.EXPLICITLY_RESET) return state
        if (state.status == WatchStatus.COMPLETED) return state
        val eventSession = event.sessionId
        val activeSession = state.activeSessionId
        if (eventSession != null && activeSession != null && eventSession != activeSession) return state
        val duration = event.durationMs ?: state.durationMs
        return state.copy(
            status = WatchStatus.IN_PROGRESS,
            positionMs = position,
            durationMs = duration,
            completionFraction = CompletionPolicy.fraction(position, duration),
            lastEventId = event.eventId,
            lastMeaningfulAt = event.occurredAt,
            activeSessionId = eventSession ?: state.activeSessionId,
            anchor = updatedAnchor(anchor ?: state.anchor, position, event.occurredAt),
        )
    }

    private fun start(state: WatchState, event: WatchEvent.Local, anchor: ResumeAnchor?): WatchState {

        if (state.status == WatchStatus.COMPLETED &&
            !playbackContradictsCompletion(state, event.positionMs, event.durationMs)
        ) {
            return state
        }

        val sameSessionRestart = event.sessionId != null && event.sessionId == state.activeSessionId
        val position = when {

            sameSessionRestart -> maxOf(state.positionMs, event.positionMs ?: 0L)
            else -> event.positionMs ?: state.positionMs
        }
        val duration = event.durationMs ?: state.durationMs
        return state.copy(
            status = WatchStatus.IN_PROGRESS,
            positionMs = position,
            durationMs = duration,
            completionFraction = CompletionPolicy.fraction(position, duration),
            lastEventId = event.eventId,
            lastMeaningfulAt = event.occurredAt,
            activeSessionId = event.sessionId ?: state.activeSessionId,
            anchor = updatedAnchor(anchor ?: state.anchor, position, event.occurredAt),
        )
    }

    private fun progress(state: WatchState, event: WatchEvent.Local, anchor: ResumeAnchor?): WatchState {

        if (state.status == WatchStatus.EXPLICITLY_RESET) return state

        if (state.status == WatchStatus.COMPLETED &&
            !playbackContradictsCompletion(state, event.positionMs, event.durationMs)
        ) {
            return state
        }
        val position = event.positionMs ?: state.positionMs
        val duration = event.durationMs ?: state.durationMs
        return state.copy(
            status = WatchStatus.IN_PROGRESS,
            positionMs = position,
            durationMs = duration,
            completionFraction = CompletionPolicy.fraction(position, duration),
            lastEventId = event.eventId,
            lastMeaningfulAt = event.occurredAt,
            activeSessionId = event.sessionId ?: state.activeSessionId,
            anchor = updatedAnchor(anchor ?: state.anchor, position, event.occurredAt),
        )
    }

    private fun updatedAnchor(
        anchor: ResumeAnchor?,
        positionMs: Long,
        anchoredAt: Instant,
    ): ResumeAnchor? = anchor?.copy(
        positionMs = positionMs,
        anchoredAt = anchoredAt,
    )

    private fun playbackContradictsCompletion(
        state: WatchState,
        eventPositionMs: Long?,
        eventDurationMs: Long?,
    ): Boolean {
        if (state.status != WatchStatus.COMPLETED) return true
        val position = eventPositionMs ?: state.positionMs
        val duration = eventDurationMs ?: state.durationMs
        return !CompletionPolicy.isComplete(position, duration)
    }

    private fun complete(state: WatchState, event: WatchEvent.Local): WatchState {
        val duration = event.durationMs ?: state.durationMs
        val position = event.positionMs ?: duration ?: state.positionMs
        return state.copy(
            status = WatchStatus.COMPLETED,
            positionMs = position,
            durationMs = duration,
            completionFraction = 1.0,
            lastEventId = event.eventId,
            lastMeaningfulAt = event.occurredAt,
            activeSessionId = event.sessionId ?: state.activeSessionId,
        )
    }

    private fun reset(state: WatchState, event: WatchEvent.Local): WatchState = state.copy(
        status = WatchStatus.EXPLICITLY_RESET,
        positionMs = 0L,
        durationMs = state.durationMs,
        completionFraction = 0.0,
        lastEventId = event.eventId,
        lastMeaningfulAt = event.occurredAt,
        activeSessionId = null,
        anchor = null,
    )

    private fun resumeReset(state: WatchState, event: WatchEvent.Local): WatchState {
        val watched = state.status == WatchStatus.COMPLETED
        return state.copy(
            status = if (watched) WatchStatus.COMPLETED else WatchStatus.UNSEEN,
            positionMs = 0L,
            durationMs = state.durationMs,
            completionFraction = if (watched) 1.0 else 0.0,
            lastEventId = event.eventId,
            lastMeaningfulAt = event.occurredAt,
            activeSessionId = null,
            anchor = null,
        )
    }

    private fun applyImported(state: WatchState, event: WatchEvent.Imported): WatchState {
        val connector = event.connectorId

        val seen = state.connectorStates[connector]
        if (seen != null && !event.observedAt.isAfter(seen.observedAt)) return state

        if (state.status == WatchStatus.COMPLETED) {
            val completedAt = state.lastMeaningfulAt
            val provablyNewer = completedAt != null && event.observedAt.isAfter(completedAt)
            val reopens = provablyNewer &&
                event.kind == WatchEvent.Kind.PROGRESS &&
                event.positionMs != null &&
                playbackContradictsCompletion(state, event.positionMs, event.durationMs)
            if (!reopens) {
                return withConflict(
                    state,
                    WatchConflict(
                        key = "completion:${event.watchKey}:$connector",
                        kind = ConflictKind.COMPLETION_VS_OBSERVATION,
                        connectorId = connector,
                        localPositionMs = state.positionMs,
                        remotePositionMs = event.positionMs,
                        observedAt = event.observedAt,
                        description = "Connector observed ${event.kind} for a completed item without newer, " +
                            "contradicting playback evidence; completion stands.",
                    ),
                )
            }
        }

        if (state.status == WatchStatus.EXPLICITLY_RESET) {
            return withConflict(
                state,
                WatchConflict(
                    key = "reset:${event.watchKey}:$connector",
                    kind = ConflictKind.RESET_VS_OBSERVATION,
                    connectorId = connector,
                    localPositionMs = state.positionMs,
                    remotePositionMs = event.positionMs,
                    observedAt = event.observedAt,
                    description = "Connector observed ${event.kind} after an explicit reset; the reset stands.",
                ),
            )
        }

        val lastMeaningful = state.lastMeaningfulAt
        if (lastMeaningful != null && event.observedAt.isBefore(lastMeaningful)) {
            return withConflict(
                state,
                WatchConflict(
                    key = "stale:${event.watchKey}:$connector",
                    kind = ConflictKind.STALE_OBSERVATION,
                    connectorId = connector,
                    localPositionMs = state.positionMs,
                    remotePositionMs = event.positionMs,
                    observedAt = event.observedAt,
                    description = "Connector observed at ${event.observedAt}, which predates the last meaningful change at $lastMeaningful.",
                ),
            )
        }

        val eventPosition = event.positionMs
        val regressesPosition = event.kind == WatchEvent.Kind.PROGRESS &&
            eventPosition != null && eventPosition < state.positionMs
        val orderingProvenNewer = lastMeaningful != null && event.observedAt.isAfter(lastMeaningful)
        val materialRegression = regressesPosition &&
            WatchConflictPolicy.isMaterialRegression(state.positionMs, eventPosition!!)
        if (regressesPosition && !orderingProvenNewer) {
            return if (materialRegression) {
                withConflict(state, positionRegressionConflict(state, event, orderingProvenNewer = false))
            } else {
                state
            }
        }

        val applied = when (event.kind) {
            WatchEvent.Kind.COMPLETED -> {
                val duration = event.durationMs ?: state.durationMs
                val position = maxOf(state.positionMs, event.positionMs ?: state.positionMs)
                state.copy(
                    status = WatchStatus.COMPLETED,
                    positionMs = position,
                    durationMs = duration,
                    completionFraction = 1.0,
                    lastEventId = event.eventId,
                    lastMeaningfulAt = event.observedAt,
                    connectorStates = state.connectorStates + (
                        connector to ConnectorObservation(
                            connectorId = connector,
                            status = WatchStatus.COMPLETED,
                            positionMs = position,
                            durationMs = duration,
                            observedAt = event.observedAt,
                        )
                        ),
                )
            }
            else -> applyImportedProgress(state, event)
        }
        return if (materialRegression) {
            withConflict(applied, positionRegressionConflict(state, event, orderingProvenNewer = true))
        } else {
            applied
        }
    }

    private fun applyImportedProgress(state: WatchState, event: WatchEvent.Imported): WatchState {
        val connector = event.connectorId
        val duration = event.durationMs ?: state.durationMs
        val position = event.positionMs ?: state.positionMs
        val fraction = CompletionPolicy.fraction(position, duration)
        val status =
            if (fraction >= CompletionPolicy.IMPORT_COMPLETION_FRACTION) WatchStatus.COMPLETED
            else WatchStatus.IN_PROGRESS
        return state.copy(
            status = status,
            positionMs = position,
            durationMs = duration,
            completionFraction = fraction,
            lastEventId = event.eventId,
            lastMeaningfulAt = event.observedAt,
            connectorStates = state.connectorStates + (
                connector to ConnectorObservation(
                    connectorId = connector,
                    status = status,
                    positionMs = position,
                    durationMs = duration,
                    observedAt = event.observedAt,
                )
                ),
        )
    }

    private fun positionRegressionConflict(
        state: WatchState,
        event: WatchEvent.Imported,
        orderingProvenNewer: Boolean,
    ): WatchConflict = WatchConflict(
        key = "regression:${event.watchKey}:${event.connectorId}",
        kind = ConflictKind.POSITION_REGRESSION,
        connectorId = event.connectorId,
        localPositionMs = state.positionMs,
        remotePositionMs = event.positionMs,
        observedAt = event.observedAt,
        description = if (orderingProvenNewer) {
            "Connector observed ${event.positionMs}ms (newer than the local position " +
                "${state.positionMs}ms at ${state.lastMeaningfulAt}); the provider's more recent " +
                "position was applied."
        } else {
            "Connector observed ${event.positionMs}ms, local is ${state.positionMs}ms; the " +
                "non-destructive view is kept because the ordering could not be proven."
        },
    )

    private fun withConflict(state: WatchState, conflict: WatchConflict): WatchState {
        val conflicts = state.conflicts.filterNot { it.key == conflict.key } + conflict
        return state.copy(conflicts = conflicts.takeLast(WatchConflictPolicy.MAX_CONFLICTS))
    }
}
