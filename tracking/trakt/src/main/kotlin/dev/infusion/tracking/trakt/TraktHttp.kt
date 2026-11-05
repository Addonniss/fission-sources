package dev.infusion.tracking.trakt

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

data class TraktConfiguration(
    val clientId: String,
    val clientSecret: String,
    val appName: String,
    val appVersion: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val authBaseUrl: String = DEFAULT_AUTH_BASE_URL,

    val redirectUri: String = DEFAULT_REDIRECT_URI,
) {

    val hasClientId: Boolean get() = clientId.isNotBlank()

    val hasRegistration: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {

        const val DEFAULT_API_BASE_URL: String = "https://api.trakt.tv"

        const val DEFAULT_AUTH_BASE_URL: String = "https://auth.trakt.tv"

        const val DEFAULT_REDIRECT_URI: String = "urn:ietf:wg:oauth:2.0:oob"
    }
}

enum class TraktMethod { GET, POST, DELETE }

enum class TraktReadBudget {

    DEFAULT,

    LOOKUP,

    WATCHED_HISTORY,
}

data class TraktRequest(
    val method: TraktMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),

    val json: String? = null,

    val authenticated: Boolean = true,

    val toAuthHost: Boolean = false,

    val retryable: Boolean = true,

    val devicePoll: Boolean = false,

    val conflictIsSuccess: Boolean = false,

    val readBudget: TraktReadBudget = TraktReadBudget.DEFAULT,
)

data class TraktHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

fun interface TraktTransport {
    suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): TraktHttpResponse
}

class TraktResponseTooLargeException(
    val maxBytes: Long,
    val maxDecompressedBytes: Long,
    cause: Throwable? = null,
) : Exception(
    "trakt response exceeds the bounded budget of $maxBytes bytes ($maxDecompressedBytes decompressed)",
    cause,
)

sealed interface TraktOutcome {

    data class Success(val response: TraktHttpResponse) : TraktOutcome

    data class Retry(val reason: String, val retryAfterMs: Long?) : TraktOutcome

    data class Unauthorized(val reason: String) : TraktOutcome

    data class Rejected(val reason: String) : TraktOutcome
}

