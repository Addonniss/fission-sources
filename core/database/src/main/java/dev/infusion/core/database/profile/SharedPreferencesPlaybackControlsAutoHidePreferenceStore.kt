package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.playback.PlaybackControlsAutoHidePreferenceStore

class SharedPreferencesPlaybackControlsAutoHidePreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : PlaybackControlsAutoHidePreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun controlsAutoHide(profileId: String): String? =
        prefs.getString(key(profileId), null)

    override fun setControlsAutoHide(profileId: String, storageKey: String?) {
        prefs.edit().apply {
            if (storageKey == null) remove(key(profileId)) else putString(key(profileId), storageKey)
        }.apply()
    }

    override fun clearForProfile(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String): String = "$KEY_PREFIX$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_playback_controls_auto_hide_preferences"
        const val KEY_PREFIX = "playback_controls_auto_hide:"
    }
}
