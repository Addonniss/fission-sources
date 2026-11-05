package dev.infusion.core.database.tracking

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingPreferences
import dev.infusion.core.domain.tracking.TrackingPreferencesStore
import dev.infusion.core.domain.tracking.TrackingSelection

class SharedPreferencesTrackingPreferencesStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : TrackingPreferencesStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun current(): TrackingPreferences =
        TrackingPreferences(selection = selectionAt(prefs.getString(key(profileScope.activeProfileId), null)))

    override fun record(selection: TrackingSelection) {
        prefs.edit().putString(key(profileScope.activeProfileId), encode(selection)).apply()
    }

    override fun clearProfile(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    override fun clearAllProfiles() {
        val prefix = "$PREFS_KEY_PREFIX."
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    private fun key(profileId: String) = "$PREFS_KEY_PREFIX.$profileId"

    private fun encode(selection: TrackingSelection): String = when (selection) {
        TrackingSelection.OnDeviceOnly -> VALUE_ON_DEVICE_ONLY
        is TrackingSelection.Tracker -> "$VALUE_TRACKER_PREFIX${selection.trackerId}"
    }

    private fun selectionAt(stored: String?): TrackingSelection {
        if (stored == null || stored == VALUE_ON_DEVICE_ONLY) return TrackingSelection.OnDeviceOnly
        if (stored.startsWith(VALUE_TRACKER_PREFIX)) {
            val trackerId = stored.removePrefix(VALUE_TRACKER_PREFIX).trim()
            if (trackerId.isNotEmpty()) return TrackingSelection.Tracker(trackerId)
        }
        return TrackingSelection.OnDeviceOnly
    }

    companion object {

        const val PREFS_NAME: String = "infusion_tracking_preferences"

        private const val PREFS_KEY_PREFIX = "tracking-selection"

        private const val VALUE_ON_DEVICE_ONLY = "on_device_only"
        private const val VALUE_TRACKER_PREFIX = "tracker."
    }
}
