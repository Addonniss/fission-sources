package dev.infusion.core.domain.tracking

data class TrackingTrackerOption(

    val trackerId: String,

    val displayName: String,

    val capabilities: TrackingProviderCapabilities,

    val connected: Boolean,
) {
    init {
        require(trackerId.isNotBlank()) { "a tracker option requires the provider's stable id" }
    }

    val halves: TrackingWatchTrackingHalves get() = capabilities.watchTrackingHalves

    val selectable: Boolean get() = connected && halves.declared
}

interface WatchTrackingSelectionControl {

    fun current(): TrackingSelection

    fun trackers(): List<TrackingTrackerOption>

    fun select(selection: TrackingSelection)

    fun retryableCount(trackerId: String): Int

    fun notSentByUserChoiceCount(trackerId: String): Int
}
