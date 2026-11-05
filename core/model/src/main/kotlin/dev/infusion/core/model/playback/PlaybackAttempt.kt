package dev.infusion.core.model.playback

import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.PlaybackAttemptId
import dev.infusion.core.model.SessionId
import java.time.Instant

enum class PlaybackStage {
    RESOLVE,
    PREPARE,
    START,
    PLAYBACK,
    SEEK,
    TRACK_SWITCH,
    SUBTITLE,
    TEARDOWN,
}

enum class PlaybackFailureKind {
    AUTH,
    CERTIFICATE,
    POLICY,
    DRM,
    FORMAT,
    DECODE,
    NETWORK,
    TIMEOUT,
    SOURCE_UNAVAILABLE,
    EXPIRED,
    NOT_FOUND,
    STORAGE,
    UNKNOWN,
}

enum class FailureRecoverability {
    TRANSIENT,
    PERMANENT,
    UNKNOWN,
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val recoverability: FailureRecoverability,
    val stage: PlaybackStage,
    val message: String? = null,
    val remedyHint: String? = null,
) {
    init {
        require(message == null || message.isNotBlank()) { "message must not be blank" }
    }
}

data class PlaybackAttempt(
    val attemptId: PlaybackAttemptId,
    val candidateId: CandidateId,
    val sessionId: SessionId,
    val engine: String,
    val startedAt: Instant,
    val completedStages: List<PlaybackStage> = emptyList(),
    val failure: PlaybackFailure? = null,
    val endedAt: Instant? = null,
    val redactedDiagnosticsRef: String? = null,
) {
    init {
        require(engine.isNotBlank()) { "engine must not be blank" }
    }
}
