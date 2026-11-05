package dev.infusion.core.database.profile

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.ratings.RatingsCredentialStore
import dev.infusion.core.security.SecretContext
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import java.nio.charset.StandardCharsets

class SharedPreferencesRatingsCredentialStore(
    context: Context,
    private val profileScope: ProfileScope,
    private val secretStore: () -> SecretStore,
    prefsName: String = PREFS_NAME,
) : RatingsCredentialStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun hasOmdbKey(): Boolean = referenceId(profileScope.activeProfileId) != null

    override fun saveOmdbKey(key: String) {
        require(key.isNotBlank()) { "an OMDb key must not be blank" }
        val profileId = profileScope.activeProfileId
        val previous = referenceId(profileId)
        val reference = secretStore().put(
            key.trim().toByteArray(StandardCharsets.UTF_8),
            SecretContext(purpose = SECRET_PURPOSE, sourceId = OMDb_SOURCE_ID),
        )
        prefs.edit().putString(key(profileId), reference.id).apply()

        previous?.takeIf { it != reference.id }?.let { secretStore().delete(SecretReference(it)) }
    }

    override fun removeOmdbKey() {
        val profileId = profileScope.activeProfileId
        referenceId(profileId)?.let { secretStore().delete(SecretReference(it)) }
        prefs.edit().remove(key(profileId)).apply()
    }

    override fun omdbKey(): String? {
        val id = referenceId(profileScope.activeProfileId) ?: return null
        val bytes = secretStore().get(SecretReference(id)) ?: return null
        return String(bytes, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
    }

    override fun clearProfile(profileId: String) {
        referenceId(profileId)?.let { secretStore().delete(SecretReference(it)) }
        prefs.edit().remove(key(profileId)).apply()
    }

    override fun clearAllProfiles() {
        val prefix = "$PREFS_KEY_PREFIX."
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { entryKey ->
                val profileId = entryKey.removePrefix(prefix)
                referenceId(profileId)?.let { secretStore().delete(SecretReference(it)) }
                remove(entryKey)
            }
        }.apply()
    }

    private fun referenceId(profileId: String): String? =
        prefs.getString(key(profileId), null)?.takeIf { it.isNotBlank() }

    private fun key(profileId: String) = "$PREFS_KEY_PREFIX.$profileId"

    companion object {
        const val PREFS_NAME: String = "infusion_ratings_credentials"

        private const val PREFS_KEY_PREFIX = "ratings-omdb-ref"
        private const val SECRET_PURPOSE = "ratings-omdb"
        private const val OMDb_SOURCE_ID = "omdb"
    }
}
