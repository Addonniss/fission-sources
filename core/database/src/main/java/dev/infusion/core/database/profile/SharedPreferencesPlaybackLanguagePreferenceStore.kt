package dev.infusion.core.database.profile

import android.content.Context
import dev.infusion.core.domain.playback.PlaybackLanguagePreferenceStore

class SharedPreferencesPlaybackLanguagePreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : PlaybackLanguagePreferenceStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun audioLanguagePreference(profileId: String): String? =
        prefs.getString(PRIMARY_AUDIO_KEY(profileId), null)

    override fun setAudioLanguagePreference(profileId: String, language: String?) {
        prefs.edit().apply {
            if (language == null) remove(PRIMARY_AUDIO_KEY(profileId)) else putString(PRIMARY_AUDIO_KEY(profileId), language)
        }.apply()
    }

    override fun secondaryAudioLanguagePreference(profileId: String): String? =
        prefs.getString(SECONDARY_AUDIO_KEY(profileId), null)

    override fun setSecondaryAudioLanguagePreference(profileId: String, language: String?) {
        prefs.edit().apply {
            if (language == null) remove(SECONDARY_AUDIO_KEY(profileId)) else putString(SECONDARY_AUDIO_KEY(profileId), language)
        }.apply()
    }

    override fun subtitleModePreference(profileId: String): String? =
        prefs.getString(SUBTITLE_MODE_KEY(profileId), null)

    override fun setSubtitleModePreference(profileId: String, mode: String?) {
        prefs.edit().apply {
            if (mode == null) remove(SUBTITLE_MODE_KEY(profileId)) else putString(SUBTITLE_MODE_KEY(profileId), mode)
        }.apply()
    }

    override fun subtitleLanguagePreference(profileId: String): String? =
        prefs.getString(SUBTITLE_PRIMARY_KEY(profileId), null)

    override fun setSubtitleLanguagePreference(profileId: String, language: String?) {
        prefs.edit().apply {
            if (language == null) remove(SUBTITLE_PRIMARY_KEY(profileId)) else putString(SUBTITLE_PRIMARY_KEY(profileId), language)
        }.apply()
    }

    override fun secondarySubtitleLanguagePreference(profileId: String): String? =
        prefs.getString(SUBTITLE_SECONDARY_KEY(profileId), null)

    override fun setSecondarySubtitleLanguagePreference(profileId: String, language: String?) {
        prefs.edit().apply {
            if (language == null) remove(SUBTITLE_SECONDARY_KEY(profileId)) else putString(SUBTITLE_SECONDARY_KEY(profileId), language)
        }.apply()
    }

    override fun subtitleSameLanguagePolicy(profileId: String): String? =
        prefs.getString(SUBTITLE_SAME_LANGUAGE_KEY(profileId), null)

    override fun setSubtitleSameLanguagePolicy(profileId: String, policy: String?) {
        prefs.edit().apply {
            if (policy == null) remove(SUBTITLE_SAME_LANGUAGE_KEY(profileId)) else putString(SUBTITLE_SAME_LANGUAGE_KEY(profileId), policy)
        }.apply()
    }

    override fun subtitleTypePreference(profileId: String): String? =
        prefs.getString(SUBTITLE_TYPE_KEY(profileId), null)

    override fun setSubtitleTypePreference(profileId: String, type: String?) {
        prefs.edit().apply {
            if (type == null) remove(SUBTITLE_TYPE_KEY(profileId)) else putString(SUBTITLE_TYPE_KEY(profileId), type)
        }.apply()
    }

    override fun clearForProfile(profileId: String) {
        prefs.edit().apply {
            remove(PRIMARY_AUDIO_KEY(profileId))
            remove(SECONDARY_AUDIO_KEY(profileId))
            remove(SUBTITLE_MODE_KEY(profileId))
            remove(SUBTITLE_PRIMARY_KEY(profileId))
            remove(SUBTITLE_SECONDARY_KEY(profileId))
            remove(SUBTITLE_SAME_LANGUAGE_KEY(profileId))
            remove(SUBTITLE_TYPE_KEY(profileId))

            remove(LEGACY_AUDIO_KEY(profileId))
            remove(LEGACY_SUBTITLE_KEY(profileId))
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "infusion_playback_language_preferences"
        fun PRIMARY_AUDIO_KEY(profileId: String) = "audio_lang_primary_v2:$profileId"
        fun SECONDARY_AUDIO_KEY(profileId: String) = "audio_lang_secondary_v2:$profileId"
        fun SUBTITLE_MODE_KEY(profileId: String) = "subtitle_mode_v1:$profileId"
        fun SUBTITLE_PRIMARY_KEY(profileId: String) = "subtitle_lang_primary_v1:$profileId"
        fun SUBTITLE_SECONDARY_KEY(profileId: String) = "subtitle_lang_secondary_v1:$profileId"
        fun SUBTITLE_SAME_LANGUAGE_KEY(profileId: String) = "subtitle_same_language_v1:$profileId"
        fun SUBTITLE_TYPE_KEY(profileId: String) = "subtitle_type_v1:$profileId"
        fun LEGACY_AUDIO_KEY(profileId: String) = "lang_audio:$profileId"
        fun LEGACY_SUBTITLE_KEY(profileId: String) = "lang_subtitle:$profileId"
    }
}
