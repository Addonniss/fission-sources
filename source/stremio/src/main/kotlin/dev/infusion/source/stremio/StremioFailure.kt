package dev.infusion.source.stremio

import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.RedactedDiagnostic

data class StremioFailure(
    val reason: DiagnosticReason,
    val status: Int?,
    val message: String,
    val retryable: Boolean,
    val diagnostic: RedactedDiagnostic,
) {
    override fun toString(): String = "StremioFailure(${reason.name}, status=${status ?: "-"}, retryable=$retryable): $message"
}

sealed class StremioResult<out T> {
    data class Ok<T>(
        val value: T,
        val status: Int,
    ) : StremioResult<T>()

    data class Err<T>(val failure: StremioFailure) : StremioResult<T>()

    val valueOrNull: T? get() = (this as? Ok<T>)?.value
    val failureOrNull: StremioFailure? get() = (this as? Err<T>)?.failure
}

internal val RETRYABLE_REASONS = setOf(
    DiagnosticReason.TIMEOUT,
    DiagnosticReason.CONNECTION_FAILED,
    DiagnosticReason.DNS_FAILED,
    DiagnosticReason.RATE_LIMITED,
)
