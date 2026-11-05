package dev.infusion.core.domain.tracking

sealed interface TrackingSelection {

    data object OnDeviceOnly : TrackingSelection

    data class Tracker(val trackerId: String) : TrackingSelection {
        init {
            require(trackerId.isNotBlank()) { "a tracker selection requires the provider's stable id" }
        }
    }
}

data class TrackingPreferences(
    val selection: TrackingSelection = TrackingSelection.OnDeviceOnly,
    val hideWatched: Boolean = false,
) {

    val selectedTrackerId: String? get() = (selection as? TrackingSelection.Tracker)?.trackerId

    companion object {

        val Default: TrackingPreferences = TrackingPreferences()
    }
}

data class TrackingWatchTrackingHalves(

    val sends: Boolean,

    val reads: Boolean,
) {

    val declared: Boolean get() = sends || reads
}

val TrackingProviderCapabilities.watchTrackingHalves: TrackingWatchTrackingHalves
    get() = TrackingWatchTrackingHalves(
        sends = scrobbling,
        reads = playbackImport || watchedImport,
    )

data class TrackingAvailability(
    val capabilities: TrackingProviderCapabilities,
    val connected: Boolean,
) {

    val halves: TrackingWatchTrackingHalves get() = capabilities.watchTrackingHalves

    val selectable: Boolean get() = connected && halves.declared
}

fun selectableTrackers(candidates: Iterable<TrackingAvailability>): List<TrackingAvailability> =
    candidates.filter { it.selectable }
