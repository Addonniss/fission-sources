package dev.infusion.core.domain.playback

interface PlaybackControlsAutoHidePreferenceStore {

    fun controlsAutoHide(profileId: String): String?

    fun setControlsAutoHide(profileId: String, storageKey: String?)

    fun clearForProfile(profileId: String)
}
