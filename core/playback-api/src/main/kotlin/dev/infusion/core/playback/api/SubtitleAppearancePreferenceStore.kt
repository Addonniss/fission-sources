package dev.infusion.core.playback.api

interface SubtitleAppearancePreferenceStore {

    fun appearanceFor(profileId: String): SubtitleAppearance

    fun setAppearance(profileId: String, appearance: SubtitleAppearance)

    fun clearForProfile(profileId: String)
}
