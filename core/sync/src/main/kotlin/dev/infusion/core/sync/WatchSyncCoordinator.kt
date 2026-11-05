package dev.infusion.core.sync

import dev.infusion.core.domain.sync.ConnectorCheckpointStore
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.TrackingServicePort
import dev.infusion.core.domain.tracking.WatchTrackingSelectionGate
import dev.infusion.core.domain.watch.WatchEventLedger
import dev.infusion.core.domain.watch.WatchStateStore
import dev.infusion.core.model.EventId
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.sync.ConnectorCheckpoint
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import java.time.Instant

class WatchSyncCoordinator(
    private val ledger: WatchEventLedger,
    private val stateStore: WatchStateStore,
    private val outbox: WatchOutbox,
    private val checkpointStore: ConnectorCheckpointStore,
    private val trackingPort: TrackingServicePort,
    private val now: () -> Instant = Instant::now,

    private val localEventObserver: ((WatchEvent.Local) -> Unit)? = null,

    private val removedSourceIds: () -> Set<String> = { emptySet() },

    private val selectionGate: WatchTrackingSelectionGate =
        WatchTrackingSelectionGate.AllowAllForOfflineConstructionSites,
) {

    fun recordLocal(event: WatchEvent.Local): WatchState {
        ledger.append(event)
        val state = materialize(event.watchKey)
        enqueueForConnectors(event, knownDurationMs = state.durationMs)

        try {
            localEventObserver?.invoke(event)
        } catch (_: Exception) {

        }
        return state
    }

    fun disposeUnsentWorkByUserChoice(connectorId: String): Int =
        outbox.disposeByUserChoice(connectorId)

    fun importObservation(
        connectorId: String,
        watchKey: WatchKey,
        kind: WatchEvent.Kind,
        observedAt: Instant,
        positionMs: Long?,
        durationMs: Long? = null,
        cursor: String? = null,
    ): WatchState {
        require(kind == WatchEvent.Kind.PROGRESS || kind == WatchEvent.Kind.COMPLETED) {
            "connectors may only import PROGRESS or COMPLETED observations"
        }
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        val event = WatchEvent.Imported(
            eventId = EventId.random(),
            watchKey = watchKey,
            kind = kind,
            occurredAt = now(),
            connectorId = connectorId,
            observedAt = observedAt,
            positionMs = positionMs,
            durationMs = durationMs,
        )
        ledger.append(event)
        val state = materialize(watchKey)
        checkpointStore.save(
            ConnectorCheckpoint(
                connectorId = connectorId,
                lastImportCursor = cursor,
                lastImportedAt = now(),
                updatedAt = now(),
            ),
        )
        return state
    }

    fun alreadyImportedCompletion(
        connectorId: String,
        watchKey: WatchKey,
        observedAt: Instant,
    ): Boolean =
        ledger.eventsFor(watchKey).any { event ->
            event is WatchEvent.Imported &&
                event.connectorId == connectorId &&
                event.kind == WatchEvent.Kind.COMPLETED &&
                event.observedAt == observedAt
        }

    fun materialize(key: WatchKey): WatchState {

        val state = WatchEventReducer.reduce(key, ledger.eventsFor(key), removedSourceIds())
        stateStore.save(state)
        return state
    }

    private fun enqueueForConnectors(event: WatchEvent.Local, knownDurationMs: Long? = null) {

        if (event.kind == WatchEvent.Kind.RESUME_RESET) return
        for (registration in trackingPort.connectedConnectors()) {
            if (!registration.capabilities.scrobble) continue

            if (!selectionGate.isSelected(registration.connectorId)) continue
            val idempotencyKey = idempotencyKeyFor(registration.connectorId, event)
            outbox.enqueue(
                OutboxEntry(
                    entryId = OutboxEntryId.random(),
                    connectorId = registration.connectorId,
                    idempotencyKey = idempotencyKey,
                    payload = OutboxPayload.WatchAction(
                        watchKey = event.watchKey,
                        kind = event.kind,
                        positionMs = event.positionMs,
                        durationMs = event.durationMs ?: knownDurationMs,
                        sessionId = event.sessionId,
                        occurredAt = event.occurredAt,
                    ),
                    createdAt = now(),
                ),
            )
        }
    }

    private fun idempotencyKeyFor(connectorId: String, event: WatchEvent.Local): String {
        val scope = when (event.kind) {
            WatchEvent.Kind.COMPLETED, WatchEvent.Kind.EXPLICIT_WATCHED, WatchEvent.Kind.EXPLICIT_RESET ->
                event.sessionId?.let { "session:$it:${event.kind}" } ?: "event:${event.eventId.value}"
            else -> "event:${event.eventId.value}"
        }
        return "watch:$connectorId:$scope"
    }
}
