package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.playback.api.DisplayMatchingPolicy
import dev.infusion.core.playback.api.DisplayMatchingPreferenceStore

class SharedPreferencesDisplayMatchingPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : DisplayMatchingPreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun policy(): DisplayMatchingPolicy =
        DisplayMatchingPolicy.orDefault(prefs.getString(DISPLAY_MATCHING_KEY, null))

    override fun setPolicy(policy: DisplayMatchingPolicy) {
        prefs.edit()
            .putString(DISPLAY_MATCHING_KEY, policy.storageKey)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "infusion_playback_preferences"
        const val DISPLAY_MATCHING_KEY = "playback_display_matching"
    }
}
