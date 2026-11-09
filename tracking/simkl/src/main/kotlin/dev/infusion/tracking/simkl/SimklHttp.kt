package dev.infusion.tracking.simkl

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SimklConfiguration(
    val clientId: String,
    val appName: String,
    val appVersion: String,
    val baseUrl: String = DEFAULT_BASE_URL,
) {

    val hasClientId: Boolean get() = clientId.isNotBlank()

    companion object {

        const val DEFAULT_BASE_URL: String = "https://api.simkl.com"
    }
}

enum class SimklMethod { GET, POST, DELETE }

enum class SimklReadBudget {

    DEFAULT,

    LOOKUP,

    UP_NEXT,

    WATCHED_HISTORY,
}

data class SimklRequest(
    val method: SimklMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),

    val form: Map<String, String>? = null,

    val json: String? = null,

    val authenticated: Boolean = true,

    val retryable: Boolean = true,

    val conflictIsSuccess: Boolean = false,

    val readBudget: SimklReadBudget = SimklReadBudget.DEFAULT,
)

data class SimklHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

fun interface SimklTransport {
    suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): SimklHttpResponse
}

class SimklResponseTooLargeException(
    val maxBytes: Long,
    val maxDecompressedBytes: Long,
    cause: Throwable? = null,
) : Exception(
    "simkl response exceeds the bounded budget of $maxBytes bytes ($maxDecompressedBytes decompressed)",
    cause,
)

sealed interface SimklOutcome {

    data class Success(val response: SimklHttpResponse) : SimklOutcome

    data class Retry(val reason: String, val retryAfterMs: Long?) : SimklOutcome

    data class Unauthorized(val reason: String) : SimklOutcome

    data class Rejected(val reason: String) : SimklOutcome
}

class SimklRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SimklHttpClient(
    private val configuration: SimklConfiguration,
    private val transport: SimklTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },

    private val trace: ((String) -> Unit)? = null,
) {

    private val scheduleMutex = Mutex()
    private var nextGetAtMs = 0L
    private var nextPostAtMs = 0L

    suspend fun execute(request: SimklRequest, accessToken: String? = null): SimklOutcome {
        if (!configuration.hasClientId) {
            return traced(request, SimklOutcome.Rejected(MISSING_CLIENT_ID))
        }
        if (request.authenticated && accessToken.isNullOrBlank()) {
            return traced(request, SimklOutcome.Unauthorized(NO_TOKEN))
        }
        val url = buildUrl(request)
        val headers = buildHeaders(request, accessToken)
        val body = encodeBody(request)
        val limits = SimklReadBudgets.limitsFor(request.readBudget)
        val attempts = if (request.retryable) MAX_ATTEMPTS else 1
        var last: SimklOutcome = SimklOutcome.Retry(TRANSPORT_FAILURE, null)
        var attempt = 0
        while (attempt < attempts) {
            val method = request.method
            val response = try {
                paced(method) { transport.execute(url, method.name, headers, body, limits) }
            } catch (oversize: SimklResponseTooLargeException) {

                return traced(request, SimklOutcome.Rejected(RESPONSE_TOO_LARGE_REASON))
            } catch (error: Throwable) {
                if (attempt == attempts - 1) {
                    return traced(request, SimklOutcome.Retry(TRANSPORT_FAILURE, null))
                }
                sleep(retryDelayMs(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            val outcome = classify(response, request.conflictIsSuccess)
            when (outcome) {
                is SimklOutcome.Success -> return traced(request, outcome)
                is SimklOutcome.Unauthorized -> return traced(request, outcome)
                is SimklOutcome.Rejected -> return traced(request, outcome)
                is SimklOutcome.Retry -> {
                    last = outcome
                    if (attempt == attempts - 1) return traced(request, outcome)

                    val delay = if (outcome.reason == WRITE_LOCK_REASON) {
                        WRITE_LOCK_RETRY_DELAY_MS
                    } else {
                        retryDelayMs(attempt, outcome.retryAfterMs)
                    }
                    sleep(delay)
                }
            }
            attempt++
        }
        return traced(request, last)
    }

    private fun traced(request: SimklRequest, outcome: SimklOutcome): SimklOutcome {
        trace?.invoke(traceLine(request, outcome))
        return outcome
    }

    private suspend fun <T> paced(method: SimklMethod, block: suspend () -> T): T {
        scheduleMutex.withLock {
            val now = nowMs()
            val scheduledAt = if (method == SimklMethod.GET) nextGetAtMs else nextPostAtMs
            if (scheduledAt > now) sleep(scheduledAt - now)
            try {
                return block()
            } finally {
                val completedAt = nowMs()
                val interval = if (method == SimklMethod.GET) GET_INTERVAL_MS else POST_INTERVAL_MS
                if (method == SimklMethod.GET) {
                    nextGetAtMs = max(nextGetAtMs, completedAt + interval)
                } else {
                    nextPostAtMs = max(nextPostAtMs, completedAt + interval)
                }
            }
        }
    }

    fun buildUrl(request: SimklRequest): CanonicalUrl {
        val query = buildString {
            request.query.forEach { (key, value) ->
                if (key !in REQUIRED_PARAMETERS) appendParam(key, value)
            }
            appendParam("client_id", configuration.clientId)
            appendParam("app-name", configuration.appName)
            appendParam("app-version", configuration.appVersion)
        }
        return UrlParser.parse("${configuration.baseUrl}${request.path}?$query")
    }

    private fun StringBuilder.appendParam(key: String, value: String) {
        if (isNotEmpty()) append('&')
        append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
        append('=')
        append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()))
    }

    fun buildHeaders(request: SimklRequest, accessToken: String?): Map<String, String> = buildMap {
        put("User-Agent", "fission/${configuration.appVersion}")
        put("Accept", "application/json")
        if (request.authenticated && !accessToken.isNullOrBlank()) {
            put("Authorization", "Bearer $accessToken")
        }
        if (request.form != null || request.json != null) {
            put(
                "Content-Type",
                if (request.form != null) "application/x-www-form-urlencoded" else "application/json",
            )
        }
    }

    private fun encodeBody(request: SimklRequest): ByteArray? {
        request.form?.let { form ->
            return form.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=" +
                    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            }.toByteArray(StandardCharsets.UTF_8)
        }
        return request.json?.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {

        const val GET_INTERVAL_MS: Long = 100L

        const val POST_INTERVAL_MS: Long = 1_000L

        const val MAX_ATTEMPTS: Int = 4

        const val WRITE_LOCK_RETRY_DELAY_MS: Long = 3_000L

        internal const val MISSING_CLIENT_ID = "no Simkl client registration configured"
        internal const val NO_TOKEN = "no stored Simkl authorization"
        internal const val TRANSPORT_FAILURE = "simkl transport failure"

        internal const val WRITE_LOCK_REASON = "rate_limit_lock"

        internal const val RESPONSE_TOO_LARGE_REASON = "response_too_large"

        internal fun errorCode(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("error", "").trim().ifEmpty { null }
        }.getOrNull()

        internal fun traceLine(request: SimklRequest, outcome: SimklOutcome): String {
            val fields = mutableListOf("path=${request.path}", "class=${outcome::class.simpleName}")
            sentProgress(request)?.let { fields += "sent_progress=$it" }
            when (outcome) {
                is SimklOutcome.Success -> {
                    fields += "status=${outcome.response.status}"

                    fields += "body_bytes=${outcome.response.body.length}"
                    answeredAction(outcome.response.body)?.let { fields += "action=$it" }
                    answeredProgress(outcome.response.body)?.let { fields += "answer_progress=$it" }
                }
                is SimklOutcome.Retry -> fields += "reason=${outcome.reason}"
                is SimklOutcome.Rejected -> fields += "reason=${outcome.reason}"
                is SimklOutcome.Unauthorized -> fields += "reason=${outcome.reason}"
            }
            return "simkl " + fields.joinToString(" ")
        }

        private fun sentProgress(request: SimklRequest): String? = runCatching {
            val json = request.json ?: return null
            org.json.JSONObject(json).opt("progress")?.toString()
        }.getOrNull()

        private fun answeredAction(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("action", "").trim().ifEmpty { null }
        }.getOrNull()

        private fun answeredProgress(body: String): String? = runCatching {
            org.json.JSONObject(body).opt("progress")?.toString()
        }.getOrNull()

        internal fun isWriteLock(response: SimklHttpResponse): Boolean =
            response.status == 400 && errorCode(response.body)?.equals("RATE_LIMIT", ignoreCase = true) == true

        internal fun classify(response: SimklHttpResponse, conflictIsSuccess: Boolean): SimklOutcome = when {
            response.status in 200..299 -> SimklOutcome.Success(response)
            response.status == 409 && conflictIsSuccess -> SimklOutcome.Success(response)
            response.status == 401 -> SimklOutcome.Unauthorized(errorCode(response.body) ?: "unauthorized")
            isWriteLock(response) -> SimklOutcome.Retry(WRITE_LOCK_REASON, null)
            response.status == 429 -> SimklOutcome.Retry("rate_limit", retryAfterMs(response))
            response.status == 500 || response.status == 502 || response.status == 503 ->
                SimklOutcome.Retry("provider_unavailable", retryAfterMs(response))
            else -> SimklOutcome.Rejected(errorCode(response.body) ?: "http_${response.status}")
        }

        internal fun retryAfterMs(response: SimklHttpResponse): Long? =
            response.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.substringBefore(',')?.trim()?.toLongOrNull()
                ?.takeIf { it >= 0 }?.times(1_000L)

        internal fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long {
            val exponential = 1_000L shl attempt.coerceIn(0, 5)
            return max(exponential, retryAfterMs ?: 0L).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal const val MAX_RETRY_DELAY_MS: Long = 30_000L

        internal val REQUIRED_PARAMETERS = setOf("client_id", "app-name", "app-version")
    }
}

object SimklReadBudgets {

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

    val UP_NEXT: NetworkLimits = NetworkLimits.DEFAULT.copy(
        maxResponseBytes = 1L * 1024 * 1024,
        maxDecompressedBytes = 4L * 1024 * 1024,
        maxRequestBytes = 64L * 1024,
        totalTimeoutMs = 30_000L,
    )

    val WATCHED_HISTORY: NetworkLimits = NetworkLimits.DEFAULT.copy(
        maxResponseBytes = 4L * 1024 * 1024,
        maxDecompressedBytes = 8L * 1024 * 1024,
        maxRequestBytes = 64L * 1024,
        totalTimeoutMs = 45_000L,
    )

    fun limitsFor(budget: SimklReadBudget): NetworkLimits = when (budget) {
        SimklReadBudget.DEFAULT -> DEFAULT
        SimklReadBudget.LOOKUP -> LOOKUP
        SimklReadBudget.UP_NEXT -> UP_NEXT
        SimklReadBudget.WATCHED_HISTORY -> WATCHED_HISTORY
    }
}

class HardenedSimklTransport(
    private val client: HardenedHttpClient,
) : SimklTransport {

    override suspend fun execute(
        url: CanonicalUrl,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limits: NetworkLimits,
    ): SimklHttpResponse {
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
                    throw SimklResponseTooLargeException(
                        maxBytes = limits.maxResponseBytes,
                        maxDecompressedBytes = limits.maxDecompressedBytes,
                        cause = limit,
                    )
                }
                throw limit
            }
            return SimklHttpResponse(closed.statusCode, text, closed.headers)
        }
    }
}
