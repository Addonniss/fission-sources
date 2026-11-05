package dev.infusion.core.domain.tracking

interface TrackingPreferencesStore {

    fun current(): TrackingPreferences

    fun record(selection: TrackingSelection)

    fun clearProfile(profileId: String)

    fun clearAllProfiles()
}
