package dev.infusion.tracking.mdblist

import dev.infusion.core.domain.tracking.StoredPendingAuthorization
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MDBListAuthRepository(
    private val configuration: MDBListConfiguration,
    private val client: MDBListHttpClient,
    private val store: TrackingCredentialStore,
    private val providerId: String = MDBLIST_PROVIDER_ID,
    private val now: () -> Instant = Instant::now,
) {

    private val mutex = Mutex()
    private val refreshMutex = Mutex()

    fun connectionState(): TrackingConnectionState {
        store.pendingAuthorization(providerId)?.let {
            return TrackingConnectionState.AwaitingApproval(it.toAuthorization())
        }
        val credentials = store.credentials(providerId) ?: return TrackingConnectionState.Disconnected
        return TrackingConnectionState.Connected(
            accountLabel = credentials.accountLabel,
            accessExpiresAt = credentials.accessExpiresAt,
            refreshExpiresAt = credentials.refreshExpiresAt,

            reconnectRequired = credentials.refreshExpiresAt?.let { !now().isBefore(it) } ?: false,
        )
    }

    fun isConnected(): Boolean = store.credentials(providerId) != null

    fun accountId(): String? = store.credentials(providerId)?.accountId

    fun recordAccountIdentity(accountId: String?) = store.updateAccountId(providerId, accountId)

    suspend fun beginAuthorization(): TrackingDeviceAuthorization = mutex.withLock {
        store.pendingAuthorization(providerId)?.let { return@withLock it.toAuthorization() }
        if (!configuration.hasRegistration) throw MDBListAuthException(REGISTRATION_MISSING)
        val outcome = client.execute(
            MDBListRequest(
                method = MDBListMethod.POST,
                path = DEVICE_AUTHORIZATION_PATH,
                form = mapOf("client_id" to configuration.clientId, "scope" to WRITE_SCOPE),
                authenticated = false,
                retryable = true,
            ),
        )
        val response = when (outcome) {
            is MDBListOutcome.Success -> outcome.response
            is MDBListOutcome.Unauthorized -> throw MDBListAuthException(registrationMessage(outcome.reason))
            is MDBListOutcome.Rejected -> throw MDBListAuthException(registrationMessage(outcome.reason))
            is MDBListOutcome.Retry -> throw MDBListAuthException("MDBList device authorization is temporarily unavailable")
        }
        val payload = MDBListDeviceAuthorizationResponse.parse(response.body)
        val pending = payload.toStoredPending(now())
            ?: throw MDBListAuthException("MDBList returned an unusable device authorization response")
        store.savePendingAuthorization(providerId, pending, deviceCode = payload.deviceCode!!)
        pending.toAuthorization()
    }

    suspend fun pollAuthorization(): TrackingAuthorizationPoll = mutex.withLock {
        val pending = store.pendingAuthorization(providerId)
            ?: return@withLock TrackingAuthorizationPoll.Failed(NO_CHALLENGE)
        if (!now().isBefore(pending.expiresAt)) {
            store.clearPendingAuthorization(providerId)
            return@withLock TrackingAuthorizationPoll.Expired
        }
        val deviceCode = store.pendingDeviceCode(providerId) ?: run {
            store.clearPendingAuthorization(providerId)
            return@withLock TrackingAuthorizationPoll.Failed(NO_CHALLENGE)
        }
        val outcome = client.execute(
            MDBListRequest(
                method = MDBListMethod.POST,
                path = TOKEN_PATH,
                form = mapOf(
                    "grant_type" to DEVICE_CODE_GRANT,
                    "device_code" to deviceCode,
                    "client_id" to configuration.clientId,
                ),
                authenticated = false,
                devicePoll = true,
                retryable = false,
            ),
        )
        when (outcome) {
            is MDBListOutcome.Success -> completeAuthorization(outcome.response)

            is MDBListOutcome.Retry -> {
                if (outcome.reason == MDBListHttpClient.DEVICE_SLOW_DOWN) {

                    TrackingAuthorizationPoll.SlowDown(pending.intervalSeconds + SLOW_DOWN_STEP_SECONDS)
                } else {
                    TrackingAuthorizationPoll.Pending
                }
            }
            is MDBListOutcome.Unauthorized -> {
                store.clearPendingAuthorization(providerId)
                TrackingAuthorizationPoll.Failed(registrationMessage(outcome.reason))
            }
            is MDBListOutcome.Rejected -> when (outcome.reason) {
                MDBListHttpClient.DEVICE_PENDING -> TrackingAuthorizationPoll.Pending

                MDBListHttpClient.DEVICE_EXPIRED,
                MDBListHttpClient.DEVICE_NOT_FOUND,
                -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Expired
                }
                MDBListHttpClient.DEVICE_DENIED -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("The MDBList request was denied on the authorizing device")
                }
                else -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("MDBList rejected the authorization (${outcome.reason})")
                }
            }
        }
    }

    fun cancelAuthorization() = store.clearPendingAuthorization(providerId)

    suspend fun disconnect() {
        val credentials = store.credentials(providerId)
        val revokeToken = store.tokens(providerId)?.accessToken
        if (credentials != null && revokeToken != null && configuration.hasRegistration) {
            runCatching {
                client.execute(
                    MDBListRequest(
                        method = MDBListMethod.POST,
                        path = REVOKE_PATH,
                        form = mapOf("client_id" to configuration.clientId, "token" to revokeToken),
                        authenticated = false,
                        retryable = false,
                    ),
                )
            }
        }
        store.clearPendingAuthorization(providerId)
        store.clear(providerId)
    }

    suspend fun accessToken(): String? {
        val credentials = store.credentials(providerId) ?: return null
        val tokens = store.tokens(providerId) ?: return null
        if (!isRefreshDue(credentials.accessExpiresAt)) return tokens.accessToken
        return refresh()
    }

    suspend fun refresh(): String? = refreshMutex.withLock {
        val credentials = store.credentials(providerId) ?: return@withLock null
        val tokens = store.tokens(providerId) ?: return@withLock null
        val refreshToken = tokens.refreshToken ?: return@withLock null
        if (!configuration.hasRegistration) return@withLock null
        val outcome = client.execute(
            MDBListRequest(
                method = MDBListMethod.POST,
                path = TOKEN_PATH,
                form = mapOf(
                    "grant_type" to REFRESH_GRANT,
                    "refresh_token" to refreshToken,
                    "client_id" to configuration.clientId,
                ),
                authenticated = false,
                retryable = true,
            ),
        )
        when (outcome) {
            is MDBListOutcome.Success -> {
                val tokenResponse = MDBListTokenResponse.parse(outcome.response.body)
                val access = tokenResponse.accessToken
                if (access.isNullOrBlank()) return@withLock null
                val issuedAt = now()
                store.updateTokens(
                    providerId = providerId,
                    accessToken = access,

                    refreshToken = tokenResponse.refreshTokenOrNull() ?: refreshToken,
                    accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) }
                        ?: credentials.accessExpiresAt,
                    refreshExpiresAt = null,
                )
                access
            }

            is MDBListOutcome.Retry -> null

            is MDBListOutcome.Rejected,
            is MDBListOutcome.Unauthorized,
            -> {
                store.clear(providerId)
                null
            }
        }
    }

    private fun isRefreshDue(accessExpiresAt: Instant?): Boolean {
        val expiry = accessExpiresAt ?: return false
        return !now().plusSeconds(ACCESS_REFRESH_WINDOW_SECONDS).isBefore(expiry)
    }

    private suspend fun completeAuthorization(response: MDBListHttpResponse): TrackingAuthorizationPoll {
        val tokenResponse = MDBListTokenResponse.parse(response.body)
        val access = tokenResponse.accessToken
        if (access.isNullOrBlank()) {
            store.clearPendingAuthorization(providerId)
            return TrackingAuthorizationPoll.Failed("MDBList approved the request without an access token")
        }
        val issuedAt = now()
        store.save(
            providerId = providerId,
            accessToken = access,
            refreshToken = tokenResponse.refreshTokenOrNull(),

            accountLabel = null,
            accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) },

            refreshExpiresAt = null,
            scope = tokenResponse.scope,
        )
        store.clearPendingAuthorization(providerId)
        return TrackingAuthorizationPoll.Authorized
    }

    private fun StoredPendingAuthorization.toAuthorization(): TrackingDeviceAuthorization = TrackingDeviceAuthorization(
        userCode = userCode,
        verificationUri = verificationUri,
        verificationUriComplete = verificationUriComplete,
        expiresAt = expiresAt,
        intervalSeconds = intervalSeconds,
    )

    private fun registrationMessage(reason: String): String = when {
        reason.contains("invalid_request") ->
            "MDBList rejected this client registration (invalid client id)"
        reason.contains(MDBListHttpClient.INVALID_CLIENT) -> "MDBList rejected this client registration"
        else -> "MDBList rejected the authorization request ($reason)"
    }

    companion object {

        const val MDBLIST_PROVIDER_ID: String = "mdblist"

        const val DEVICE_AUTHORIZATION_PATH: String = "/oauth/device-authorization/"
        const val TOKEN_PATH: String = "/oauth/token/"
        const val REVOKE_PATH: String = "/oauth/revoke_token/"

        const val WRITE_SCOPE: String = "write"

        const val DEVICE_CODE_GRANT: String = "urn:ietf:params:oauth:grant-type:device_code"

        const val REFRESH_GRANT: String = "refresh_token"

        const val SLOW_DOWN_STEP_SECONDS: Int = 5

        const val ACCESS_REFRESH_WINDOW_SECONDS: Long = 24L * 60L * 60L

        const val REGISTRATION_MISSING: String =
            "no MDBList client registration is configured in this build"

        internal const val NO_CHALLENGE = "no MDBList authorization is in flight"
    }
}

class MDBListAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class MDBListDeviceAuthorizationResponse(
    val deviceCode: String?,
    val userCode: String?,
    val verificationUri: String?,
    val verificationUriComplete: String?,
    val expiresInSeconds: Long?,
    val intervalSeconds: Int?,
) {

    fun toStoredPending(now: Instant): StoredPendingAuthorization? {
        val code = userCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = verificationUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        deviceCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expires = expiresInSeconds?.takeIf { it > 0 } ?: return null
        return StoredPendingAuthorization(
            userCode = code,
            verificationUri = uri,

            verificationUriComplete = verificationUriComplete?.trim()?.takeIf { it.isNotEmpty() },
            expiresAt = now.plusSeconds(expires),
            intervalSeconds = (intervalSeconds ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS: Int = 5

        fun parse(body: String): MDBListDeviceAuthorizationResponse = runCatching {
            val json = JSONObject(body)
            MDBListDeviceAuthorizationResponse(
                deviceCode = json.optStringOrNull("device_code"),
                userCode = json.optStringOrNull("user_code"),
                verificationUri = json.optStringOrNull("verification_uri"),
                verificationUriComplete = json.optStringOrNull("verification_uri_complete"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                intervalSeconds = json.optIntOrNull("interval"),
            )
        }.getOrDefault(MDBListDeviceAuthorizationResponse(null, null, null, null, null, null))
    }
}

internal data class MDBListTokenResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val scope: String?,
) {

    fun refreshTokenOrNull(): String? = refreshToken?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(body: String): MDBListTokenResponse = runCatching {
            val json = JSONObject(body)
            MDBListTokenResponse(
                accessToken = json.optStringOrNull("access_token"),
                refreshToken = json.optStringOrNull("refresh_token"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                scope = json.optStringOrNull("scope"),
            )
        }.getOrDefault(MDBListTokenResponse(null, null, null, null))
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, 0L).takeIf { it != 0L }

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key, 0).takeIf { it != 0 }
