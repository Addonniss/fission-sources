package dev.infusion.core.domain.tracking

fun interface WatchTrackingSelectionGate {

    fun isSelected(connectorId: String): Boolean

    companion object {

        val AllowAllForOfflineConstructionSites: WatchTrackingSelectionGate =
            WatchTrackingSelectionGate { true }
    }
}

fun TrackingPreferencesStore.watchTrackingSelectionGate(): WatchTrackingSelectionGate =
    WatchTrackingSelectionGate { connectorId -> current().selectedTrackerId == connectorId }
