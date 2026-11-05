package dev.infusion.core.database.profile

import android.content.Context
import dev.infusion.core.domain.catalog.VersionPreference
import dev.infusion.core.domain.catalog.VersionPreferenceStore
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.SourceId
import java.util.UUID

class SharedPreferencesVersionPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : VersionPreferenceStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun preferredVersion(profileId: String, unitKey: String): VersionPreference? {
        val raw = prefs.getString(key(profileId, unitKey), null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        return try {
            VersionPreference(
                editionId = EditionId(UUID.fromString(parts[0])),
                sourceId = SourceId(UUID.fromString(parts[1])),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun setPreferredVersion(profileId: String, unitKey: String, preference: VersionPreference?) {
        prefs.edit().apply {
            if (preference == null) {
                remove(key(profileId, unitKey))
            } else {
                putString(
                    key(profileId, unitKey),
                    "${preference.editionId.value}|${preference.sourceId.value}",
                )
            }
        }.apply()
    }

    override fun clearForSource(sourceId: String) {
        val removed = prefs.all.keys
            .filter { it.startsWith(PREF_PREFIX) }
            .filter { key ->
                val raw = prefs.getString(key, null)
                raw?.endsWith("|$sourceId") == true
            }
        prefs.edit().apply {
            removed.forEach { remove(it) }
        }.apply()
    }

    private fun key(profileId: String, unitKey: String): String = "$PREF_PREFIX$profileId:$unitKey"

    private companion object {
        const val PREFS_NAME = "infusion_version_preferences"
        const val PREF_PREFIX = "version_pref:"
    }
}
