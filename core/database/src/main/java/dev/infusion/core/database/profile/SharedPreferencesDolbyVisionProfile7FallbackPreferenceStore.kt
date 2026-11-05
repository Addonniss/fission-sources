package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.DolbyVisionProfile7FallbackPreferenceStore

class SharedPreferencesDolbyVisionProfile7FallbackPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : DolbyVisionProfile7FallbackPreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun fallback(): DolbyVisionProfile7Fallback =
        DolbyVisionProfile7Fallback.orDefault(prefs.getString(DOLBY_VISION_PROFILE7_FALLBACK_KEY, null))

    override fun setFallback(fallback: DolbyVisionProfile7Fallback) {
        prefs.edit()
            .putString(DOLBY_VISION_PROFILE7_FALLBACK_KEY, fallback.storageKey)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "infusion_playback_preferences"
        const val DOLBY_VISION_PROFILE7_FALLBACK_KEY = "playback_dv_profile7_fallback"
    }
}
