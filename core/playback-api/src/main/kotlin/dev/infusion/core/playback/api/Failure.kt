package dev.infusion.core.playback.api

data class PlaybackFailure(
    val category: FailureCategory,

    val code: String,
    val severity: FailureSeverity,
    val recoverability: Recoverability,

    val message: String,

    val engineDetail: String? = null,
    val timestampMs: Long,

    val stall: PlaybackStall? = null,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

enum class PlaybackStall {

    NO_PROGRESS,

    NOT_ENDING,

    SUPPRESSED,
}

enum class FailureCategory {

    SOURCE,

    NETWORK,

    FORMAT,

    DECODE,

    DRM,

    TRACK,

    RENDER,

    AUDIO_OUTPUT,

    RESOURCE,

    POLICY,

    INTERNAL,

    UNKNOWN,
}

enum class FailureSeverity {

    TRANSIENT,

    PERMANENT,

    UNKNOWN,
}

enum class Recoverability {

    RETRYABLE_TRANSIENT,

    RETRYABLE_REFRESH,

    NOT_RETRYABLE,

    UNKNOWN,
}
