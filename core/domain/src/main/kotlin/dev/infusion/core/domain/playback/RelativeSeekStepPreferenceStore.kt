package dev.infusion.core.domain.playback

interface RelativeSeekStepPreferenceStore {

    fun relativeSeekStep(profileId: String): String?

    fun setRelativeSeekStep(profileId: String, storageKey: String?)

    fun clearForProfile(profileId: String)
}
