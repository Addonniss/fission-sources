package dev.infusion.source.aggregation.select

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.playback.PlaybackFailure
import dev.infusion.core.model.playback.FailureRecoverability

enum class SelectionPolicy {

    MANUAL,

    AUTOPLAY,

    AUTOPLAY_WITH_FALLBACK,
}

data class FallbackBudget(
    val maxAttempts: Int = 3,
    val maxSameCandidateRetries: Int = 2,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(maxSameCandidateRetries >= 0) { "maxSameCandidateRetries must be >= 0" }
    }
}

sealed interface FallbackDecision {

    data class RetrySame(
        val candidate: PlayableCandidate,
        val message: String,
    ) : FallbackDecision

    data class TryNext(
        val candidate: PlayableCandidate,
        val message: String,
    ) : FallbackDecision

    data class Stop(
        val message: String,
    ) : FallbackDecision
}

object FallbackPolicy {

    fun decide(
        fallbackEnabled: Boolean,
        meaningfulPlaybackStarted: Boolean,
        cancelled: Boolean,
        failure: PlaybackFailure,
        attemptsUsed: Int,
        sameCandidateRetries: Int,
        budget: FallbackBudget,
        current: PlayableCandidate,
        nextEligible: PlayableCandidate?,
    ): FallbackDecision {
        if (cancelled) {
            return FallbackDecision.Stop("Cancelled — no fallback")
        }
        if (meaningfulPlaybackStarted) {
            return FallbackDecision.Stop(
                "Playback already started — no silent provider/edition switch",
            )
        }
        val retryable = failure.recoverability == FailureRecoverability.TRANSIENT
        if (!retryable) {
            return FallbackDecision.Stop("Not a transient failure: ${failure.kind.name.lowercase()}")
        }
        if (!fallbackEnabled) {
            return FallbackDecision.Stop("Automatic fallback is off")
        }

        if (sameCandidateRetries < budget.maxSameCandidateRetries) {
            return FallbackDecision.RetrySame(
                candidate = current,
                message = "Transient failure (${failure.kind.name.lowercase()}); retrying ${current.originalLabel}",
            )
        }

        if (attemptsUsed >= budget.maxAttempts) {
            return FallbackDecision.Stop("Fallback attempt budget exhausted")
        }
        val next = nextEligible ?: return FallbackDecision.Stop("No compatible fallback option")
        return FallbackDecision.TryNext(
            candidate = next,
            message = "Source failed (${failure.kind.name.lowercase()}); trying ${next.providerName}: ${next.originalLabel}",
        )
    }
}
