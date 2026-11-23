package dev.infusion.tracking.wetrakr

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WeTrakrConfiguration(
    val clientId: String,
    val appName: String,
    val appVersion: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {

    val hasRegistration: Boolean get() = clientId.isNotBlank()

    companion object {

        const val DEFAULT_API_BASE_URL: String = "https://api.wetrakr.com"
    }
}

enum class WeTrakrMethod { GET, POST, DELETE }

data class WeTrakrRequest(
    val method: WeTrakrMethod,
    val path: String,
    val json: String? = null,

    val query: Map<String, String> = emptyMap(),

    val authenticated: Boolean = false,

    val appKeyOnly: Boolean = false,

    val optionalAuth: Boolean = false,

    val retryable: Boolean = true,

    val limits: NetworkLimits? = null,

    val devicePoll: Boolean = false,
)

data class WeTrakrHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

fun interface WeTrakrTransport {
    suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): WeTrakrHttpResponse
}

class WeTrakrResponseTooLargeException(
    val maxBytes: Long,
    val maxDecompressedBytes: Long,
    cause: Throwable? = null,
) : Exception(
    "wetrakr response exceeds the bounded budget of $maxBytes bytes ($maxDecompressedBytes decompressed)",
    cause,
)

sealed interface WeTrakrOutcome {

    data class Success(val response: WeTrakrHttpResponse) : WeTrakrOutcome

    data class Retry(val reason: String, val retryAfterMs: Long?) : WeTrakrOutcome

    data class Unauthorized(val reason: String) : WeTrakrOutcome

    data class Rejected(val reason: String) : WeTrakrOutcome
}

