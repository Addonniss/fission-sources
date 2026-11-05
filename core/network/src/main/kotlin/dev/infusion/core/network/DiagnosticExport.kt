package dev.infusion.core.network

import java.util.UUID

data class PlaybackDiagnosticSummary(
    val sessionId: String? = null,
    val engineName: String? = null,
    val engineVersion: String? = null,
    val state: String? = null,
    val positionMs: Long? = null,
    val droppedFrames: Int? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val bufferingPercent: Int? = null,
    val reason: String? = null,
    val createdAtMs: Long,
) {
    init {

        require(sessionId.isNullOrBlank() || sessionId.matches(Regex("[0-9a-zA-Z-]+"))) { "session id carries disallowed characters" }
        require(reason == null || reason.matches(Regex("^[A-Z_]{1,40}$"))) { "reason must be an enum name, not free text" }

        require(engineName.isNullOrBlank() || engineName.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$"))) { "engine name must be a plain identifier" }
        require(engineVersion.isNullOrBlank() || engineVersion.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9._+-]{0,63}$"))) { "engine version must be a plain version string" }
        require(state.isNullOrBlank() || state.matches(Regex("^[A-Z_]{1,40}$"))) { "state must be an enum name, not free text" }
    }
}

data class DiagnosticAppDescriptor(
    val name: String,
    val version: String,
    val sdk: Int? = null,
)

data class DiagnosticCounts(
    val profiles: Int,
    val sources: Int,
    val addons: Int,
)

data class DiagnosticBundle(
    val bundleId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val app: DiagnosticAppDescriptor,
    val counts: DiagnosticCounts,
    val sources: List<RedactedDiagnostic>,
    val playback: List<PlaybackDiagnosticSummary>,
) {
    init {
        require(bundleId.matches(Regex("^[0-9a-f]{8}$"))) { "bundle id must be 8 hex chars" }
        require(expiresAtMs > createdAtMs) { "expiry must be after creation" }
    }

    fun isExpired(nowMs: Long): Boolean = nowMs > expiresAtMs
}

object DiagnosticExporter {

    const val DEFAULT_TTL_MS: Long = 72L * 60 * 60 * 1000

    fun newBundleId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun build(
        app: DiagnosticAppDescriptor,
        counts: DiagnosticCounts,
        sources: List<RedactedDiagnostic>,
        playback: List<PlaybackDiagnosticSummary>,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): DiagnosticBundle = DiagnosticBundle(
        bundleId = newBundleId(),
        createdAtMs = nowMs,
        expiresAtMs = nowMs + ttlMs,
        app = app,
        counts = counts,
        sources = sources,
        playback = playback,
    )

    fun toPreview(bundle: DiagnosticBundle): String = buildString {
        append("Diagnostic bundle ").append(bundle.bundleId)
        append("\n  app: ").append(bundle.app.name).append(' ').append(bundle.app.version)
        bundle.app.sdk?.let { append(" (SDK ").append(it).append(')') }
        append("\n  expires: ").append(bundle.expiresAtMs)
        append("\n  profiles: ").append(bundle.counts.profiles)
        append("  sources: ").append(bundle.counts.sources)
        append("  add-ons: ").append(bundle.counts.addons)
        append("\n  source diagnostics: ").append(bundle.sources.size)
        for (d in bundle.sources) {
            append("\n    ").append(d.toLogLine())
        }
        append("\n  player diagnostics: ").append(bundle.playback.size)
        for (p in bundle.playback) {
            append("\n    sess=").append(p.sessionId ?: "-")
            p.engineName?.let { append(" engine=").append(it) }
            p.state?.let { append(" state=").append(it) }
            p.reason?.let { append(" reason=").append(it) }
        }
    }

    fun toJson(bundle: DiagnosticBundle): String = buildString {
        append('{')
        append("\"schema\":\"infusion/diagnostic-bundle/v1\",")
        append("\"bundleId\":").append(quote(bundle.bundleId)).append(',')
        append("\"createdAtMs\":").append(bundle.createdAtMs).append(',')
        append("\"expiresAtMs\":").append(bundle.expiresAtMs).append(',')
        append("\"app\":{")
        append("\"name\":").append(quote(bundle.app.name)).append(',')
        append("\"version\":").append(quote(bundle.app.version))
        bundle.app.sdk?.let { append(",\"sdk\":").append(it) }
        append("},")
        append("\"counts\":{")
        append("\"profiles\":").append(bundle.counts.profiles).append(',')
        append("\"sources\":").append(bundle.counts.sources).append(',')
        append("\"addons\":").append(bundle.counts.addons)
        append("},")
        append("\"sources\":[")
        bundle.sources.forEachIndexed { i, d ->
            if (i > 0) append(',')
            append('{')
            append("\"correlationId\":").append(quote(d.correlationId))
            append(",\"reason\":").append(quote(d.reason.name))
            d.origin?.let { append(",\"origin\":").append(quote(it)) }
            d.resource?.let { append(",\"resource\":").append(quote(it.name)) }
            d.statusCode?.let { append(",\"statusCode\":").append(it) }
            if (d.bytesRead > 0) append(",\"bytesRead\":").append(d.bytesRead)
            if (d.elapsedMs > 0) append(",\"elapsedMs\":").append(d.elapsedMs)

            d.limitGuard?.let { append(",\"limitGuard\":").append(quote(it.name)) }
            if (d.diagnosticDetails.isNotEmpty()) {
                append(",\"diagnosticDetails\":{")
                val sorted = d.diagnosticDetails.toSortedMap().entries.iterator()
                while (sorted.hasNext()) {
                    val (k, v) = sorted.next()
                    append(quote(k.name)).append(':').append(v)
                    if (sorted.hasNext()) append(',')
                }
                append('}')
            }
            append('}')
        }
        append("],")
        append("\"playback\":[")
        bundle.playback.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append('{')
            append("\"createdAtMs\":").append(p.createdAtMs)
            p.sessionId?.let { append(",\"sessionId\":").append(quote(it)) }
            p.engineName?.let { append(",\"engineName\":").append(quote(it)) }
            p.engineVersion?.let { append(",\"engineVersion\":").append(quote(it)) }
            p.state?.let { append(",\"state\":").append(quote(it)) }
            p.positionMs?.let { append(",\"positionMs\":").append(it) }
            p.droppedFrames?.let { append(",\"droppedFrames\":").append(it) }
            p.videoWidth?.let { append(",\"videoWidth\":").append(it) }
            p.videoHeight?.let { append(",\"videoHeight\":").append(it) }
            p.bufferingPercent?.let { append(",\"bufferingPercent\":").append(it) }
            p.reason?.let { append(",\"reason\":").append(quote(it)) }
            append('}')
        }
        append(']')
        append('}')
    }

    private fun quote(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"" + escaped + "\""
    }
}
