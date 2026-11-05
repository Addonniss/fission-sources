package dev.infusion.core.domain.appearance

interface AppearancePreferenceStore {

    fun preferencesFor(profileId: String): ProfileAppearancePreferences

    fun setPreferences(profileId: String, preferences: ProfileAppearancePreferences)

    fun clearForProfile(profileId: String)
}
