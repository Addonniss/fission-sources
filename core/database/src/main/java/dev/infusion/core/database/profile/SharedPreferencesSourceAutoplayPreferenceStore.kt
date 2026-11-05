package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.playback.SourceAutoplayPreferenceStore

class SharedPreferencesSourceAutoplayPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : SourceAutoplayPreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun policy(profileId: String): String? =
        prefs.getString(key(KEY_POLICY, profileId), null)

    override fun setPolicy(profileId: String, value: String?) =
        write(key(KEY_POLICY, profileId), value)

    override fun preferJellyfin(profileId: String): String? =
        prefs.getString(key(KEY_PREFER_JELLYFIN, profileId), null)

    override fun setPreferJellyfin(profileId: String, value: String?) =
        write(key(KEY_PREFER_JELLYFIN, profileId), value)

    override fun clearForProfile(profileId: String) {
        prefs.edit().apply {
            remove(key(KEY_POLICY, profileId))
            remove(key(KEY_PREFER_JELLYFIN, profileId))
        }.apply()
    }

    private fun write(fullKey: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(fullKey) else putString(fullKey, value)
        }.apply()
    }

    private fun key(prefix: String, profileId: String): String = "$prefix$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_playback_source_autoplay_preferences"
        const val KEY_POLICY = "source_autoplay_policy:"
        const val KEY_PREFER_JELLYFIN = "source_autoplay_prefer_jellyfin:"
    }
}
