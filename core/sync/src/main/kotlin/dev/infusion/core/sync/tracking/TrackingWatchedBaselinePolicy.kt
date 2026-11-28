package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingWatchedHistoryBaselineState
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.trackingWatchedHistoryBaselineState

class TrackingWatchedBaselinePolicy(

    private val coverageStore: TrackingWatchedHistoryCoverageStore,
) {

    fun stateOf(providerId: String, unit: TrackingWatchedHistoryUnit): TrackingWatchedHistoryBaselineState {
        val record = runCatching { coverageStore.coverage(providerId, unit) }
        val importProgress = runCatching { coverageStore.importProgress(providerId, unit) }

        if (record.isFailure || importProgress.isFailure) {
            return TrackingWatchedHistoryBaselineState.COMPATIBLE
        }
        return trackingWatchedHistoryBaselineState(
            record = record.getOrNull(),
            importOpen = importProgress.getOrNull() != null,
        )
    }

    fun unitsRequiringBaseline(
        providerId: String,
        units: Collection<TrackingWatchedHistoryUnit>,
    ): Set<TrackingWatchedHistoryUnit> = units
        .filterTo(LinkedHashSet()) { stateOf(providerId, it).requiresBaselineWalk }

    fun requiresBaseline(providerId: String, unit: TrackingWatchedHistoryUnit): Boolean =
        stateOf(providerId, unit).requiresBaselineWalk
}
