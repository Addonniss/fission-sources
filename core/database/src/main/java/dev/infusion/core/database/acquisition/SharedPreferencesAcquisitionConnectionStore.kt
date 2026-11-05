package dev.infusion.core.database.acquisition

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.acquisition.AcquisitionConnection
import dev.infusion.core.domain.acquisition.AcquisitionConnectionStore
import dev.infusion.core.domain.acquisition.StoredAcquisitionConnection
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.security.SecretContext
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SharedPreferencesAcquisitionConnectionStore(
    context: Context,
    private val profileScope: ProfileScope,
    private val secretStore: () -> SecretStore,
    prefsName: String = PREFS_NAME,
) : AcquisitionConnectionStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _connectionChanges = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun connectionChanges(): Flow<Unit> = _connectionChanges.asSharedFlow()

    private fun notifyConnectionChanged() {
        _connectionChanges.tryEmit(Unit)
    }

    override fun connection(serviceId: String): StoredAcquisitionConnection? =
        connectionFor(serviceId, profileScope.activeProfileId)

    private fun connectionFor(serviceId: String, profileId: String): StoredAcquisitionConnection? {
        val secretId = prefs.getString(key(serviceId, profileId, KEY_SECRET_REF), null) ?: return null
        val url = prefs.getString(key(serviceId, profileId, KEY_URL), null) ?: return null
        val root = prefs.getString(key(serviceId, profileId, KEY_ROOT), null) ?: return null
        val profileIdValue = prefs.getIntOrNull(key(serviceId, profileId, KEY_PROFILE_ID)) ?: return null
        val profileLabel = prefs.getString(key(serviceId, profileId, KEY_PROFILE_LABEL), null) ?: return null
        return StoredAcquisitionConnection(
            connection = AcquisitionConnection(
                serviceId = serviceId,
                url = url,
                rootFolderPath = root,
                qualityProfileId = profileIdValue,
                qualityProfileLabel = profileLabel,
                serverVersion = prefs.getString(key(serviceId, profileId, KEY_VERSION), null),
            ),
            secretReferenceId = secretId,
        )
    }

    override fun apiKey(serviceId: String): String? {
        val profileId = profileScope.activeProfileId
        val secretId = prefs.getString(key(serviceId, profileId, KEY_SECRET_REF), null) ?: return null
        val bytes = secretStore().get(SecretReference(secretId)) ?: return null
        return String(bytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    override fun save(serviceId: String, connection: AcquisitionConnection, apiKey: String) {
        require(connection.serviceId == serviceId) { "connection.serviceId must match serviceId" }
        require(apiKey.isNotBlank()) { "api key must not be blank" }
        val profileId = profileScope.activeProfileId
        val previous = connectionFor(serviceId, profileId)

        val reference = secretStore().put(
            apiKey.toByteArray(StandardCharsets.UTF_8),
            SecretContext(purpose = SECRET_PURPOSE, sourceId = serviceId),
        )
        prefs.edit().apply {
            putString(key(serviceId, profileId, KEY_SECRET_REF), reference.id)
            putString(key(serviceId, profileId, KEY_URL), connection.url)
            putString(key(serviceId, profileId, KEY_ROOT), connection.rootFolderPath)
            putInt(key(serviceId, profileId, KEY_PROFILE_ID), connection.qualityProfileId)
            putString(key(serviceId, profileId, KEY_PROFILE_LABEL), connection.qualityProfileLabel)
            putOrRemove(key(serviceId, profileId, KEY_VERSION), connection.serverVersion)
        }.apply()

        previous?.secretReferenceId?.takeIf { it != reference.id }?.let { stale ->
            secretStore().delete(SecretReference(stale))
        }
        notifyConnectionChanged()
    }

    override fun clear(serviceId: String) = clearProfile(serviceId, profileScope.activeProfileId)

    override fun clearProfile(profileId: String) {
        val prefix = "$PREFS_KEY_PREFIX."
        val services = prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { rest -> rest.removePrefix(prefix).substringBefore('.').takeIf { it.isNotEmpty() } }
            .distinct()
        services.forEach { serviceId -> clearProfile(serviceId, profileId) }
    }

    private fun clearProfile(serviceId: String, profileId: String) {
        prefs.getString(key(serviceId, profileId, KEY_SECRET_REF), null)?.let { secretId ->
            secretStore().delete(SecretReference(secretId))
        }
        prefs.edit().apply {
            val prefix = "$PREFS_KEY_PREFIX.$serviceId.$profileId."
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
        notifyConnectionChanged()
    }

    override fun clearActiveProfile() {
        val profileId = profileScope.activeProfileId
        val prefix = "$PREFS_KEY_PREFIX."
        val services = prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).substringBefore('.').takeIf { s -> s.isNotEmpty() } }
            .toSet()
        services.forEach { serviceId -> clearProfile(serviceId, profileId) }
    }

    private fun key(serviceId: String, profileId: String, suffix: String) =
        "$PREFS_KEY_PREFIX.$serviceId.$profileId.$suffix"

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private fun SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0).takeIf { it > 0 } else null

    companion object {

        const val PREFS_NAME: String = "infusion_acquisition_connections"

        private const val PREFS_KEY_PREFIX = "acquisition"
        private const val SECRET_PURPOSE = "acquisition-api-key"

        private const val KEY_SECRET_REF = "secret_ref"
        private const val KEY_URL = "url"
        private const val KEY_ROOT = "root"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_PROFILE_LABEL = "profile_label"
        private const val KEY_VERSION = "version"
    }
}
