package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.tracking.StoredPendingAuthorization
import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class WeTrakrAuthRepository(
    private val configuration: WeTrakrConfiguration,
    private val client: WeTrakrHttpClient,
    private val store: TrackingCredentialStore,
    private val providerId: String = WETRAKR_PROVIDER_ID,
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
        if (!configuration.hasRegistration) throw WeTrakrAuthException(REGISTRATION_MISSING)
        val outcome = client.execute(
            WeTrakrRequest(
                method = WeTrakrMethod.POST,
                path = WeTrakrHttpClient.DEVICE_CODE_PATH,
                json = JSONObject().put("client_id", configuration.clientId).toString(),
                authenticated = false,

                retryable = false,
            ),
        )
        val response = when (outcome) {
            is WeTrakrOutcome.Success -> outcome.response
            is WeTrakrOutcome.Unauthorized -> throw WeTrakrAuthException(registrationMessage(outcome.reason))
            is WeTrakrOutcome.Rejected -> throw WeTrakrAuthException(registrationMessage(outcome.reason))
            is WeTrakrOutcome.Retry -> throw WeTrakrAuthException(
                if (outcome.reason == WeTrakrHttpClient.DEVICE_CODE_BUDGET_EXHAUSTED) {
                    "WeTrakr allows a limited number of authorization codes; please wait a few minutes and try again"
                } else {
                    "WeTrakr device authorization is temporarily unavailable"
                },
            )
        }
        val payload = WeTrakrDeviceAuthorizationResponse.parse(response.body)
        val pending = payload.toStoredPending(now())
            ?: throw WeTrakrAuthException("WeTrakr returned an unusable device authorization response")
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
            WeTrakrRequest(
                method = WeTrakrMethod.POST,
                path = WeTrakrHttpClient.DEVICE_TOKEN_PATH,
                json = JSONObject()
                    .put("code", deviceCode)
                    .put("client_id", configuration.clientId)
                    .toString(),
                authenticated = false,
                devicePoll = true,
                retryable = false,
            ),
        )
        when (outcome) {
            is WeTrakrOutcome.Success -> completeAuthorization(outcome.response)

            is WeTrakrOutcome.Retry -> {
                if (outcome.reason == WeTrakrHttpClient.DEVICE_SLOW_DOWN) {
                    TrackingAuthorizationPoll.SlowDown(pending.intervalSeconds + SLOW_DOWN_STEP_SECONDS)
                } else {
                    TrackingAuthorizationPoll.Pending
                }
            }
            is WeTrakrOutcome.Unauthorized -> {
                store.clearPendingAuthorization(providerId)
                TrackingAuthorizationPoll.Failed(registrationMessage(outcome.reason))
            }
            is WeTrakrOutcome.Rejected -> when (outcome.reason) {
                WeTrakrHttpClient.DEVICE_PENDING -> TrackingAuthorizationPoll.Pending

                WeTrakrHttpClient.DEVICE_EXPIRED,
                WeTrakrHttpClient.DEVICE_INVALID_CODE,
                WeTrakrHttpClient.DEVICE_ALREADY_USED,
                -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Expired
                }
                WeTrakrHttpClient.DEVICE_DENIED -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("The WeTrakr request was denied on the authorizing device")
                }
                WeTrakrHttpClient.DEVICE_LOCKED_CLIENT -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed(LOCKED_CLIENT_MESSAGE)
                }
                WeTrakrHttpClient.DEVICE_INVALID_REQUEST -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed(
                        "WeTrakr rejected the authorization request as malformed",
                    )
                }
                else -> {
                    store.clearPendingAuthorization(providerId)
                    TrackingAuthorizationPoll.Failed("WeTrakr rejected the authorization (${outcome.reason})")
                }
            }
        }
    }

    fun cancelAuthorization() = store.clearPendingAuthorization(providerId)

    suspend fun disconnect() {
        val credentials = store.credentials(providerId)
        val tokens = store.tokens(providerId)
        val refreshToken = tokens?.refreshToken

        if (credentials != null && tokens != null && refreshToken != null && configuration.hasRegistration) {
            runCatching {
                client.execute(
                    WeTrakrRequest(
                        method = WeTrakrMethod.POST,
                        path = WeTrakrHttpClient.LOGOUT_PATH,
                        json = JSONObject().put("refresh_token", refreshToken).toString(),
                        authenticated = true,
                        retryable = false,
                    ),
                    accessToken = tokens.accessToken,
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
            WeTrakrRequest(
                method = WeTrakrMethod.POST,
                path = WeTrakrHttpClient.TOKEN_REFRESH_PATH,
                json = JSONObject().put("refresh_token", refreshToken).toString(),
                authenticated = false,
                retryable = true,
            ),
        )
        when (outcome) {
            is WeTrakrOutcome.Success -> {
                val tokenResponse = WeTrakrTokenResponse.parse(outcome.response.body)
                val access = tokenResponse.accessToken
                if (access.isNullOrBlank()) return@withLock null
                val issuedAt = now()
                store.updateTokens(
                    providerId = providerId,
                    accessToken = access,

                    refreshToken = tokenResponse.rotatedRefreshToken() ?: refreshToken,
                    accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) }
                        ?: credentials.accessExpiresAt,
                    refreshExpiresAt = issuedAt.plusSeconds(REFRESH_TOKEN_LIFE_SECONDS),
                )
                access
            }

            is WeTrakrOutcome.Retry -> null

            is WeTrakrOutcome.Rejected,
            is WeTrakrOutcome.Unauthorized,
            -> {
                store.clear(providerId)
                null
            }
        }
    }

    suspend fun resolveAccountIdentity(): WeTrakrIdentityOutcome {
        val credentials = store.credentials(providerId)
            ?: return WeTrakrIdentityOutcome.NotConnected
        val token = accessToken() ?: return WeTrakrIdentityOutcome.Unavailable(IDENTITY_UNAVAILABLE)
        return when (
            val outcome = client.execute(
                WeTrakrRequest(
                    method = WeTrakrMethod.GET,
                    path = WeTrakrHttpClient.ACCOUNT_SETTINGS_PATH,
                    authenticated = true,
                ),
                accessToken = token,
            )
        ) {
            is WeTrakrOutcome.Success -> {
                val identity = WeTrakrAccountIdentity.parse(outcome.response.body)
                if (identity == null) {
                    WeTrakrIdentityOutcome.Unavailable(MALFORMED_RESPONSE)
                } else {

                    recordAccountIdentity(identity.accountId)
                    WeTrakrIdentityOutcome.Ok(identity)
                }
            }
            is WeTrakrOutcome.Unauthorized -> WeTrakrIdentityOutcome.Unauthorized(outcome.reason)
            is WeTrakrOutcome.Rejected -> WeTrakrIdentityOutcome.Unavailable(outcome.reason)
            is WeTrakrOutcome.Retry -> WeTrakrIdentityOutcome.Unavailable(outcome.reason)
        }
    }

    private fun isRefreshDue(accessExpiresAt: Instant?): Boolean {
        val expiry = accessExpiresAt ?: return false
        return !now().plusSeconds(ACCESS_REFRESH_WINDOW_SECONDS).isBefore(expiry)
    }

    private suspend fun completeAuthorization(response: WeTrakrHttpResponse): TrackingAuthorizationPoll {
        val tokenResponse = WeTrakrTokenResponse.parse(response.body)
        val access = tokenResponse.accessToken
        if (access.isNullOrBlank()) {
            store.clearPendingAuthorization(providerId)
            return TrackingAuthorizationPoll.Failed("WeTrakr approved the request without an access token")
        }
        val issuedAt = now()

        val identity = try {
            readIdentity(access)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {

            null
        }
        store.save(
            providerId = providerId,
            accessToken = access,
            refreshToken = tokenResponse.rotatedRefreshToken(),
            accountLabel = identity?.label,
            accessExpiresAt = tokenResponse.expiresInSeconds?.let { issuedAt.plusSeconds(it) }
                ?: issuedAt.plusSeconds(DEFAULT_ACCESS_LIFE_SECONDS),

            refreshExpiresAt = issuedAt.plusSeconds(REFRESH_TOKEN_LIFE_SECONDS),
            scope = null,
        )

        identity?.let { recordAccountIdentity(it.accountId) }
        store.clearPendingAuthorization(providerId)
        return TrackingAuthorizationPoll.Authorized
    }

    private suspend fun readIdentity(accessToken: String): WeTrakrAccountIdentity? {
        val outcome = client.execute(
            WeTrakrRequest(
                method = WeTrakrMethod.GET,
                path = WeTrakrHttpClient.ACCOUNT_SETTINGS_PATH,
                authenticated = true,
            ),
            accessToken = accessToken,
        )
        val success = outcome as? WeTrakrOutcome.Success ?: return null
        return WeTrakrAccountIdentity.parse(success.response.body)
    }

    private fun StoredPendingAuthorization.toAuthorization(): TrackingDeviceAuthorization = TrackingDeviceAuthorization(
        userCode = userCode,
        verificationUri = verificationUri,
        verificationUriComplete = verificationUriComplete,
        expiresAt = expiresAt,
        intervalSeconds = intervalSeconds,
    )

    private fun registrationMessage(reason: String): String = when {
        reason.contains(WeTrakrHttpClient.DEVICE_LOCKED_CLIENT) -> LOCKED_CLIENT_MESSAGE
        reason.contains(WeTrakrHttpClient.DEVICE_TOO_MANY) ||
            reason.contains("rate_limit") ->
            "WeTrakr allows a limited number of authorization codes; please wait a few minutes and try again"
        reason.contains("invalid_client") || reason.contains("http_401") ->
            "WeTrakr rejected this app registration (unknown or invalid client id)"
        else -> "WeTrakr rejected the authorization request ($reason)"
    }

    companion object {

        const val WETRAKR_PROVIDER_ID: String = "wetrakr"

        const val DEFAULT_ACCESS_LIFE_SECONDS: Long = 7L * 24L * 60L * 60L
        const val REFRESH_TOKEN_LIFE_SECONDS: Long = 180L * 24L * 60L * 60L

        const val SLOW_DOWN_STEP_SECONDS: Int = 5

        const val ACCESS_REFRESH_WINDOW_SECONDS: Long = 24L * 60L * 60L

        const val REGISTRATION_MISSING: String =
            "no WeTrakr client registration is configured in this build"

        const val LOCKED_CLIENT_MESSAGE: String =
            "This WeTrakr application is suspended; contact support@wetrakr.com"

        internal const val NO_CHALLENGE = "no WeTrakr authorization is in flight"
        internal const val MALFORMED_RESPONSE = "wetrakr answered an account document this client cannot use"
        internal const val IDENTITY_UNAVAILABLE = "wetrakr account identity is temporarily unavailable"
    }
}

class WeTrakrAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class WeTrakrAccountIdentity(
    val accountId: String,
    val label: String?,
) {
    companion object {

        fun parse(body: String): WeTrakrAccountIdentity? = runCatching {
            val json = JSONObject(body)
            val id = when {
                json.has("id") && !json.isNull("id") -> json.opt("id")?.toString()?.trim()
                else -> null
            }
            val accountId = id?.takeIf { it.isNotEmpty() && it != "0" } ?: return null
            val info = json.optJSONObject("info")
            val label = info?.optStringOrNull("username")
                ?: info?.optStringOrNull("display_name")
            WeTrakrAccountIdentity(accountId = accountId, label = label)
        }.getOrNull()
    }
}

sealed interface WeTrakrIdentityOutcome {

    data class Ok(val identity: WeTrakrAccountIdentity) : WeTrakrIdentityOutcome

    data object NotConnected : WeTrakrIdentityOutcome

    data class Unauthorized(val reason: String) : WeTrakrIdentityOutcome

    data class Unavailable(val reason: String) : WeTrakrIdentityOutcome
}

internal data class WeTrakrDeviceAuthorizationResponse(
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

            verificationUriComplete = buildString {
                append(uri)
                append(if (uri.contains('?')) '&' else '?')
                append("code=").append(java.net.URLEncoder.encode(code, "UTF-8"))
            },
            expiresAt = now.plusSeconds(expires),
            intervalSeconds = (intervalSeconds ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
        )
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS: Int = 5

        fun parse(body: String): WeTrakrDeviceAuthorizationResponse = runCatching {
            val json = JSONObject(body)
            WeTrakrDeviceAuthorizationResponse(

                deviceCode = json.optStringOrNull("device_code") ?: json.optStringOrNull("code"),
                userCode = json.optStringOrNull("user_code"),

                verificationUri = json.optStringOrNull("verification_url")
                    ?: json.optStringOrNull("verification_uri"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
                intervalSeconds = json.optIntOrNull("interval"),
            )
        }.getOrDefault(WeTrakrDeviceAuthorizationResponse(null, null, null, null, null))
    }
}

internal data class WeTrakrTokenResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val newRefreshToken: String?,
    val expiresInSeconds: Long?,
) {

    fun rotatedRefreshToken(): String? =
        refreshToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: newRefreshToken?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun parse(body: String): WeTrakrTokenResponse = runCatching {
            val json = JSONObject(body)
            WeTrakrTokenResponse(
                accessToken = json.optStringOrNull("access_token"),
                refreshToken = json.optStringOrNull("refresh_token"),
                newRefreshToken = json.optStringOrNull("new_refresh_token"),
                expiresInSeconds = json.optLongOrNull("expires_in"),
            )
        }.getOrDefault(WeTrakrTokenResponse(null, null, null, null))
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, 0L).takeIf { it != 0L }

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key, 0).takeIf { it != 0 }
