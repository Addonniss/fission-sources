package dev.infusion.tracking.trakt

import dev.infusion.core.domain.tracking.StoredPendingAuthorization
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class TraktAuthRepository(
    private val configuration: TraktConfiguration,
    private val client: TraktHttpClient,
    private val store: TrackingCredentialStore,
    private val providerId: String = TRAKT_PROVIDER_ID,
    private val now: () -> Instant = Instant::now,
) {

    private val mutex = Mutex()
    private val refreshMutex = Mutex()

    fun connectionState(): TrackingConnectionState {
        store.pendingAuthorization(providerId)?.let {
            return TrackingConnectionState.AwaitingApproval(it.toAuthorization())
        }
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
        if (!configuration.hasRegistration) throw TraktAuthException(TRAKT_REGISTRATION_MISSING)
        val outcome = client.execute(
            TraktRequest(
                method = TraktMethod.POST,
                path = DEVICE_CODE_PATH,
                json = JSONObject().put("client_id", configuration.clientId).toString(),
                authenticated = false,
                toAuthHost = true,
                retryable = true,
            ),
        )
        val response = when (outcome) {
            is TraktOutcome.Success -> outcome.response
            is TraktOutcome.Unauthorized -> throw TraktAuthException(registrationMessage(outcome.reason))
            is TraktOutcome.Rejected -> throw TraktAuthException(registrationMessage(outcome.reason))
            is TraktOutcome.Retry -> throw TraktAuthException("Trakt device authorization is temporarily unavailable")
        }
        val payload = TraktDeviceCodeResponse.parse(response.body)
        val pending = payload.toStoredPending(now())
            ?: throw TraktAuthException("Trakt returned an unusable device authorization response")
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
            TraktRequest(
                method = TraktMethod.POST,
                path = DEVICE_TOKEN_PATH,
                json = JSONObject()
                    .put("code", deviceCode)
                    .put("client_id", configuration.clientId)
                    .put("client_secret", configuration.clientSecret)
                    .toString(),
                authenticated = false,
                toAuthHost = true,
                devicePoll = true,
                retryable = false,
            ),
        )
        when (outcome) {
            is TraktOutcome.Success -> completeAuthorization(outcome.response)

            is TraktOutcome.Retry -> {
                if (outcome.reason == TraktHttpClient.DEVICE_SLOW_DOWN) {

                    TrackingAuthorizationPoll.SlowDown(pending.intervalSeconds + SLOW_DOWN_STEP_SECONDS)
                } else {
                    TrackingAuthorizationPoll.Pending
                }
            }
            is TraktOutcome.Unauthorized -> {
                store.clearPendingAuthorization(providerId)
                TrackingAuthorizationPoll.Failed(registrationMessage(outcome.reason))
            }
            is TraktOutcome.Rejected -> when (outcome.reason) {
                TraktHttpClient.DEVICE_PENDING -> TrackingAuthorizationPoll.Pending

                TraktHttpClient.DEVICE_ALREADY_USED,
                TraktHttpClient.DEVICE_EXPIRED,
                -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Expired
                }
                TraktHttpClient.DEVICE_DENIED -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("The Trakt request was denied on the authorizing device")
                }
                TraktHttpClient.DEVICE_INVALID_CODE -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("Trakt no longer recognises this code; start again")
                }
                else -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("Trakt rejected the authorization (${outcome.reason})")
                }
            }
        }
    }

    fun cancelAuthorization() = store.clearPendingAuthorization(providerId)

    suspend fun disconnect() {
        val credentials = store.credentials(providerId)
        val tokens = store.tokens(providerId)
        val revokeToken = tokens?.accessToken
        if (credentials != null && revokeToken != null && configuration.hasRegistration) {
            runCatching {
                client.execute(
                    TraktRequest(
                        method = TraktMethod.POST,
                        path = REVOKE_PATH,
                        json = JSONObject()
                            .put("token", revokeToken)
                            .put("client_id", configuration.clientId)
                            .put("client_secret", configuration.clientSecret)
                            .toString(),
                        authenticated = false,
                        toAuthHost = true,
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
            TraktRequest(
                method = TraktMethod.POST,
                path = TOKEN_PATH,
                json = JSONObject()
                    .put("refresh_token", refreshToken)
                    .put("client_id", configuration.clientId)
                    .put("client_secret", configuration.clientSecret)
                    .put("redirect_uri", configuration.redirectUri)
                    .put("grant_type", REFRESH_GRANT)
                    .toString(),
                authenticated = false,
                toAuthHost = true,
                retryable = true,
            ),
        )
        when (outcome) {
            is TraktOutcome.Success -> {
                val tokenResponse = TraktTokenResponse.parse(outcome.response.body)
                val access = tokenResponse.accessToken
                if (access.isNullOrBlank()) return@withLock null
                val issuedAt = now()
                store.updateTokens(
                    providerId = providerId,
                    accessToken = access,

                    refreshToken = tokenResponse.refreshTokenOrNull(),
                    accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) }
                        ?: credentials.accessExpiresAt,
                    refreshExpiresAt = null,
                )
                access
            }

            is TraktOutcome.Retry -> null

            is TraktOutcome.Rejected -> {
                store.clear(providerId)
                null
            }
            is TraktOutcome.Unauthorized -> {
                store.clear(providerId)
                null
            }
        }
    }

    private fun isRefreshDue(accessExpiresAt: Instant?): Boolean {
        val expiry = accessExpiresAt ?: return false
        return !now().plusSeconds(ACCESS_REFRESH_WINDOW_SECONDS).isBefore(expiry)
    }

    private suspend fun completeAuthorization(response: TraktHttpResponse): TrackingAuthorizationPoll {
        val tokenResponse = TraktTokenResponse.parse(response.body)
        val access = tokenResponse.accessToken
        if (access.isNullOrBlank()) {
            store.clearPendingAuthorization(providerId)
            return TrackingAuthorizationPoll.Failed("Trakt approved the request without an access token")
        }
        val issuedAt = now()
        store.save(
            providerId = providerId,
            accessToken = access,
            refreshToken = tokenResponse.refreshTokenOrNull(),
            accountLabel = tokenResponse.accountLabel(),
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
        reason.contains("http_403") ->
            "Trakt rejected this application (invalid API key or unapproved app)"
        reason.contains("client_id") -> "Trakt rejected this client registration"
        else -> "Trakt rejected the authorization request ($reason)"
    }

    companion object {

        const val TRAKT_PROVIDER_ID: String = "trakt"

        const val DEVICE_CODE_PATH: String = "/oauth/device/code"
        const val DEVICE_TOKEN_PATH: String = "/oauth/device/token"
        const val TOKEN_PATH: String = "/oauth/token"
        const val REVOKE_PATH: String = "/oauth/revoke"

        const val REFRESH_GRANT: String = "refresh_token"

        const val SLOW_DOWN_STEP_SECONDS: Int = 5

        const val ACCESS_REFRESH_WINDOW_SECONDS: Long = 24L * 60L * 60L

        const val TRAKT_REGISTRATION_MISSING: String =
            "no Trakt client registration is configured in this build"

        internal const val NO_CHALLENGE = "no Trakt authorization is in flight"
    }
}

class TraktAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class TraktDeviceCodeResponse(
    val deviceCode: String?,
    val userCode: String?,
    val verificationUri: String?,
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
            verificationUriComplete = null,
            expiresAt = now.plusSeconds(expires),
            intervalSeconds = (intervalSeconds ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS: Int = 5

        fun parse(body: String): TraktDeviceCodeResponse = runCatching {
            val json = JSONObject(body)
            TraktDeviceCodeResponse(
                deviceCode = json.optStringOrNull("device_code"),
                userCode = json.optStringOrNull("user_code"),
                verificationUri = json.optStringOrNull("verification_url")
                    ?: json.optStringOrNull("verification_uri"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                intervalSeconds = json.optIntOrNull("interval"),
            )
        }.getOrDefault(TraktDeviceCodeResponse(null, null, null, null, null))
    }
}

internal data class TraktTokenResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val scope: String?,
    val username: String?,
) {

    fun refreshTokenOrNull(): String? = refreshToken?.trim()?.takeIf { it.isNotEmpty() }

    fun accountLabel(): String? = username?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(body: String): TraktTokenResponse = runCatching {
            val json = JSONObject(body)
            TraktTokenResponse(
                accessToken = json.optStringOrNull("access_token"),
                refreshToken = json.optStringOrNull("refresh_token"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                scope = json.optStringOrNull("scope"),
                username = json.optStringOrNull("username"),
            )
        }.getOrDefault(TraktTokenResponse(null, null, null, null, null))
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, 0L).takeIf { it != 0L }

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key, 0).takeIf { it != 0 }