class WeTrakrHttpClient(
    private val configuration: WeTrakrConfiguration,
    private val transport: WeTrakrTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },

    private val trace: ((String) -> Unit)? = null,
) {

    private val scheduleMutex = Mutex()
    private var nextRequestAtMs = 0L

    val hasRegistration: Boolean get() = configuration.hasRegistration

    private val deviceCodeRequestsMs = ArrayDeque<Long>()

    suspend fun execute(request: WeTrakrRequest, accessToken: String? = null): WeTrakrOutcome {
        if (!configuration.hasRegistration) {
            return traced(request, WeTrakrOutcome.Rejected(MISSING_CLIENT_ID))
        }
        if (request.authenticated && accessToken.isNullOrBlank()) {
            return traced(request, WeTrakrOutcome.Unauthorized(NO_TOKEN))
        }
        if (request.path == DEVICE_CODE_PATH && !reserveDeviceCodeSlot()) {
            return traced(request, WeTrakrOutcome.Retry(DEVICE_CODE_BUDGET_EXHAUSTED, null))
        }
        val url = buildUrl(request)
        val headers = buildHeaders(request, accessToken)
        val body = request.json?.toByteArray(StandardCharsets.UTF_8)
        val attempts = if (request.retryable) MAX_ATTEMPTS else 1
        var last: WeTrakrOutcome = WeTrakrOutcome.Retry(TRANSPORT_FAILURE, null)
        var attempt = 0
        while (attempt < attempts) {
            val response = try {
                paced { transport.execute(url, request.method.name, headers, body, request.limits ?: LIMITS) }
            } catch (oversize: WeTrakrResponseTooLargeException) {

                return traced(request, WeTrakrOutcome.Rejected(RESPONSE_TOO_LARGE_REASON))
            } catch (error: Throwable) {
                if (attempt == attempts - 1) {
                    return traced(request, WeTrakrOutcome.Retry(TRANSPORT_FAILURE, null))
                }
                sleep(retryDelayMs(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            val outcome = if (request.devicePoll) {
                classifyDevicePoll(response)
            } else {
                classify(response)
            }
            when (outcome) {
                is WeTrakrOutcome.Success -> return traced(request, outcome)
                is WeTrakrOutcome.Unauthorized -> return traced(request, outcome)
                is WeTrakrOutcome.Rejected -> return traced(request, outcome)
                is WeTrakrOutcome.Retry -> {
                    last = outcome
                    if (attempt == attempts - 1) return traced(request, outcome)
                    sleep(retryDelayMs(attempt, outcome.retryAfterMs))
                }
            }
            attempt++
        }
        return traced(request, last)
    }

    private fun traced(request: WeTrakrRequest, outcome: WeTrakrOutcome): WeTrakrOutcome {
        trace?.invoke(traceLine(request, outcome))
        return outcome
    }

    private suspend fun <T> paced(block: suspend () -> T): T = scheduleMutex.withLock {
        val now = nowMs()
        if (nextRequestAtMs > now) sleep(nextRequestAtMs - now)
        try {
            return block()
        } finally {
            nextRequestAtMs = max(nextRequestAtMs, nowMs() + REQUEST_INTERVAL_MS)
        }
    }

    private fun reserveDeviceCodeSlot(): Boolean = synchronized(deviceCodeRequestsMs) {
        val now = nowMs()
        while (deviceCodeRequestsMs.isNotEmpty() && now - deviceCodeRequestsMs.first() >= DEVICE_CODE_BUDGET_WINDOW_MS) {
            deviceCodeRequestsMs.removeFirst()
        }
        if (deviceCodeRequestsMs.size >= DEVICE_CODE_BUDGET_REQUESTS) return false
        deviceCodeRequestsMs.addLast(now)
        true
    }

    fun buildUrl(request: WeTrakrRequest): CanonicalUrl =
        UrlParser.parse(
            buildString {
                append(configuration.apiBaseUrl)
                append(request.path)
                if (request.query.isNotEmpty()) {
                    append('?')
                    append(
                        request.query.entries.joinToString("&") { (key, value) ->
                            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
                        },
                    )
                }
            },
        )

    private fun encodeQueryComponent(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            ) {
                out.append(char)
            } else {
                out.append('%')
                out.append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
        return out.toString()
    }

    fun buildHeaders(request: WeTrakrRequest, accessToken: String?): Map<String, String> = buildMap {
        put(HEADER_API_VERSION, API_VERSION)
        put("User-Agent", "${configuration.appName}/${configuration.appVersion}")
        put("Accept", "application/json")

        if (request.authenticated || request.appKeyOnly || request.optionalAuth) {
            put(HEADER_API_KEY, configuration.clientId)
        }
        if (request.appKeyOnly) {

            put(HEADER_API_KEY, configuration.clientId)
        }

        if ((request.authenticated || request.optionalAuth) && !accessToken.isNullOrBlank()) {
            put("Authorization", "Bearer $accessToken")
        }
        if (request.json != null) put("Content-Type", "application/json")
    }

    companion object {

        const val HEADER_API_VERSION = "wetrakr-api-version"
        const val API_VERSION = "1"

        const val HEADER_API_KEY = "wetrakr-api-key"

        const val DEVICE_CODE_PATH = "/oauth/device/code"
        const val DEVICE_TOKEN_PATH = "/oauth/device/token"
        const val TOKEN_REFRESH_PATH = "/oauth/token/refresh"
        const val LOGOUT_PATH = "/oauth/logout"

        const val ACCOUNT_SETTINGS_PATH = "/account/settings"

        const val REQUEST_INTERVAL_MS: Long = 400L

        const val DEVICE_CODE_BUDGET_REQUESTS: Int = 5
        const val DEVICE_CODE_BUDGET_WINDOW_MS: Long = 15L * 60L * 1_000L

        const val MAX_ATTEMPTS: Int = 4

        internal const val MISSING_CLIENT_ID = "no WeTrakr client registration configured"
        internal const val NO_TOKEN = "no stored WeTrakr authorization"
        internal const val TRANSPORT_FAILURE = "wetrakr transport failure"
        internal const val DEVICE_CODE_BUDGET_EXHAUSTED = "device_code_budget_exhausted"
        internal const val RESPONSE_TOO_LARGE_REASON = "response_too_large"

        internal const val DEVICE_PENDING = "authorization_pending"
        internal const val DEVICE_INVALID_REQUEST = "invalid_request"
        internal const val DEVICE_INVALID_CLIENT = "invalid_client"
        internal const val DEVICE_INVALID_CODE = "invalid_code"
        internal const val DEVICE_ALREADY_USED = "already_used"
        internal const val DEVICE_EXPIRED = "expired_token"
        internal const val DEVICE_DENIED = "access_denied"
        internal const val DEVICE_SLOW_DOWN = "slow_down"
        internal const val DEVICE_LOCKED_CLIENT = "locked_client"
        internal const val DEVICE_TOO_MANY = "too_many_requests"

        internal fun retryAfterMs(response: WeTrakrHttpResponse): Long? =
            response.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.substringBefore(',')?.trim()?.toLongOrNull()
                ?.takeIf { it >= 0 }?.times(1_000L)

        internal fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long {
            val exponential = 1_000L shl attempt.coerceIn(0, 5)
            return max(exponential, retryAfterMs ?: 0L).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal const val MAX_RETRY_DELAY_MS: Long = 30_000L

        internal fun errorCode(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("error", "").trim().ifEmpty { null }
        }.getOrNull()

        internal fun statusReason(response: WeTrakrHttpResponse): String {
            val error = errorCode(response.body)
            return if (error == null) "http_${response.status}" else "http_${response.status}:$error"
        }

        internal fun classify(response: WeTrakrHttpResponse): WeTrakrOutcome = when {
            response.status in 200..299 -> WeTrakrOutcome.Success(response)
            response.status == 401 -> WeTrakrOutcome.Unauthorized(errorCode(response.body) ?: "unauthorized")
            response.status == 429 -> WeTrakrOutcome.Retry("rate_limit", retryAfterMs(response))
            response.status == 500 ||
                response.status == 502 ||
                response.status == 503 ||
                response.status == 504 -> WeTrakrOutcome.Retry("provider_unavailable", retryAfterMs(response))
            else -> WeTrakrOutcome.Rejected(statusReason(response))
        }

        internal fun classifyDevicePoll(response: WeTrakrHttpResponse): WeTrakrOutcome {
            if (response.status in 200..299) return WeTrakrOutcome.Success(response)
            when (errorCode(response.body)) {
                DEVICE_PENDING -> return WeTrakrOutcome.Rejected(DEVICE_PENDING)
                DEVICE_SLOW_DOWN -> return WeTrakrOutcome.Retry(DEVICE_SLOW_DOWN, retryAfterMs(response))
                DEVICE_INVALID_REQUEST -> return WeTrakrOutcome.Rejected(DEVICE_INVALID_REQUEST)
                DEVICE_INVALID_CODE -> return WeTrakrOutcome.Rejected(DEVICE_INVALID_CODE)
                DEVICE_ALREADY_USED -> return WeTrakrOutcome.Rejected(DEVICE_ALREADY_USED)
                DEVICE_EXPIRED -> return WeTrakrOutcome.Rejected(DEVICE_EXPIRED)
                DEVICE_DENIED -> return WeTrakrOutcome.Rejected(DEVICE_DENIED)
                DEVICE_INVALID_CLIENT -> return WeTrakrOutcome.Unauthorized(DEVICE_INVALID_CLIENT)
                DEVICE_LOCKED_CLIENT -> return WeTrakrOutcome.Rejected(DEVICE_LOCKED_CLIENT)
            }
            return when (response.status) {
                401 -> WeTrakrOutcome.Unauthorized(errorCode(response.body) ?: "unauthorized")
                429 -> WeTrakrOutcome.Retry(DEVICE_TOO_MANY, retryAfterMs(response))
                500, 502, 503, 504 -> WeTrakrOutcome.Retry("provider_unavailable", retryAfterMs(response))

                else -> WeTrakrOutcome.Retry(statusReason(response), retryAfterMs(response))
            }
        }

        internal fun traceLine(request: WeTrakrRequest, outcome: WeTrakrOutcome): String {
            val fields = mutableListOf("path=${request.path}", "class=${outcome::class.simpleName}")
            when (outcome) {
                is WeTrakrOutcome.Success -> {
                    fields += "status=${outcome.response.status}"

                    fields += "body_bytes=${outcome.response.body.length}"
                }
                is WeTrakrOutcome.Retry -> fields += "reason=${outcome.reason}"
                is WeTrakrOutcome.Rejected -> fields += "reason=${outcome.reason}"
                is WeTrakrOutcome.Unauthorized -> fields += "reason=${outcome.reason}"
            }
            return "wetrakr " + fields.joinToString(" ")
        }

        val LIMITS: NetworkLimits = NetworkLimits.DEFAULT.copy(
            maxResponseBytes = 128L * 1024,
            maxDecompressedBytes = 512L * 1024,
            maxRequestBytes = 16L * 1024,
            totalTimeoutMs = 20_000L,
        )

        val READ_LIMITS: NetworkLimits = NetworkLimits.DEFAULT.copy(
            maxResponseBytes = 4L * 1024 * 1024,
            maxDecompressedBytes = 8L * 1024 * 1024,
            maxRequestBytes = 16L * 1024,
            totalTimeoutMs = 30_000L,
        )

        private const val HEX_DIGITS: String = "0123456789ABCDEF"

        const val LAST_ACTIVITIES_PATH = "/sync/last_activities"

        const val TRACKING_PLAYING_PATH = "/sync/tracking/playing"

        const val TRACKING_WATCHED_PATH = "/sync/tracking/watched"

        const val TRACKING_WATCHED_HISTORY_PATH = "/sync/tracking/watched/history"

        const val TRACKING_PATH = "/sync/tracking"

        const val TRACKING_WRITE_PATH = "/sync/tracking"

        const val TRACKING_REMOVE_PATH = "/sync/tracking/remove"

        const val MEDIA_EXTERNAL_PATH = "/media/external"

        const val LISTS_PATH = "/lists"
        const val LISTS_TRENDING_PATH = "/lists/trending"
        const val LISTS_LAST_PATH = "/lists/last"

        const val ACCOUNT_LISTS_PATH = "/sync/lists"

        const val SCROBBLE_START_PATH = "/scrobble/start"
        const val SCROBBLE_PAUSE_PATH = "/scrobble/pause"
        const val SCROBBLE_STOP_PATH = "/scrobble/stop"

        const val SCROBBLE_PLAYING_PATH = "/scrobble/playing"
    }
}

class HardenedWeTrakrTransport(
    private val client: HardenedHttpClient,
) : WeTrakrTransport {

    override suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): WeTrakrHttpResponse {
        val response = client.execute(
            HardenedRequest(
                url = url,
                method = method,
                headers = headers,
                limits = limits,
                body = body,
                sensitiveHeaders = setOf("Authorization", WeTrakrHttpClient.HEADER_API_KEY),
            ),
        )
        response.use { closed ->
            val text = try {
                closed.body.readBytes().toString(StandardCharsets.UTF_8)
            } catch (limit: NetworkPolicyException) {
                if (limit.reason == DiagnosticReason.LIMIT_EXCEEDED) {
                    throw WeTrakrResponseTooLargeException(
                        maxBytes = limits.maxResponseBytes,
                        maxDecompressedBytes = limits.maxDecompressedBytes,
                        cause = limit,
                    )
                }
                throw limit
            }
            return WeTrakrHttpResponse(closed.statusCode, text, closed.headers)
        }
    }
}
