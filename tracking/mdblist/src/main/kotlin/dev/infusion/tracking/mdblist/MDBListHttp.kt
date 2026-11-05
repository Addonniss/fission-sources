package dev.infusion.tracking.mdblist

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

data class MDBListConfiguration(
    val clientId: String,
    val appName: String,
    val appVersion: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {

    val hasRegistration: Boolean get() = clientId.isNotBlank()

    companion object {

        const val DEFAULT_API_BASE_URL: String = "https://api.mdblist.com"
    }
}

enum class MDBListMethod { GET, POST }

enum class MDBListReadBudget {

    DEFAULT,

    LOOKUP,

    WATCHED_HISTORY,
}

data class MDBListRequest(
    val method: MDBListMethod,
    val path: String,

    val query: Map<String, String> = emptyMap(),
    val json: String? = null,
    val form: Map<String, String>? = null,

    val authenticated: Boolean = true,

    val retryable: Boolean = true,

    val devicePoll: Boolean = false,

    val readBudget: MDBListReadBudget = MDBListReadBudget.DEFAULT,
)

data class MDBListHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

fun interface MDBListTransport {
    suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): MDBListHttpResponse
}

class MDBListResponseTooLargeException(
    val maxBytes: Long,
    val maxDecompressedBytes: Long,
    cause: Throwable? = null,
) : Exception(
    "mdblist response exceeds the bounded budget of $maxBytes bytes ($maxDecompressedBytes decompressed)",
    cause,
)

sealed interface MDBListOutcome {

    data class Success(val response: MDBListHttpResponse) : MDBListOutcome

    data class Retry(val reason: String, val retryAfterMs: Long?) : MDBListOutcome

    data class Unauthorized(val reason: String) : MDBListOutcome

    data class Rejected(val reason: String) : MDBListOutcome
}

