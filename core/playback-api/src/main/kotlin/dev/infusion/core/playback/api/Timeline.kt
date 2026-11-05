package dev.infusion.core.playback.api

data class PlaybackTimeline(

    val durationMs: Long?,
    val isSeekable: Boolean,
    val isLive: Boolean,

    val windowCount: Int = 1,
)

enum class BufferingReason {
    INITIAL_LOAD,
    SEEK,
    UNDERFLOW,
    REBUFFERING,
    UNKNOWN,
}

data class BufferingState(
    val isBuffering: Boolean,

    val bufferedPositionMs: Long?,
    val reason: BufferingReason = BufferingReason.UNKNOWN,
)

data class VideoSize(
    val width: Int,
    val height: Int,
    val pixelWidthHeightRatio: Float = 1f,
    val unappliedRotationDegrees: Int = 0,
)
