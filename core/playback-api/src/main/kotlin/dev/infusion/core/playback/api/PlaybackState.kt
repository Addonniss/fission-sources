package dev.infusion.core.playback.api

enum class PlaybackState {

    IDLE,

    PREPARING,

    BUFFERING,

    READY,

    PLAYING,

    PAUSED,

    ENDED,

    CANCELLED,

    FAILED,

    RELEASED,
}

enum class StateCause {
    PREPARE_REQUESTED,
    PREPARE_COMPLETED,
    PLAY_REQUESTED,
    PAUSE_REQUESTED,
    SEEK_REQUESTED,
    BUFFERING_STARTED,
    BUFFERING_ENDED,
    MEDIA_ENDED,
    STOP_REQUESTED,
    SESSION_RELEASED,
    FAILURE_OCCURRED,
    CANCELLED_BY_USER,
    CANCELLED_BY_SYSTEM,
    AUTO,
    UNKNOWN,
}

object PlaybackStateMachine {
    private val transitions: Map<PlaybackState, Set<PlaybackState>> = mapOf(
        PlaybackState.IDLE to setOf(PlaybackState.PREPARING, PlaybackState.CANCELLED, PlaybackState.RELEASED, PlaybackState.FAILED),
        PlaybackState.PREPARING to setOf(PlaybackState.BUFFERING, PlaybackState.READY, PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED),
        PlaybackState.BUFFERING to setOf(PlaybackState.READY, PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED),
        PlaybackState.READY to setOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING, PlaybackState.ENDED, PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED),
        PlaybackState.PLAYING to setOf(PlaybackState.PAUSED, PlaybackState.BUFFERING, PlaybackState.ENDED, PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED),
        PlaybackState.PAUSED to setOf(PlaybackState.PLAYING, PlaybackState.BUFFERING, PlaybackState.ENDED, PlaybackState.CANCELLED, PlaybackState.FAILED, PlaybackState.RELEASED),
        PlaybackState.ENDED to setOf(PlaybackState.PLAYING, PlaybackState.CANCELLED, PlaybackState.RELEASED),
        PlaybackState.CANCELLED to setOf(PlaybackState.RELEASED),
        PlaybackState.FAILED to setOf(PlaybackState.RELEASED),
        PlaybackState.RELEASED to emptySet(),
    )

    fun canTransition(from: PlaybackState, to: PlaybackState): Boolean =
        to in (transitions[from] ?: emptySet())
}