class TraktHttpClient(
    private val configuration: TraktConfiguration,
    private val transport: TraktTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },

    private val trace: ((String) -> Unit)? = null,
) {

    private val scheduleMutex = Mutex()
    private var nextGetAtMs = 0L
    private var nextPostAtMs = 0L

    suspend fun execute(request: TraktRequest, accessToken: String? = null): TraktOutcome {
        if (!configuration.hasClientId) {
            return traced(request, TraktOutcome.Rejected(MISSING_CLIENT_ID))
        }
        if (request.authenticated && accessToken.isNullOrBlank()) {
            return traced(request, TraktOutcome.Unauthorized(NO_TOKEN))
        }
        val url = buildUrl(request)
        val headers = buildHeaders(request, accessToken)
        val body = encodeBody(request)
        val limits = TraktReadBudgets.limitsFor(request.readBudget)
        val attempts = if (request.retryable) MAX_ATTEMPTS else 1
        var last: TraktOutcome = TraktOutcome.Retry(TRANSPORT_FAILURE, null)
        var attempt = 0
        while (attempt < attempts) {
            val method = request.method
            val response = try {
                paced(method) { transport.execute(url, method.name, headers, body, limits) }
            } catch (oversize: TraktResponseTooLargeException) {

                return traced(request, TraktOutcome.Rejected(RESPONSE_TOO_LARGE_REASON))
            } catch (error: Throwable) {
                if (attempt == attempts - 1) {
                    return traced(request, TraktOutcome.Retry(TRANSPORT_FAILURE, null))
                }
                sleep(retryDelayMs(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            val outcome = if (request.devicePoll) {
                classifyDevicePoll(response)
            } else {
                classify(response, request.conflictIsSuccess)
            }
            when (outcome) {
                is TraktOutcome.Success -> return traced(request, outcome)
                is TraktOutcome.Unauthorized -> return traced(request, outcome)
                is TraktOutcome.Rejected -> return traced(request, outcome)
                is TraktOutcome.Retry -> {
                    last = outcome
                    if (attempt == attempts - 1) return traced(request, outcome)
                    sleep(retryDelayMs(attempt, outcome.retryAfterMs))
                }
            }
            attempt++
        }
        return traced(request, last)
    }

    private fun traced(request: TraktRequest, outcome: TraktOutcome): TraktOutcome {
        trace?.invoke(traceLine(request, outcome))
        return outcome
    }

    private suspend fun <T> paced(method: TraktMethod, block: suspend () -> T): T {
        scheduleMutex.withLock {
            val now = nowMs()
            val scheduledAt = if (method == TraktMethod.GET) nextGetAtMs else nextPostAtMs
            if (scheduledAt > now) sleep(scheduledAt - now)
            try {
                return block()
            } finally {
                val completedAt = nowMs()
                val interval = if (method == TraktMethod.GET) GET_INTERVAL_MS else POST_INTERVAL_MS
                if (method == TraktMethod.GET) {
                    nextGetAtMs = max(nextGetAtMs, completedAt + interval)
                } else {
                    nextPostAtMs = max(nextPostAtMs, completedAt + interval)
                }
            }
        }
    }

    fun buildUrl(request: TraktRequest): CanonicalUrl {
        val base = if (request.toAuthHost) configuration.authBaseUrl else configuration.apiBaseUrl
        val query = request.query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val suffix = if (query.isEmpty()) "" else "?$query"
        return UrlParser.parse("$base${request.path}$suffix")
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    fun buildHeaders(request: TraktRequest, accessToken: String?): Map<String, String> = buildMap {
        put("User-Agent", "${configuration.appName}/${configuration.appVersion}")
        put("Accept", "application/json")
        put("trakt-api-version", API_VERSION)
        put("trakt-api-key", configuration.clientId)
        if (request.authenticated && !accessToken.isNullOrBlank()) {
            put("Authorization", "Bearer $accessToken")
        }
        if (request.json != null) put("Content-Type", "application/json")
    }

    private fun encodeBody(request: TraktRequest): ByteArray? =
        request.json?.toByteArray(StandardCharsets.UTF_8)

    companion object {

        const val GET_INTERVAL_MS: Long = 600L

        const val POST_INTERVAL_MS: Long = 1_000L

        const val MAX_ATTEMPTS: Int = 4

        const val API_VERSION: String = "2"

        internal const val MISSING_CLIENT_ID = "no Trakt client registration configured"
        internal const val NO_TOKEN = "no stored Trakt authorization"
        internal const val TRANSPORT_FAILURE = "trakt transport failure"

        internal const val RESPONSE_TOO_LARGE_REASON = "response_too_large"

        internal const val NOT_FOUND_REASON = "not_found"

        internal const val INVALID_GRANT = "invalid_grant"

        internal fun errorCode(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("error", "").trim().ifEmpty { null }
        }.getOrNull()

        internal fun traceLine(request: TraktRequest, outcome: TraktOutcome): String {
            val fields = mutableListOf("path=${request.path}", "class=${outcome::class.simpleName}")
            scrobbleProgress(request)?.let { fields += "sent_progress=$it" }
            when (outcome) {
                is TraktOutcome.Success -> {
                    fields += "status=${outcome.response.status}"

                    fields += "body_bytes=${outcome.response.body.length}"
                    answeredAction(outcome.response.body)?.let { fields += "action=$it" }
                }
                is TraktOutcome.Retry -> fields += "reason=${outcome.reason}"
                is TraktOutcome.Rejected -> fields += "reason=${outcome.reason}"
                is TraktOutcome.Unauthorized -> fields += "reason=${outcome.reason}"
            }
            return "trakt " + fields.joinToString(" ")
        }

        private fun scrobbleProgress(request: TraktRequest): String? = runCatching {
            val json = request.json ?: return null
            org.json.JSONObject(json).opt("progress")?.toString()
        }.getOrNull()

        private fun answeredAction(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("action", "").trim().ifEmpty { null }
        }.getOrNull()

        internal fun classify(response: TraktHttpResponse, conflictIsSuccess: Boolean): TraktOutcome = when {
            response.status in 200..299 -> TraktOutcome.Success(response)
            response.status == 409 && conflictIsSuccess -> TraktOutcome.Success(response)
            response.status == 401 -> TraktOutcome.Unauthorized(errorCode(response.body) ?: "unauthorized")
            response.status == 429 -> TraktOutcome.Retry("rate_limit", retryAfterMs(response))
            response.status == 500 ||
                response.status == 502 ||
                response.status == 503 ||
                response.status == 504 ||
                response.status == 520 ||
                response.status == 521 ||
                response.status == 522 -> TraktOutcome.Retry("provider_unavailable", retryAfterMs(response))
            response.status == 404 -> TraktOutcome.Rejected(NOT_FOUND_REASON)
            else -> TraktOutcome.Rejected(statusReason(response))
        }

        internal fun classifyDevicePoll(response: TraktHttpResponse): TraktOutcome = when {
            response.status in 200..299 -> TraktOutcome.Success(response)
            response.status == 400 -> TraktOutcome.Rejected(DEVICE_PENDING)
            response.status == 404 -> TraktOutcome.Rejected(DEVICE_INVALID_CODE)
            response.status == 409 -> TraktOutcome.Rejected(DEVICE_ALREADY_USED)
            response.status == 410 -> TraktOutcome.Rejected(DEVICE_EXPIRED)
            response.status == 418 -> TraktOutcome.Rejected(DEVICE_DENIED)

            response.status == 429 -> TraktOutcome.Retry(DEVICE_SLOW_DOWN, retryAfterMs(response))
            response.status == 401 -> TraktOutcome.Unauthorized("unauthorized")

            response.status == 500 ||
                response.status == 502 ||
                response.status == 503 ||
                response.status == 504 ||
                response.status == 520 ||
                response.status == 521 ||
                response.status == 522 -> TraktOutcome.Retry("provider_unavailable", retryAfterMs(response))
            else -> TraktOutcome.Rejected(statusReason(response))
        }

        internal fun statusReason(response: TraktHttpResponse): String {
            val error = errorCode(response.body)
            return if (error == null) "http_${response.status}" else "http_${response.status}:$error"
        }

        internal const val DEVICE_PENDING = "device_pending"
        internal const val DEVICE_INVALID_CODE = "device_invalid_code"
        internal const val DEVICE_ALREADY_USED = "device_already_used"
        internal const val DEVICE_EXPIRED = "device_expired"
        internal const val DEVICE_DENIED = "device_denied"
        internal const val DEVICE_SLOW_DOWN = "device_slow_down"

        internal fun retryAfterMs(response: TraktHttpResponse): Long? =
            response.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.substringBefore(',')?.trim()?.toLongOrNull()
                ?.takeIf { it >= 0 }?.times(1_000L)

        internal fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long {
            val exponential = 1_000L shl attempt.coerceIn(0, 5)
            return max(exponential, retryAfterMs ?: 0L).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal const val MAX_RETRY_DELAY_MS: Long = 30_000L
    }
}

object TraktReadBudgets {

    val DEFAULT: NetworkLimits = NetworkLimits.DEFAULT.copy(
        maxResponseBytes = 128L * 1024,
        maxDecompressedBytes = 512L * 1024,
        maxRequestBytes = 64L * 1024,
        totalTimeoutMs = 15_000L,
    )

    val LOOKUP: NetworkLimits = NetworkLimits.DEFAULT.copy(
        maxResponseBytes = 1L * 1024 * 1024,
        maxDecompressedBytes = 2L * 1024 * 1024,
        maxRequestBytes = 64L * 1024,
        totalTimeoutMs = 30_000L,
    )

    val WATCHED_HISTORY: NetworkLimits = NetworkLimits.DEFAULT.copy(
        maxResponseBytes = 2L * 1024 * 1024,
        maxDecompressedBytes = 4L * 1024 * 1024,
        maxRequestBytes = 64L * 1024,
        totalTimeoutMs = 30_000L,
    )

    fun limitsFor(budget: TraktReadBudget): NetworkLimits = when (budget) {
        TraktReadBudget.DEFAULT -> DEFAULT
        TraktReadBudget.LOOKUP -> LOOKUP
        TraktReadBudget.WATCHED_HISTORY -> WATCHED_HISTORY
    }
}

class HardenedTraktTransport(
    private val client: HardenedHttpClient,
) : TraktTransport {

    override suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): TraktHttpResponse {
        val response = client.execute(
            HardenedRequest(
                url = url,
                method = method,
                headers = headers,
                limits = limits,
                body = body,
                sensitiveHeaders = setOf("Authorization"),
            ),
        )
        response.use { closed ->
            val text = try {
                closed.body.readBytes().toString(StandardCharsets.UTF_8)
            } catch (limit: NetworkPolicyException) {
                if (limit.reason == DiagnosticReason.LIMIT_EXCEEDED) {
                    throw TraktResponseTooLargeException(
                        maxBytes = limits.maxResponseBytes,
                        maxDecompressedBytes = limits.maxDecompressedBytes,
                        cause = limit,
                    )
                }
                throw limit
            }
            return TraktHttpResponse(closed.statusCode, text, closed.headers)
        }
    }
}
