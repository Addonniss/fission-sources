package dev.infusion.core.domain.playback

interface PlaybackLanguagePreferenceStore {

    fun audioLanguagePreference(profileId: String): String?

    fun setAudioLanguagePreference(profileId: String, language: String?)

    fun secondaryAudioLanguagePreference(profileId: String): String?

    fun setSecondaryAudioLanguagePreference(profileId: String, language: String?)

    fun subtitleModePreference(profileId: String): String?

    fun setSubtitleModePreference(profileId: String, mode: String?)

    fun subtitleLanguagePreference(profileId: String): String?

    fun setSubtitleLanguagePreference(profileId: String, language: String?)

    fun secondarySubtitleLanguagePreference(profileId: String): String?

    fun setSecondarySubtitleLanguagePreference(profileId: String, language: String?)

    fun subtitleSameLanguagePolicy(profileId: String): String?

    fun setSubtitleSameLanguagePolicy(profileId: String, policy: String?)

    fun subtitleTypePreference(profileId: String): String?

    fun setSubtitleTypePreference(profileId: String, type: String?)

    fun clearForProfile(profileId: String)
}
