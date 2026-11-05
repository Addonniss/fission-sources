package dev.infusion.core.database.profile

import android.content.Context
import dev.infusion.core.domain.profile.ActiveProfileStore

class SharedPreferencesActiveProfileStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : ActiveProfileStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun load(): String? =
        prefs.getString(KEY_ACTIVE_PROFILE, null)

    override fun save(profileId: String?) {
        prefs.edit().apply {
            if (profileId == null) remove(KEY_ACTIVE_PROFILE) else putString(KEY_ACTIVE_PROFILE, profileId)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "infusion_profile_state"
        const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }
}
