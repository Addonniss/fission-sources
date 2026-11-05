package dev.infusion.core.playback.api

interface HdrSubtitleDimmingPreferenceStore {

    fun dimmingFor(profileId: String): HdrSubtitleDimming

    fun setDimming(profileId: String, dimming: HdrSubtitleDimming)

    fun clearForProfile(profileId: String)
}
