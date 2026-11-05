package dev.infusion.core.network

import java.util.UUID

enum class DiagnosticResource {
    MANIFEST,
    CATALOG,
    META,
    STREAM,
    SUBTITLE,
    PLAYLIST,
    HEALTH,
}

enum class DiagnosticLimitGuard {

    RESPONSE_BODY_BYTES,

    DECOMPRESSED_BYTES,

    DECOMPRESSION_RATIO,

    JSON_NODES,

    JSON_DEPTH,

    JSON_OBJECT_KEYS,

    JSON_ARRAY_ELEMENTS,

    STREAM_ARRAY,

    EMBEDDED_SUBTITLES,

    SUBTITLE_RESPONSE,
}

enum class DiagnosticDetailKey {

    BYTES_READ,

    STREAM_ARRAY_COUNT,

    STREAMS_ACCEPTED,

    STREAMS_REJECTED,
}

data class RedactedDiagnostic(
    val correlationId: String,
    val reason: DiagnosticReason,
    val origin: String? = null,
    val resource: DiagnosticResource? = null,
    val statusCode: Int? = null,
    val bytesRead: Long = 0,
    val elapsedMs: Long = 0,

    val limitGuard: DiagnosticLimitGuard? = null,

    val diagnosticDetails: Map<DiagnosticDetailKey, Long> = emptyMap(),
) {
    init {
        require(correlationId.matches(Regex("^[0-9a-f]{8}$"))) { "invalid correlation id" }
        require(origin == null || UrlParser.parse(origin).origin == origin) { "origin must be canonical and path-free" }
        require(statusCode == null || statusCode in 100..599) { "invalid status code" }
        require(bytesRead >= 0) { "bytes read must be non-negative" }
        require(elapsedMs >= 0) { "elapsed time must be non-negative" }
        require(limitGuard == null || reason == DiagnosticReason.LIMIT_EXCEEDED) {
            "limit guard must only accompany LIMIT_EXCEEDED"
        }
        require(diagnosticDetails.values.all { it >= 0 }) { "diagnostic details must be non-negative" }
    }

    fun toLogLine(): String = buildString {
        append("corr=").append(correlationId)
        append(" reason=").append(reason.name)
        origin?.let { append(" origin=").append(it) }
        resource?.let { append(" resource=").append(it.name) }
        statusCode?.let { append(" status=").append(it) }
        if (bytesRead > 0) append(" bytes=").append(bytesRead)
        if (elapsedMs > 0) append(" ms=").append(elapsedMs)
        limitGuard?.let { append(" guard=").append(it.name) }
        if (diagnosticDetails.isNotEmpty()) {
            append(" details=")
            diagnosticDetails.toSortedMap().forEach { (k, v) -> append(k.name).append(':').append(v).append(',') }
        }
    }
}

object CorrelationIds {
    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
}

class DiagnosticsCollector(
    private val correlationId: String = CorrelationIds.newId(),
    private val origin: String? = null,
    private val resource: DiagnosticResource? = null,
) {
    init {
        require(correlationId.matches(Regex("^[0-9a-f]{8}$"))) { "invalid correlation id" }
        require(origin == null || UrlParser.parse(origin).origin == origin) { "origin must be canonical and path-free" }
    }

    private val startNanos = System.nanoTime()

    @Volatile
    private var limitGuard: DiagnosticLimitGuard? = null

    private val details = java.util.concurrent.ConcurrentHashMap<DiagnosticDetailKey, Long>()

    fun recordLimitGuard(guard: DiagnosticLimitGuard): DiagnosticsCollector = apply {
        limitGuard = guard
    }

    fun recordDetail(key: DiagnosticDetailKey, value: Long): DiagnosticsCollector = apply {
        require(value >= 0) { "detail value must be non-negative" }
        details[key] = value
    }

    fun finish(
        reason: DiagnosticReason,
        statusCode: Int? = null,
        bytesRead: Long = 0,
    ): RedactedDiagnostic {
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        return RedactedDiagnostic(
            correlationId = correlationId,
            reason = reason,
            origin = origin,
            resource = resource,
            statusCode = statusCode,
            bytesRead = bytesRead,
            elapsedMs = elapsedMs,
            limitGuard = if (reason == DiagnosticReason.LIMIT_EXCEEDED) limitGuard else null,
            diagnosticDetails = details.toSortedMap(),
        )
    }
}
