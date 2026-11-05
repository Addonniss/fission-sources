package dev.infusion.source.stremio

import dev.infusion.core.model.SourceHealth
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.RequestCancellation
import dev.infusion.core.network.isProviderObservation
import dev.infusion.source.stremio.wire.StremioManifest

class StremioProviderState {

    @Volatile
    private var enabled = true

    @Volatile
    private var scope = RequestCancellation()

    @Volatile
    private var lastHealth: HealthObservation? = null

    @Volatile
    private var lastFailure: StremioFailure? = null

    val isEnabled: Boolean get() = enabled

    val lastHealthObservation: HealthObservation? get() = lastHealth

    val lastFailureObservation: StremioFailure? get() = lastFailure

    fun currentScope(): RequestCancellation = scope

    fun disable() {
        enabled = false
        scope.cancel()
    }

    fun enable() {
        enabled = true
        scope = RequestCancellation()
    }

    internal fun recordHealth(observation: HealthObservation) {
        lastHealth = observation
    }

    internal fun recordFailure(failure: StremioFailure) {
        lastFailure = failure
    }
}

data class HealthObservation(
    val healthy: Boolean,
    val reason: DiagnosticReason?,
    val status: Int?,
    val atEpochMs: Long,
    val manifestId: String?,
    val manifestVersion: String?,
) {
    val sourceHealth: SourceHealth
        get() = when {
            healthy -> SourceHealth.HEALTHY
            reason == DiagnosticReason.UNAUTHORIZED -> SourceHealth.UNAUTHORIZED
            reason == DiagnosticReason.TIMEOUT ||
                reason == DiagnosticReason.CONNECTION_FAILED ||
                reason == DiagnosticReason.DNS_FAILED ||
                reason == DiagnosticReason.REDIRECT_POLICY ||
                reason == DiagnosticReason.PRIVATE_DESTINATION -> SourceHealth.UNREACHABLE
            reason != null -> SourceHealth.DEGRADED
            else -> SourceHealth.UNKNOWN
        }
}

class StremioHealthMonitor(
    private val client: StremioClient,
    private val state: StremioProviderState,
    private val minProbeIntervalMs: Long = DEFAULT_MIN_PROBE_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed class ProbeResult {

        data class Completed(val result: StremioResult<StremioManifest>) : ProbeResult()

        data object Throttled : ProbeResult()

        data object Disabled : ProbeResult()
    }

    suspend fun probe(): ProbeResult {
        if (!state.isEnabled) return ProbeResult.Disabled
        val now = clock()
        val last = state.lastHealthObservation
        if (last != null && now - last.atEpochMs < minProbeIntervalMs) {
            return ProbeResult.Throttled
        }
        val result = client.fetchManifest(cancellation = state.currentScope())
        when (result) {
            is StremioResult.Ok -> {
                state.recordHealth(
                    HealthObservation(
                        healthy = true,
                        reason = null,
                        status = result.status,
                        atEpochMs = now,
                        manifestId = result.value.id,
                        manifestVersion = result.value.version,
                    ),
                )
            }
            is StremioResult.Err -> {

                if (!result.failure.reason.isProviderObservation) {
                    return ProbeResult.Completed(result)
                }
                state.recordHealth(
                    HealthObservation(
                        healthy = false,
                        reason = result.failure.reason,
                        status = result.failure.status,
                        atEpochMs = now,
                        manifestId = null,
                        manifestVersion = null,
                    ),
                )
                state.recordFailure(result.failure)
            }
        }
        return ProbeResult.Completed(result)
    }

    companion object {
        const val DEFAULT_MIN_PROBE_INTERVAL_MS = 5L * 60 * 1000
    }
}