class MDBListHttpClient(
    private val configuration: MDBListConfiguration,
    private val transport: MDBListTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },

    private val trace: ((String) -> Unit)? = null,
) {

    private val scheduleMutex = Mutex()
    private var nextGetAtMs = 0L
    private var nextPostAtMs = 0L

    suspend fun execute(request: MDBListRequest, accessToken: String? = null): MDBListOutcome {
        if (!configuration.hasRegistration) {
            return traced(request, MDBListOutcome.Rejected(MISSING_CLIENT_ID))
        }
        if (request.authenticated && accessToken.isNullOrBlank()) {
            return traced(request, MDBListOutcome.Unauthorized(NO_TOKEN))
        }
        val url = buildUrl(request)
        val headers = buildHeaders(request, accessToken)
        val body = encodeBody(request)
        val limits = MDBListReadBudgets.limitsFor(request.readBudget)
        val attempts = if (request.retryable) MAX_ATTEMPTS else 1
        var last: MDBListOutcome = MDBListOutcome.Retry(TRANSPORT_FAILURE, null)
        var attempt = 0
        while (attempt < attempts) {
            val method = request.method
            val response = try {
                paced(method) { transport.execute(url, method.name, headers, body, limits) }
            } catch (oversize: MDBListResponseTooLargeException) {

                return traced(request, MDBListOutcome.Rejected(RESPONSE_TOO_LARGE_REASON))
            } catch (error: Throwable) {
                if (attempt == attempts - 1) {
                    return traced(request, MDBListOutcome.Retry(TRANSPORT_FAILURE, null))
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
                is MDBListOutcome.Success -> return traced(request, outcome)
                is MDBListOutcome.Unauthorized -> return traced(request, outcome)
                is MDBListOutcome.Rejected -> return traced(request, outcome)
                is MDBListOutcome.Retry -> {
                    last = outcome
                    if (attempt == attempts - 1) return traced(request, outcome)
                    sleep(retryDelayMs(attempt, outcome.retryAfterMs))
                }
            }
            attempt++
        }
        return traced(request, last)
    }

    private fun traced(request: MDBListRequest, outcome: MDBListOutcome): MDBListOutcome {
        trace?.invoke(traceLine(request, outcome))
        return outcome
    }

    private suspend fun <T> paced(method: MDBListMethod, block: suspend () -> T): T {
        scheduleMutex.withLock {
            val now = nowMs()
            val scheduledAt = if (method == MDBListMethod.GET) nextGetAtMs else nextPostAtMs
            if (scheduledAt > now) sleep(scheduledAt - now)
            try {
                return block()
            } finally {
                val completedAt = nowMs()
                val interval = if (method == MDBListMethod.GET) GET_INTERVAL_MS else POST_INTERVAL_MS
                if (method == MDBListMethod.GET) {
                    nextGetAtMs = max(nextGetAtMs, completedAt + interval)
                } else {
                    nextPostAtMs = max(nextPostAtMs, completedAt + interval)
                }
            }
        }
    }

    fun buildUrl(request: MDBListRequest): CanonicalUrl {
        val query = request.query.entries.joinToString("&") { (key, value) ->
            "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        val suffix = if (query.isEmpty()) "" else "?$query"
        return UrlParser.parse("${configuration.apiBaseUrl}${request.path}$suffix")
    }

    fun buildHeaders(request: MDBListRequest, accessToken: String?): Map<String, String> = buildMap {
        put("User-Agent", "${configuration.appName}/${configuration.appVersion}")
        put("Accept", "application/json")
        if (request.authenticated && !accessToken.isNullOrBlank()) {
            put("Authorization", "Bearer $accessToken")
        }
        when {
            request.form != null -> put("Content-Type", FORM_CONTENT_TYPE)
            request.json != null -> put("Content-Type", JSON_CONTENT_TYPE)
        }
    }

    private fun encodeBody(request: MDBListRequest): ByteArray? = when {
        request.form != null -> request.form.entries
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
            .toByteArray(StandardCharsets.UTF_8)
        request.json != null -> request.json.toByteArray(StandardCharsets.UTF_8)
        else -> null
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {

        const val GET_INTERVAL_MS: Long = 300L

        const val POST_INTERVAL_MS: Long = 1_000L

        const val MAX_ATTEMPTS: Int = 4

        internal const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        internal const val JSON_CONTENT_TYPE = "application/json"

        internal const val MISSING_CLIENT_ID = "no MDBList client registration configured"
        internal const val NO_TOKEN = "no stored MDBList authorization"
        internal const val TRANSPORT_FAILURE = "mdblist transport failure"

        internal const val RESPONSE_TOO_LARGE_REASON = "response_too_large"

        internal const val INVALID_GRANT = "invalid_grant"

        internal const val INVALID_CLIENT = "invalid_client"

        internal fun errorCode(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("error", "").trim().ifEmpty { null }
        }.getOrNull()

        internal fun traceLine(request: MDBListRequest, outcome: MDBListOutcome): String {
            val fields = mutableListOf("path=${request.path}", "class=${outcome::class.simpleName}")
            scrobbleProgress(request)?.let { fields += "sent_progress=$it" }
            when (outcome) {
                is MDBListOutcome.Success -> {
                    fields += "status=${outcome.response.status}"

                    fields += "body_bytes=${outcome.response.body.length}"
                    answeredAction(outcome.response.body)?.let { fields += "action=$it" }
                }
                is MDBListOutcome.Retry -> fields += "reason=${outcome.reason}"
                is MDBListOutcome.Rejected -> fields += "reason=${outcome.reason}"
                is MDBListOutcome.Unauthorized -> fields += "reason=${outcome.reason}"
            }
            return "mdblist " + fields.joinToString(" ")
        }

        private fun scrobbleProgress(request: MDBListRequest): String? = runCatching {
            val json = request.json ?: return null
            org.json.JSONObject(json).opt("progress")?.toString()
        }.getOrNull()

        private fun answeredAction(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("action", "").trim().ifEmpty { null }
        }.getOrNull()

        internal fun classify(response: MDBListHttpResponse): MDBListOutcome = when {
            response.status in 200..299 -> MDBListOutcome.Success(response)
            response.status == 401 -> MDBListOutcome.Unauthorized(errorCode(response.body) ?: "unauthorized")
            response.status == 429 -> MDBListOutcome.Retry("rate_limit", retryAfterMs(response))
            response.status == 500 ||
                response.status == 502 ||
                response.status == 503 ||
                response.status == 504 -> MDBListOutcome.Retry("provider_unavailable", retryAfterMs(response))
            else -> MDBListOutcome.Rejected(statusReason(response))
        }

        internal fun classifyDevicePoll(response: MDBListHttpResponse): MDBListOutcome {
            if (response.status in 200..299) return MDBListOutcome.Success(response)
            when (errorCode(response.body)) {
                DEVICE_PENDING -> return MDBListOutcome.Rejected(DEVICE_PENDING)
                DEVICE_SLOW_DOWN -> return MDBListOutcome.Retry(DEVICE_SLOW_DOWN, retryAfterMs(response))
                DEVICE_DENIED -> return MDBListOutcome.Rejected(DEVICE_DENIED)
                DEVICE_EXPIRED -> return MDBListOutcome.Rejected(DEVICE_EXPIRED)
                DEVICE_NOT_FOUND -> return MDBListOutcome.Rejected(DEVICE_NOT_FOUND)
                INVALID_CLIENT -> return MDBListOutcome.Unauthorized(INVALID_CLIENT)
            }
            return when (response.status) {
                401 -> MDBListOutcome.Unauthorized(errorCode(response.body) ?: "unauthorized")
                429 -> MDBListOutcome.Retry("rate_limit", retryAfterMs(response))
                500, 502, 503, 504 -> MDBListOutcome.Retry("provider_unavailable", retryAfterMs(response))

                else -> MDBListOutcome.Rejected(statusReason(response))
            }
        }

        internal fun statusReason(response: MDBListHttpResponse): String {
            val error = errorCode(response.body)
            return if (error == null) "http_${response.status}" else "http_${response.status}:$error"
        }

        internal const val DEVICE_PENDING = "authorization_pending"
        internal const val DEVICE_SLOW_DOWN = "slow_down"
        internal const val DEVICE_DENIED = "access_denied"
        internal const val DEVICE_EXPIRED = "expired_token"
        internal const val DEVICE_NOT_FOUND = "device_not_found"

        internal fun retryAfterMs(response: MDBListHttpResponse): Long? =
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

object MDBListReadBudgets {

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
        maxResponseBytes = 8L * 1024 * 1024,
        maxDecompressedBytes = 16L * 1024 * 1024,
        maxRequestBytes = 64L * 1024,
        totalTimeoutMs = 45_000L,
    )

    fun limitsFor(budget: MDBListReadBudget): NetworkLimits = when (budget) {
        MDBListReadBudget.DEFAULT -> DEFAULT
        MDBListReadBudget.LOOKUP -> LOOKUP
        MDBListReadBudget.WATCHED_HISTORY -> WATCHED_HISTORY
    }
}

class HardenedMDBListTransport(
    private val client: HardenedHttpClient,
) : MDBListTransport {

    override suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): MDBListHttpResponse {
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
                    throw MDBListResponseTooLargeException(
                        maxBytes = limits.maxResponseBytes,
                        maxDecompressedBytes = limits.maxDecompressedBytes,
                        cause = limit,
                    )
                }
                throw limit
            }
            return MDBListHttpResponse(closed.statusCode, text, closed.headers)
        }
    }
}
