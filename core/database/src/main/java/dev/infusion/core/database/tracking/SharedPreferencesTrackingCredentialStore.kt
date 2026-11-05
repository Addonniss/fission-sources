package dev.infusion.core.database.tracking

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.StoredPendingAuthorization
import dev.infusion.core.domain.tracking.StoredTrackingCredentials
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingTokenMaterial
import dev.infusion.core.security.SecretContext
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SharedPreferencesTrackingCredentialStore(
    context: Context,
    private val profileScope: ProfileScope,
    private val secretStore: () -> SecretStore,
    prefsName: String = PREFS_NAME,
    private val now: () -> Instant = Instant::now,
) : TrackingCredentialStore {

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

    override fun credentials(providerId: String): StoredTrackingCredentials? =
        credentialsFor(providerId, profileScope.activeProfileId)

    private fun credentialsFor(providerId: String, profileId: String): StoredTrackingCredentials? {
        val secretId = prefs.getString(key(providerId, profileId, KEY_SECRET_REF), null) ?: return null
        return StoredTrackingCredentials(
            providerId = providerId,
            accountLabel = prefs.getString(key(providerId, profileId, KEY_ACCOUNT), null),
            accessExpiresAt = prefs.getLongOrNull(key(providerId, profileId, KEY_ACCESS_EXPIRES))
                ?.let(Instant::ofEpochMilli),
            refreshExpiresAt = prefs.getLongOrNull(key(providerId, profileId, KEY_REFRESH_EXPIRES))
                ?.let(Instant::ofEpochMilli),
            scope = prefs.getString(key(providerId, profileId, KEY_SCOPE), null),
            secretReferenceId = secretId,
            accountId = prefs.getString(key(providerId, profileId, KEY_ACCOUNT_ID), null),
        )
    }

    override fun updateAccountId(providerId: String, accountId: String?) {
        val profileId = profileScope.activeProfileId
        val current = credentialsFor(providerId, profileId) ?: return
        val next = accountId?.trim()?.takeIf { it.isNotEmpty() }
        if (current.accountId == next) return
        prefs.edit()
            .apply { putOrRemove(key(providerId, profileId, KEY_ACCOUNT_ID), next) }
            .apply()
    }

    override fun save(
        providerId: String,
        accessToken: String,
        refreshToken: String?,
        accountLabel: String?,
        accessExpiresAt: Instant?,
        refreshExpiresAt: Instant?,
        scope: String?,
    ) {
        require(accessToken.isNotBlank()) { "access token must not be blank" }
        val profileId = profileScope.activeProfileId
        val previous = credentialsFor(providerId, profileId)
        val reference = secretStore().put(
            TokenCodec.encode(accessToken, refreshToken),
            SecretContext(purpose = SECRET_PURPOSE, sourceId = providerId),
        )
        prefs.edit()
            .putString(key(providerId, profileId, KEY_SECRET_REF), reference.id)
            .apply {
                putOrRemove(key(providerId, profileId, KEY_ACCOUNT), accountLabel)
                putOrRemove(key(providerId, profileId, KEY_SCOPE), scope)

                remove(key(providerId, profileId, KEY_ACCOUNT_ID))
                putOrRemove(key(providerId, profileId, KEY_ACCESS_EXPIRES), accessExpiresAt?.toEpochMilli())
                putOrRemove(key(providerId, profileId, KEY_REFRESH_EXPIRES), refreshExpiresAt?.toEpochMilli())
            }
            .apply()

        previous?.secretReferenceId?.takeIf { it != reference.id }?.let { stale ->
            secretStore().delete(SecretReference(stale))
        }

        notifyConnectionChanged()
    }

    override fun updateTokens(
        providerId: String,
        accessToken: String,
        refreshToken: String?,
        accessExpiresAt: Instant?,
        refreshExpiresAt: Instant?,
    ) {
        val profileId = profileScope.activeProfileId
        val existing = credentialsFor(providerId, profileId) ?: return
        secretStore().put(
            TokenCodec.encode(accessToken, refreshToken),
            SecretContext(purpose = SECRET_PURPOSE, sourceId = providerId),
        ).let { reference ->
            prefs.edit()
                .putString(key(providerId, profileId, KEY_SECRET_REF), reference.id)
                .apply {
                    putOrRemove(key(providerId, profileId, KEY_ACCESS_EXPIRES), accessExpiresAt?.toEpochMilli())
                    putOrRemove(key(providerId, profileId, KEY_REFRESH_EXPIRES), refreshExpiresAt?.toEpochMilli())
                }
                .apply()
            if (reference.id != existing.secretReferenceId) {
                secretStore().delete(SecretReference(existing.secretReferenceId))
            }

            notifyConnectionChanged()
        }
    }

    override fun tokens(providerId: String): TrackingTokenMaterial? {
        val profileId = profileScope.activeProfileId
        val secretId = prefs.getString(key(providerId, profileId, KEY_SECRET_REF), null) ?: return null
        val bytes = secretStore().get(SecretReference(secretId)) ?: return null
        return TokenCodec.decode(bytes)
    }

    override fun clear(providerId: String) = clearProfile(providerId, profileScope.activeProfileId)

    override fun clearProfile(providerId: String, profileId: String) {
        prefs.getString(key(providerId, profileId, KEY_SECRET_REF), null)?.let { secretId ->
            secretStore().delete(SecretReference(secretId))
        }
        prefs.getString(key(providerId, profileId, KEY_PENDING_DEVICE_REF), null)?.let { secretId ->
            secretStore().delete(SecretReference(secretId))
        }
        prefs.edit().apply {
            val prefix = "$PREFS_KEY_PREFIX.$providerId.$profileId."
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()

        notifyConnectionChanged()
    }

    override fun clearAllProfiles(providerId: String) {
        val prefix = "$PREFS_KEY_PREFIX.$providerId."
        val profiles = prefs.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).substringBefore('.') }
            .toSet()
        profiles.forEach { profileId -> clearProfile(providerId, profileId) }
    }

    override fun pendingAuthorization(providerId: String): StoredPendingAuthorization? {
        val profileId = profileScope.activeProfileId
        val userCode = prefs.getString(key(providerId, profileId, KEY_PENDING_USER_CODE), null) ?: return null
        val expiresAt = prefs.getLongOrNull(key(providerId, profileId, KEY_PENDING_EXPIRES)) ?: return null
        val interval = prefs.getIntOrNull(key(providerId, profileId, KEY_PENDING_INTERVAL)) ?: return null
        if (now().toEpochMilli() >= expiresAt) {

            clearPendingAuthorization(providerId)
            return null
        }
        return StoredPendingAuthorization(
            userCode = userCode,
            verificationUri = prefs.getString(key(providerId, profileId, KEY_PENDING_VERIFY_URI), null).orEmpty(),
            verificationUriComplete = prefs.getString(key(providerId, profileId, KEY_PENDING_VERIFY_URI_COMPLETE), null),
            expiresAt = Instant.ofEpochMilli(expiresAt),
            intervalSeconds = interval,
        )
    }

    override fun savePendingAuthorization(
        providerId: String,
        pending: StoredPendingAuthorization,
        deviceCode: String,
    ) {
        require(deviceCode.isNotBlank()) { "device code must not be blank" }
        val profileId = profileScope.activeProfileId
        val previous = prefs.getString(key(providerId, profileId, KEY_PENDING_DEVICE_REF), null)
        val reference = secretStore().put(
            deviceCode.toByteArray(StandardCharsets.UTF_8),
            SecretContext(purpose = SECRET_PURPOSE, sourceId = providerId),
        )
        prefs.edit().apply {
            putString(key(providerId, profileId, KEY_PENDING_USER_CODE), pending.userCode)
            putOrRemove(key(providerId, profileId, KEY_PENDING_VERIFY_URI), pending.verificationUri)
            putOrRemove(key(providerId, profileId, KEY_PENDING_VERIFY_URI_COMPLETE), pending.verificationUriComplete)
            putLong(key(providerId, profileId, KEY_PENDING_EXPIRES), pending.expiresAt.toEpochMilli())
            putInt(key(providerId, profileId, KEY_PENDING_INTERVAL), pending.intervalSeconds)
            putString(key(providerId, profileId, KEY_PENDING_DEVICE_REF), reference.id)
        }.apply()
        if (previous != null && previous != reference.id) secretStore().delete(SecretReference(previous))
        notifyConnectionChanged()
    }

    override fun pendingDeviceCode(providerId: String): String? {
        val profileId = profileScope.activeProfileId
        val secretId = prefs.getString(key(providerId, profileId, KEY_PENDING_DEVICE_REF), null) ?: return null
        val bytes = secretStore().get(SecretReference(secretId)) ?: return null
        return String(bytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    override fun clearPendingAuthorization(providerId: String) {
        val profileId = profileScope.activeProfileId
        prefs.getString(key(providerId, profileId, KEY_PENDING_DEVICE_REF), null)?.let { secretId ->
            secretStore().delete(SecretReference(secretId))
        }
        prefs.edit().apply {
            remove(key(providerId, profileId, KEY_PENDING_USER_CODE))
            remove(key(providerId, profileId, KEY_PENDING_VERIFY_URI))
            remove(key(providerId, profileId, KEY_PENDING_VERIFY_URI_COMPLETE))
            remove(key(providerId, profileId, KEY_PENDING_EXPIRES))
            remove(key(providerId, profileId, KEY_PENDING_INTERVAL))
            remove(key(providerId, profileId, KEY_PENDING_DEVICE_REF))
        }.apply()
        notifyConnectionChanged()
    }

    private fun key(providerId: String, profileId: String, suffix: String) =
        "$PREFS_KEY_PREFIX.$providerId.$profileId.$suffix"

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: Long?): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L).takeIf { it > 0L } else null

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private object TokenCodec {
        fun encode(accessToken: String, refreshToken: String?): ByteArray {
            require('\n' !in accessToken) { "access token must be a single line" }
            require(refreshToken == null || '\n' !in refreshToken) { "refresh token must be a single line" }
            return "${accessToken}\n${refreshToken.orEmpty()}".toByteArray(StandardCharsets.UTF_8)
        }

        fun decode(bytes: ByteArray): TrackingTokenMaterial? {
            val text = String(bytes, StandardCharsets.UTF_8)
            val access = text.substringBefore('\n').trim()
            if (access.isEmpty()) return null
            val refresh = text.substringAfter('\n', "").trim()
            return TrackingTokenMaterial(accessToken = access, refreshToken = refresh.takeIf { it.isNotEmpty() })
        }
    }

    companion object {

        const val PREFS_NAME: String = "infusion_tracking_credentials"

        private const val PREFS_KEY_PREFIX = "tracking"
        private const val SECRET_PURPOSE = "tracking-token"

        private const val KEY_SECRET_REF = "secret_ref"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_ACCESS_EXPIRES = "access_expires_ms"
        private const val KEY_REFRESH_EXPIRES = "refresh_expires_ms"
        private const val KEY_SCOPE = "scope"
        private const val KEY_PENDING_USER_CODE = "pending_user_code"
        private const val KEY_PENDING_VERIFY_URI = "pending_verify_uri"
        private const val KEY_PENDING_VERIFY_URI_COMPLETE = "pending_verify_uri_complete"
        private const val KEY_PENDING_EXPIRES = "pending_expires_ms"
        private const val KEY_PENDING_INTERVAL = "pending_interval_s"
        private const val KEY_PENDING_DEVICE_REF = "pending_device_ref"
    }
}
