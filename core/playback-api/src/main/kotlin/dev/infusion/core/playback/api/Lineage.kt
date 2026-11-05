package dev.infusion.core.playback.api

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

enum class AttemptReason {

    USER_INITIATED,

    AUTOPLAY,

    TRANSIENT_RETRY,

    FALLBACK,

    RESUMED,
}

data class SessionLineage(
    val sessionId: SessionId,
    val rootSessionId: SessionId,
    val parentSessionId: SessionId?,
    val attemptSequence: Int,
    val reason: AttemptReason,

    val requestId: String,
    val createdAtEpochMs: Long,
) {
    init {
        require(attemptSequence >= 1) { "attemptSequence must be >= 1" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        if (attemptSequence == 1) {
            require(sessionId == rootSessionId) { "first attempt must root itself" }
            require(parentSessionId == null) { "first attempt must not have a parent" }
        } else {
            require(parentSessionId != null) { "child attempt must have a parent" }
        }
    }

    fun nextAttempt(
        newSessionId: SessionId,
        nextReason: AttemptReason,
        newRequestId: String,
        nowEpochMs: Long,
    ): SessionLineage = SessionLineage(
        sessionId = newSessionId,
        rootSessionId = rootSessionId,
        parentSessionId = sessionId,
        attemptSequence = attemptSequence + 1,
        reason = nextReason,
        requestId = newRequestId,
        createdAtEpochMs = nowEpochMs,
    )

    companion object {

        fun first(
            sessionId: SessionId,
            reason: AttemptReason,
            requestId: String,
            nowEpochMs: Long,
        ): SessionLineage = SessionLineage(
            sessionId = sessionId,
            rootSessionId = sessionId,
            parentSessionId = null,
            attemptSequence = 1,
            reason = reason,
            requestId = requestId,
            createdAtEpochMs = nowEpochMs,
        )
    }
}
