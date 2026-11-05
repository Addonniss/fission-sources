package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.playback.api.HdrSubtitleDimming
import dev.infusion.core.playback.api.HdrSubtitleDimmingPreferenceStore

class SharedPreferencesHdrSubtitleDimmingPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : HdrSubtitleDimmingPreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun dimmingFor(profileId: String): HdrSubtitleDimming =
        HdrSubtitleDimming.fromStored(prefs.getString(dimmingKey(profileId), null))

    override fun setDimming(profileId: String, dimming: HdrSubtitleDimming) {
        prefs.edit()
            .putString(dimmingKey(profileId), dimming.name)
            .apply()
    }

    override fun clearForProfile(profileId: String) {
        prefs.edit()
            .remove(dimmingKey(profileId))
            .apply()
    }

    private fun dimmingKey(profileId: String): String = "$DIMMING_PREFIX$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_hdr_subtitle_dimming_preferences"
        const val DIMMING_PREFIX = "hdr_subtitle_dimming_v1:"
    }
}
