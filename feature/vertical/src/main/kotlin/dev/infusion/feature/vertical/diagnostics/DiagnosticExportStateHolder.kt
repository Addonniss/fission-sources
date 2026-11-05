package dev.infusion.feature.vertical.diagnostics

import dev.infusion.core.network.DiagnosticAppDescriptor
import dev.infusion.core.network.DiagnosticBundle
import dev.infusion.core.network.DiagnosticCounts
import dev.infusion.core.network.DiagnosticExporter
import dev.infusion.core.network.PlaybackDiagnosticSummary
import dev.infusion.core.network.RedactedDiagnostic
import dev.infusion.core.playback.api.DiagnosticsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExportedDiagnosticBundle(
    val bundleId: String,
    val json: String,
    val expiresAtMs: Long,
    val createdAtMs: Long,
)

class DiagnosticExportStateHolder(
    private val app: DiagnosticAppDescriptor,
    private val counts: () -> DiagnosticCounts,
    private val sourceDiagnostics: () -> List<RedactedDiagnostic>,
    private val playerDiagnostics: () -> List<DiagnosticsSnapshot>,
    private val nowMs: () -> Long,
) {

    private val _state = MutableStateFlow<ExportedDiagnosticBundle?>(null)
    val state: StateFlow<ExportedDiagnosticBundle?> = _state.asStateFlow()

    fun preview(): String = DiagnosticExporter.toPreview(buildBundle())

    fun currentExportIsValid(now: Long = nowMs()): Boolean =
        _state.value?.let { it.expiresAtMs >= now } ?: false

    fun requestExport(now: Long = nowMs()): ExportedDiagnosticBundle? {
        val bundle = buildBundle()
        if (bundle.isExpired(now)) return null
        val result = ExportedDiagnosticBundle(
            bundleId = bundle.bundleId,
            json = DiagnosticExporter.toJson(bundle),
            expiresAtMs = bundle.expiresAtMs,
            createdAtMs = bundle.createdAtMs,
        )
        _state.value = result
        return result
    }

    private fun buildBundle(): DiagnosticBundle = DiagnosticExporter.build(
        app = app,
        counts = counts(),
        sources = sourceDiagnostics(),
        playback = playerDiagnostics().map { it.toRedactedSummary() },
        nowMs = nowMs(),
    )
}

internal fun DiagnosticsSnapshot.toRedactedSummary(): PlaybackDiagnosticSummary = PlaybackDiagnosticSummary(
    sessionId = sessionId.value.toString(),
    engineName = engineId.name,
    engineVersion = engineId.version,
    state = state.name,
    positionMs = positionMs,
    droppedFrames = droppedFrames,
    videoWidth = videoSize?.width,
    videoHeight = videoSize?.height,
    reason = lastFailure?.category?.name,
    createdAtMs = createdAtMs,
)
