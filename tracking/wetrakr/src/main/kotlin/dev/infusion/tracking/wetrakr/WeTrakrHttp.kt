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

    data class Rejected(val reason: String, val oldest: java.time.Instant? = null) : WeTrakrOutcome
}

class WeTrakrHttpClient(
    private val configuration: WeTrakrConfiguration,
    private val transport: WeTrakrTransport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },

    private val trace: ((String) -> Unit)? = null,

    private val admission: WeTrakrReadAdmission? = null,

    private val admissionPartition: () -> WeTrakrReadAdmission.Partition =
        { WeTrakrReadAdmission.Partition(DEFAULT_ADMISSION_KEY, null) },
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

        if (admission != null && chargedAgainstQuota(request) && admission.anyClosed()) {
            val partition = admissionPartition()
            admission.closedFor(partition)?.let { closed ->
                return traced(
                    request,
                    WeTrakrOutcome.Retry(
                        reason = closed.reason,
                        retryAfterMs = (closed.untilMs - nowMs()).coerceAtLeast(0L),
                    ),
                )
            }
        }
        val url = buildUrl(request)
        val headers = buildHeaders(request, accessToken)
        val body = request.json?.toByteArray(StandardCharsets.UTF_8)
        val attempts = if (request.retryable) MAX_ATTEMPTS else 1
        var attempt = 0
        while (attempt < attempts) {
            val response = try {
                paced { transport.execute(url, request.method.name, headers, body, request.limits ?: LIMITS) }
            } catch (oversize: WeTrakrResponseTooLargeException) {

                return traced(
                    request,
                    WeTrakrOutcome.Rejected(RESPONSE_TOO_LARGE_REASON),
                    attempt = attempt + 1,
                    maxAttempts = attempts,
                )
            } catch (error: Throwable) {

                val transportFailure = WeTrakrOutcome.Retry(TRANSPORT_FAILURE, null)
                if (attempt == attempts - 1) {
                    return traced(
                        request,
                        transportFailure,
                        attempt = attempt + 1,
                        maxAttempts = attempts,
                        failure = error,
                    )
                }
                emitAttempt(request, transportFailure, null, attempt + 1, attempts, failure = error)
                sleep(retryDelayMs(attempt, retryAfterMs = null))
                attempt++
                continue
            }
            val outcome = if (request.devicePoll) {
                classifyDevicePoll(response)
            } else {
                classify(response)
            }
            val retry = outcome as? WeTrakrOutcome.Retry

            if (retry != null && isDailyQuotaReason(retry.reason)) {

                val wait = retry.retryAfterMs ?: quotaResetMs(response)

                if (admission != null && wait != null) {
                    admission.close(admissionPartition(), quotaScopeOf(retry.reason), retry.reason, wait)
                }
                return traced(
                    request,
                    WeTrakrOutcome.Retry(
                        reason = retry.reason,
                        retryAfterMs = wait,
                    ),
                    response,
                    attempt + 1,
                    attempts,
                )
            }
            if (retry == null || attempt == attempts - 1) {
                return traced(request, outcome, response, attempt + 1, attempts)
            }
            emitAttempt(request, outcome, response, attempt + 1, attempts)
            sleep(retryDelayMs(attempt, retry.retryAfterMs))
            attempt++
        }
        return traced(request, WeTrakrOutcome.Retry(TRANSPORT_FAILURE, null))
    }

    private fun emitAttempt(
        request: WeTrakrRequest,
        outcome: WeTrakrOutcome,
        response: WeTrakrHttpResponse?,
        attempt: Int,
        maxAttempts: Int,

        failure: Throwable? = null,
    ) {
        trace?.invoke(traceLine(request, outcome, response, attempt, maxAttempts, failure))
    }

    private fun traced(
        request: WeTrakrRequest,
        outcome: WeTrakrOutcome,
        response: WeTrakrHttpResponse? = null,
        attempt: Int? = null,
        maxAttempts: Int? = null,

        failure: Throwable? = null,
    ): WeTrakrOutcome {
        trace?.invoke(traceLine(request, outcome, response, attempt, maxAttempts, failure))
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

    private fun chargedAgainstQuota(request: WeTrakrRequest): Boolean =
        request.authenticated || request.appKeyOnly || request.optionalAuth

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

        const val MAX_WATCHED_WRITE_BATCH: Int = 20

        internal const val MISSING_CLIENT_ID = "no WeTrakr client registration configured"
        internal const val NO_TOKEN = "no stored WeTrakr authorization"
        internal const val TRANSPORT_FAILURE = "wetrakr transport failure"
        internal const val DEVICE_CODE_BUDGET_EXHAUSTED = "device_code_budget_exhausted"
        internal const val RESPONSE_TOO_LARGE_REASON = "response_too_large"

        internal const val DEFAULT_ADMISSION_KEY = "wetrakr:default"

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

        internal fun quotaResetMs(response: WeTrakrHttpResponse): Long? =
            response.headers.entries.firstOrNull { it.key.equals("X-Quota-Reset", ignoreCase = true) }
                ?.value?.trim()?.toLongOrNull()
                ?.takeIf { it in 1..DAILY_WINDOW_MAX_SECONDS }
                ?.times(1_000L)

        internal const val DAILY_WINDOW_MAX_SECONDS: Long = 86_400L

        internal const val DAILY_WINDOW_MAX_MS: Long = DAILY_WINDOW_MAX_SECONDS * 1_000L

        internal val DAILY_QUOTA_REASONS: Set<String> = setOf(
            "$RATE_LIMIT_REASON:$QUOTA_EXCEEDED_CLASS",
            APP_WIDE_QUOTA_REASON,
            APP_USER_QUOTA_REASON,
        )

        internal fun isDailyQuotaReason(reason: String): Boolean = reason in DAILY_QUOTA_REASONS

        internal fun quotaScopeOf(reason: String): WeTrakrQuotaScope = when (reason) {
            APP_WIDE_QUOTA_REASON -> WeTrakrQuotaScope.APP_WIDE
            APP_USER_QUOTA_REASON -> WeTrakrQuotaScope.APP_USER
            else -> WeTrakrQuotaScope.ACCOUNT
        }

        internal fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long {
            val exponential = 1_000L shl attempt.coerceIn(0, 5)
            return max(exponential, retryAfterMs ?: 0L).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal const val MAX_RETRY_DELAY_MS: Long = 30_000L

        internal fun errorCode(body: String): String? = runCatching {
            org.json.JSONObject(body).optString("error", "").trim().ifEmpty { null }
        }.getOrNull()

        private fun isJsonObject(body: String): Boolean = runCatching { org.json.JSONObject(body) }.isSuccess

        internal const val RATE_LIMIT_REASON: String = "rate_limit"

        internal const val QUOTA_EXCEEDED_CLASS: String = "QUOTA_EXCEEDED"

        internal const val APP_WIDE_QUOTA_REASON: String = "$RATE_LIMIT_REASON:APP_QUOTA_EXCEEDED"

        internal const val APP_USER_QUOTA_REASON: String = "$RATE_LIMIT_REASON:APP_USER_QUOTA_EXCEEDED"

        internal const val RATE_LIMIT_WINDOW_REASON: String = "rate_limit:window"

        internal const val RATE_LIMIT_UNREADABLE_REASON: String = "rate_limit:http_429"

        private const val MAX_RATE_LIMIT_CODE_LENGTH: Int = 40

        private val RATE_LIMIT_CODE_SHAPE: Regex = Regex("[A-Za-z0-9_]{1,$MAX_RATE_LIMIT_CODE_LENGTH}")

        internal fun rateLimitReason(body: String): String {
            val stated = errorCode(body)
            if (stated != null) {
                val code = stated.trim()
                return if (RATE_LIMIT_CODE_SHAPE.matches(code)) {
                    "$RATE_LIMIT_REASON:${code.uppercase()}"
                } else {
                    RATE_LIMIT_UNREADABLE_REASON
                }
            }

            return if (isJsonObject(body)) RATE_LIMIT_WINDOW_REASON else RATE_LIMIT_UNREADABLE_REASON
        }

        internal fun limitCounters(headers: Map<String, String>): List<String> =
            LIMIT_HEADER_FIELDS.mapNotNull { (header, field) ->
                val raw = headers.entries.firstOrNull { it.key.equals(header, ignoreCase = true) }?.value
                val value = raw?.trim()?.toLongOrNull() ?: return@mapNotNull null
                "$field=$value"
            }

        private val LIMIT_HEADER_FIELDS: List<Pair<String, String>> = listOf(
            "RateLimit-Limit" to "ratelimit_limit",
            "RateLimit-Remaining" to "ratelimit_remaining",
            "RateLimit-Reset" to "ratelimit_reset",
            "X-Quota-Limit" to "quota_limit",
            "X-Quota-Remaining" to "quota_remaining",
            "X-Quota-Reset" to "quota_reset",
            "X-App-Quota-Limit" to "app_quota_limit",
            "X-App-Quota-Remaining" to "app_quota_remaining",
            "X-App-User-Quota-Limit" to "app_user_quota_limit",
            "X-App-User-Quota-Remaining" to "app_user_quota_remaining",
        )

        internal fun statusReason(response: WeTrakrHttpResponse): String {
            val code = errorCode(response.body)?.trim()?.takeIf { RATE_LIMIT_CODE_SHAPE.matches(it) }
            return if (code == null) "http_${response.status}" else "http_${response.status}:$code"
        }

        internal fun unauthorizedReason(response: WeTrakrHttpResponse): String =
            errorCode(response.body)?.trim()?.takeIf { RATE_LIMIT_CODE_SHAPE.matches(it) }
                ?: "unauthorized"

        internal fun classify(response: WeTrakrHttpResponse): WeTrakrOutcome = when {
            response.status in 200..299 -> WeTrakrOutcome.Success(response)
            response.status == 401 -> WeTrakrOutcome.Unauthorized(unauthorizedReason(response))

            response.status == 429 -> WeTrakrOutcome.Retry(rateLimitReason(response.body), retryAfterMs(response))
            response.status == 500 ||
                response.status == 502 ||
                response.status == 503 ||
                response.status == 504 -> WeTrakrOutcome.Retry("provider_unavailable", retryAfterMs(response))

            else -> WeTrakrOutcome.Rejected(statusReason(response), journalExpiredOldest(response))
        }

        internal fun journalExpiredOldest(response: WeTrakrHttpResponse): java.time.Instant? {
            if (errorCode(response.body)?.trim()?.uppercase() != JOURNAL_EXPIRED_ERROR) return null
            val oldest = runCatching { org.json.JSONObject(response.body) }
                .getOrNull()
                ?.optString("oldest", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return runCatching { java.time.Instant.parse(oldest) }.getOrNull()
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
                401 -> WeTrakrOutcome.Unauthorized(unauthorizedReason(response))
                429 -> WeTrakrOutcome.Retry(DEVICE_TOO_MANY, retryAfterMs(response))
                500, 502, 503, 504 -> WeTrakrOutcome.Retry("provider_unavailable", retryAfterMs(response))

                else -> WeTrakrOutcome.Retry(statusReason(response), retryAfterMs(response))
            }
        }

        internal fun traceLine(
            request: WeTrakrRequest,
            outcome: WeTrakrOutcome,
            response: WeTrakrHttpResponse? = null,
            attempt: Int? = null,
            maxAttempts: Int? = null,

            failure: Throwable? = null,
        ): String {
            val fields = mutableListOf(
                "endpoint=${pathTemplate(request.path)}",
                "method=${request.method.name}",
            )
            if (attempt != null && maxAttempts != null) fields += "attempt=$attempt/$maxAttempts"
            val answered = response ?: (outcome as? WeTrakrOutcome.Success)?.response
            when (outcome) {
                is WeTrakrOutcome.Success -> {
                    fields += "outcome=delivered"
                    fields += "status=${outcome.response.status}"

                    fields += "body_bytes=${outcome.response.body.length}"
                }
                is WeTrakrOutcome.Retry -> {
                    fields += "outcome=retryable"
                    fields += "reason=${reasonField(outcome.reason)}"
                    outcome.retryAfterMs?.let { fields += "retry_after_ms=$it" }
                }
                is WeTrakrOutcome.Rejected -> {
                    fields += "outcome=permanent"
                    fields += "reason=${reasonField(outcome.reason)}"
                }
                is WeTrakrOutcome.Unauthorized -> {
                    fields += "outcome=unauthorized"
                    fields += "reason=${reasonField(outcome.reason)}"
                }
            }

            if (failure != null) {
                fields += "exc=${throwableChain(failure)}"
                policyReason(failure)?.let { fields += "policy=$it" }
            }
            if (answered != null && outcome !is WeTrakrOutcome.Success) {
                fields += "status=${answered.status}"
            }

            if (answered != null) fields += limitCounters(answered.headers)
            return "wetrakr " + fields.joinToString(" ")
        }

        internal const val MAX_FAILURE_CHAIN_LINKS: Int = 4

        internal const val MAX_CLASS_NAME_FIELD: Int = 48

        private val CLASS_NAME_SHAPE: Regex = Regex("[A-Za-z0-9_.$]{1,$MAX_CLASS_NAME_FIELD}")

        internal fun throwableChain(error: Throwable): String {
            val names = ArrayList<String>(MAX_FAILURE_CHAIN_LINKS)
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>(),
            )
            var current: Throwable? = error
            while (current != null && names.size < MAX_FAILURE_CHAIN_LINKS) {
                if (!seen.add(current)) break
                names += className(current)
                current = current.cause
            }
            return if (names.isEmpty()) UNKNOWN_FIELD else names.joinToString("<")
        }

        internal fun className(error: Throwable): String {

            if (error is NetworkPolicyException) return POLICY_EXCEPTION_NAME
            val name = error.javaClass.simpleName?.takeIf { it.isNotBlank() }
                ?: error.javaClass.name.substringAfterLast('.')
            return name.takeIf { CLASS_NAME_SHAPE.matches(it) } ?: UNKNOWN_FIELD
        }

        internal fun policyReason(error: Throwable): String? {
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>(),
            )
            var current: Throwable? = error
            var links = 0
            while (current != null && links < MAX_FAILURE_CHAIN_LINKS) {
                if (!seen.add(current)) break
                if (current is NetworkPolicyException) return current.reason.name
                current = current.cause
                links++
            }
            return null
        }

        internal const val POLICY_EXCEPTION_NAME: String = "NetworkPolicyException"

        private val PATH_WORDS: Set<String> = setOf(
            "media", "external", "imdb", "tmdb", "tvdb",
            "sync", "tracking", "watched", "history", "unified", "playing", "watching",
            "movies", "shows", "seasons", "episodes", "lists", "trending", "last", "remove",
            "account", "settings", "oauth", "device", "code", "token", "refresh", "logout",
            "scrobble", "start", "pause", "stop", "ratings", "comments", "planning",

            "journal",

            "last_activities", "counters", "total-time",
        )

        internal fun pathTemplate(path: String): String =
            path.split('/').joinToString("/") { segment ->
                if (segment.isEmpty() || segment.lowercase() in PATH_WORDS) segment else "{id}"
            }

        internal fun reasonField(raw: String): String {
            for (char in raw) {
                if (char.isWhitespace()) continue
                if ((char.isLetterOrDigit() && char.code < 128) || char in "_.:-") continue
                return UNKNOWN_FIELD
            }
            val bounded = StringBuilder(minOf(raw.length, MAX_TRACE_FIELD))
            for (char in raw) {
                if (bounded.length >= MAX_TRACE_FIELD) break
                if (char.isWhitespace()) {
                    if (bounded.isNotEmpty() && bounded.last() != '-') bounded.append('-')
                } else {
                    bounded.append(char)
                }
            }
            val trimmed = bounded.toString().trim('-')
            return if (trimmed.isEmpty()) UNKNOWN_FIELD else trimmed
        }

        internal const val MAX_TRACE_FIELD: Int = 64
        internal const val UNKNOWN_FIELD = "unknown"

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

        const val TRACKING_WATCHED_COUNTERS_PATH = "/sync/tracking/watched/counters"

        const val TRACKING_WATCHED_TOTAL_TIME_PATH = "/sync/tracking/watched/total-time"

        const val TRACKING_PATH = "/sync/tracking"

        const val JOURNAL_PATH = "/sync/journal"

        internal const val JOURNAL_EXPIRED_ERROR = "JOURNAL_EXPIRED"

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
