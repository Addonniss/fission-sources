package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class TrackingWatchedBulkSync {

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var inFlightProvider: String? = null

    @Volatile
    private var settled: Map<String, Map<TrackingWatchedHistoryUnit, Boolean>> = emptyMap()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onSettled(listener: () -> Unit) {
        listeners += listener
    }

    fun inProgress(providerId: String): Boolean = inFlight.get() && inFlightProvider == providerId

    fun tryBegin(providerId: String): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        inFlightProvider = providerId
        return true
    }

    fun end(providerId: String, accepted: Map<TrackingWatchedHistoryUnit, Boolean>) {
        val current = settled[providerId] ?: emptyMap()
        settled = settled + (providerId to (current + accepted))
        inFlightProvider = null
        inFlight.set(false)
        listeners.forEach { listener -> runCatching { listener() } }
    }

    fun accepted(providerId: String, unit: TrackingWatchedHistoryUnit): Boolean? =
        settled[providerId]?.get(unit)

    fun reset(providerId: String) {
        settled = settled - providerId
    }
}
