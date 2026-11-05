package dev.infusion.tracking.simkl

import dev.infusion.core.domain.tracking.StoredPendingAuthorization
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import dev.infusion.core.domain.tracking.TrackingTokenMaterial
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SimklAuthRepository(
    private val configuration: SimklConfiguration,
    private val client: SimklHttpClient,
    private val store: TrackingCredentialStore,
    private val providerId: String = SIMKL_PROVIDER_ID,
    private val now: () -> Instant = Instant::now,
) {

    private val mutex = Mutex()
    private val refreshMutex = Mutex()

    fun connectionState(): TrackingConnectionState {
        store.pendingAuthorization(providerId)?.let { return TrackingConnectionState.AwaitingApproval(it.toAuthorization()) }
        val credentials = store.credentials(providerId) ?: return TrackingConnectionState.Disconnected
        val nowMs = now()
        val refreshExpiry = credentials.refreshExpiresAt
        return TrackingConnectionState.Connected(
            accountLabel = credentials.accountLabel,
            accessExpiresAt = credentials.accessExpiresAt,
            refreshExpiresAt = refreshExpiry,

            reconnectRequired = refreshExpiry != null && !nowMs.isBefore(refreshExpiry),
        )
    }

    fun isConnected(): Boolean = store.credentials(providerId) != null

    fun accountId(): String? = store.credentials(providerId)?.accountId

    fun recordAccountIdentity(accountId: String?) = store.updateAccountId(providerId, accountId)

    suspend fun beginAuthorization(): TrackingDeviceAuthorization = mutex.withLock {
        store.pendingAuthorization(providerId)?.let { return@withLock it.toAuthorization() }
        if (!configuration.hasClientId) throw SimklAuthException(SIMKL_CLIENT_ID_MISSING)
        val outcome = client.execute(
            SimklRequest(
                method = SimklMethod.POST,
                path = DEVICE_PATH,
                form = mapOf("client_id" to configuration.clientId, "scope" to REQUESTED_SCOPE),
                authenticated = false,
                retryable = true,
            ),
        )
        val response = when (outcome) {
            is SimklOutcome.Success -> outcome.response
            is SimklOutcome.Unauthorized -> throw SimklAuthException(clientRegistrationMessage(outcome.reason))
            is SimklOutcome.Rejected -> throw SimklAuthException(clientRegistrationMessage(outcome.reason))
            is SimklOutcome.Retry -> throw SimklAuthException("Simkl device authorization is temporarily unavailable")
        }
        val payload = SimklDeviceCodeResponse.parse(response.body)
        val pending = payload.toStoredPending(now())
            ?: throw SimklAuthException("Simkl returned an unusable device authorization response")
        store.savePendingAuthorization(providerId, pending, deviceCode = payload.deviceCode!!)
        pending.toAuthorization()
    }

    suspend fun pollAuthorization(): TrackingAuthorizationPoll = mutex.withLock {
        val pending = store.pendingAuthorization(providerId) ?: return@withLock TrackingAuthorizationPoll.Failed(NO_CHALLENGE)
        if (!now().isBefore(pending.expiresAt)) {
            store.clearPendingAuthorization(providerId)
            return@withLock TrackingAuthorizationPoll.Expired
        }
        val deviceCode = store.pendingDeviceCode(providerId) ?: run {
            store.clearPendingAuthorization(providerId)
            return@withLock TrackingAuthorizationPoll.Failed(NO_CHALLENGE)
        }
        val outcome = client.execute(
            SimklRequest(
                method = SimklMethod.POST,
                path = TOKEN_PATH,
                form = mapOf(
                    "grant_type" to DEVICE_GRANT,
                    "client_id" to configuration.clientId,
                    "device_code" to deviceCode,
                ),
                authenticated = false,
                retryable = false,
            ),
        )
        when (outcome) {
            is SimklOutcome.Success -> completeAuthorization(outcome.response)

            is SimklOutcome.Retry -> TrackingAuthorizationPoll.Pending
            is SimklOutcome.Unauthorized -> {
                store.clearPendingAuthorization(providerId)
                TrackingAuthorizationPoll.Failed(clientRegistrationMessage(outcome.reason))
            }
            is SimklOutcome.Rejected -> when (outcome.reason) {
                ERROR_AUTHORIZATION_PENDING -> TrackingAuthorizationPoll.Pending

                ERROR_SLOW_DOWN -> TrackingAuthorizationPoll.SlowDown(pending.intervalSeconds + SLOW_DOWN_STEP_SECONDS)
                ERROR_EXPIRED_TOKEN -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Expired
                }
                else -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed(outcome.reason)
                }
            }
        }
    }

    fun cancelAuthorization() = store.clearPendingAuthorization(providerId)

    suspend fun disconnect() {
        val credentials = store.credentials(providerId)
        val tokens = store.tokens(providerId)
        val revokeToken = tokens?.refreshToken ?: tokens?.accessToken
        if (credentials != null && revokeToken != null && configuration.hasClientId) {
            runCatching {
                client.execute(
                    SimklRequest(
                        method = SimklMethod.POST,
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
        if (!configuration.hasClientId) return@withLock null
        val outcome = client.execute(
            SimklRequest(
                method = SimklMethod.POST,
                path = TOKEN_PATH,
                form = mapOf(
                    "grant_type" to REFRESH_GRANT,
                    "client_id" to configuration.clientId,
                    "refresh_token" to refreshToken,
                ),
                authenticated = false,
                retryable = true,
            ),
        )
        when (outcome) {
            is SimklOutcome.Success -> {
                val tokenResponse = SimklTokenResponse.parse(outcome.response.body)
                val access = tokenResponse.accessToken
                if (access.isNullOrBlank()) return@withLock null
                val issuedAt = now()
                store.updateTokens(
                    providerId = providerId,
                    accessToken = access,

                    refreshToken = tokenResponse.refreshToken ?: refreshToken,
                    accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) }
                        ?: credentials.accessExpiresAt,

                    refreshExpiresAt = issuedAt.plusSeconds(SimklTokenResponse.REFRESH_TOKEN_LIFETIME_SECONDS),
                )
                access
            }

            is SimklOutcome.Retry -> null

            is SimklOutcome.Unauthorized -> {
                store.clear(providerId)
                null
            }
            is SimklOutcome.Rejected -> {
                store.clear(providerId)
                null
            }
        }
    }

    private fun isRefreshDue(accessExpiresAt: Instant?): Boolean {
        val expiry = accessExpiresAt ?: return false
        return !now().plusSeconds(ACCESS_REFRESH_WINDOW_SECONDS).isBefore(expiry)
    }

    private suspend fun completeAuthorization(response: SimklHttpResponse): TrackingAuthorizationPoll {
        val tokenResponse = SimklTokenResponse.parse(response.body)
        val access = tokenResponse.accessToken
        if (access.isNullOrBlank()) {
            store.clearPendingAuthorization(providerId)
            return TrackingAuthorizationPoll.Failed("Simkl approved the request without an access token")
        }
        val issuedAt = now()
        store.save(
            providerId = providerId,
            accessToken = access,
            refreshToken = tokenResponse.refreshToken,
            accountLabel = null,
            accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) },
            refreshExpiresAt = tokenResponse.refreshExpiresInSeconds?.let { issuedAt.plusSeconds(it) },
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

    private fun clientRegistrationMessage(reason: String): String = when (reason) {

        "invalid_client" -> "Simkl rejected this client registration (invalid_client)"
        else -> "Simkl rejected the authorization request ($reason)"
    }

    companion object {

        const val SIMKL_PROVIDER_ID: String = "simkl"

        const val DEVICE_PATH: String = "/oauth2/device"
        const val TOKEN_PATH: String = "/oauth2/token"
        const val REVOKE_PATH: String = "/oauth2/revoke"

        const val DEVICE_GRANT: String = "urn:ietf:params:oauth:grant-type:device_code"
        const val REFRESH_GRANT: String = "refresh_token"

        const val REQUESTED_SCOPE: String = "media:read media:write"

        const val ERROR_AUTHORIZATION_PENDING: String = "authorization_pending"
        const val ERROR_SLOW_DOWN: String = "slow_down"
        const val ERROR_EXPIRED_TOKEN: String = "expired_token"

        const val SLOW_DOWN_STEP_SECONDS: Int = 5

        const val ACCESS_REFRESH_WINDOW_SECONDS: Long = 24L * 60L * 60L

        const val SIMKL_CLIENT_ID_MISSING: String =
            "no Simkl client registration is configured in this build"

        internal const val NO_CHALLENGE = "no Simkl authorization is in flight"
    }
}

class SimklAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class SimklDeviceCodeResponse(
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

        fun parse(body: String): SimklDeviceCodeResponse = runCatching {
            val json = org.json.JSONObject(body)
            SimklDeviceCodeResponse(
                deviceCode = json.optStringOrNull("device_code"),
                userCode = json.optStringOrNull("user_code"),
                verificationUri = json.optStringOrNull("verification_uri")
                    ?: json.optStringOrNull("verification_url"),
                verificationUriComplete = json.optStringOrNull("verification_uri_complete"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                intervalSeconds = json.optIntOrNull("interval"),
            )
        }.getOrDefault(SimklDeviceCodeResponse(null, null, null, null, null, null))
    }
}

internal data class SimklTokenResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val refreshExpiresInSeconds: Long?,
    val scope: String?,
) {
    companion object {

        const val REFRESH_TOKEN_LIFETIME_SECONDS: Long = 180L * 24L * 60L * 60L

        fun parse(body: String): SimklTokenResponse = runCatching {
            val json = org.json.JSONObject(body)
            val hasRefresh = json.optStringOrNull("refresh_token") != null
            SimklTokenResponse(
                accessToken = json.optStringOrNull("access_token"),
                refreshToken = json.optStringOrNull("refresh_token"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                refreshExpiresInSeconds = if (hasRefresh) REFRESH_TOKEN_LIFETIME_SECONDS else null,
                scope = json.optStringOrNull("scope"),
            )
        }.getOrDefault(SimklTokenResponse(null, null, null, null, null))
    }
}

internal fun org.json.JSONObject.optStringOrNull(key: String): String? =
    if (!has(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() }

internal fun org.json.JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, 0L).takeIf { it != 0L }

internal fun org.json.JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key, 0).takeIf { it != 0 }
