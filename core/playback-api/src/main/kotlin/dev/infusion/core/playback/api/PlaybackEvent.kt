package dev.infusion.core.playback.api

enum class SessionPhase {
    CREATED,
    PREPARING,
    PREPARED,

    MEDIA_SESSION_CONNECTED,
    MEDIA_SESSION_DISCONNECTED,
    FOREGROUND,
    BACKGROUND,
    PICTURE_IN_PICTURE,
    RELEASED,
}

enum class EndReason {
    END_OF_MEDIA,

    STOP_REQUESTED,
    NEXT_ITEM,
}

enum class CancellationReason {

    USER,

    SYSTEM,

    SUPERSEDED,

    TIMEOUT,
}

sealed interface PlaybackEvent {
    val timestampMs: Long

    data class StateChanged(
        override val timestampMs: Long,
        val state: PlaybackState,
        val cause: StateCause,
    ) : PlaybackEvent

    data class PositionChanged(
        override val timestampMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long? = null,
    ) : PlaybackEvent

    data class BufferingChanged(
        override val timestampMs: Long,
        val buffering: BufferingState,
    ) : PlaybackEvent

    data class TimelineChanged(
        override val timestampMs: Long,
        val timeline: PlaybackTimeline,
    ) : PlaybackEvent

    data class TracksChanged(
        override val timestampMs: Long,
        val inventory: TrackInventory,
    ) : PlaybackEvent

    data class TrackSelectionChanged(
        override val timestampMs: Long,
        val selection: TrackSelection,
    ) : PlaybackEvent

    data class ChaptersObserved(
        override val timestampMs: Long,
        val chapters: List<ChapterMark>,
    ) : PlaybackEvent

    data class VideoSizeChanged(
        override val timestampMs: Long,
        val size: VideoSize,
    ) : PlaybackEvent

    data class MeasuredVideoEvidenceObserved(
        override val timestampMs: Long,
        val evidence: MeasuredVideoEvidence,
    ) : PlaybackEvent

    data class RenderedFirstFrame(
        override val timestampMs: Long,
    ) : PlaybackEvent

    data class DroppedFrames(
        override val timestampMs: Long,

        val droppedCount: Int,

        val totalCount: Int? = null,
    ) : PlaybackEvent

    data class DecoderObserved(
        override val timestampMs: Long,
        val decoder: DecoderObservation,
    ) : PlaybackEvent

    data class CapabilityObserved(
        override val timestampMs: Long,
        val observation: CapabilityObservation,
    ) : PlaybackEvent

    data class Failed(
        override val timestampMs: Long,
        val failure: PlaybackFailure,
    ) : PlaybackEvent

    data class Cancelled(
        override val timestampMs: Long,
        val reason: CancellationReason,
    ) : PlaybackEvent

    data class Ended(
        override val timestampMs: Long,
        val reason: EndReason,
    ) : PlaybackEvent

    data class SessionPhaseChanged(
        override val timestampMs: Long,
        val phase: SessionPhase,
    ) : PlaybackEvent
}

fun interface PlaybackEventListener {
    fun onPlaybackEvent(event: PlaybackEvent)
}
