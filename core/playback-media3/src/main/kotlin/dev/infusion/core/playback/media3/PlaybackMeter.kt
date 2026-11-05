package dev.infusion.core.playback.media3

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.source.LoadEventInfo

internal interface PlaybackMeter {

    fun onPrepareStarted()
    fun onPlayerCreated()
    fun onPlayerReleased()
    fun onLoadStarted(loadEventInfo: LoadEventInfo)
    fun onLoadCompleted(loadEventInfo: LoadEventInfo)
    fun onLoadError(loadEventInfo: LoadEventInfo)
    fun onLoadCanceled(loadEventInfo: LoadEventInfo)
    fun onBufferingChanged(isBuffering: Boolean, bufferedPositionMs: Long?)
    fun onPositionSample(nowMs: Long, positionMs: Long, bufferedPositionMs: Long?, isPlaying: Boolean)
    fun onFirstFrame(nowMs: Long)
    fun onDroppedFrames(total: Long)
    fun summarise(cause: String)

    object NONE : PlaybackMeter {
        override fun onPrepareStarted() = Unit
        override fun onPlayerCreated() = Unit
        override fun onPlayerReleased() = Unit
        override fun onLoadStarted(loadEventInfo: LoadEventInfo) = Unit
        override fun onLoadCompleted(loadEventInfo: LoadEventInfo) = Unit
        override fun onLoadError(loadEventInfo: LoadEventInfo) = Unit
        override fun onLoadCanceled(loadEventInfo: LoadEventInfo) = Unit
        override fun onBufferingChanged(isBuffering: Boolean, bufferedPositionMs: Long?) = Unit
        override fun onPositionSample(
            nowMs: Long,
            positionMs: Long,
            bufferedPositionMs: Long?,
            isPlaying: Boolean,
        ) = Unit
        override fun onFirstFrame(nowMs: Long) = Unit
        override fun onDroppedFrames(total: Long) = Unit
        override fun summarise(cause: String) = Unit
    }
}

internal const val PLAYBACK_METER_TAG = "InfusionPlaybackMeter"

internal const val ALLOCATOR_UNKNOWN: Int = -1

internal interface SampleAllocatorProbe {
    fun totalBytesAllocated(): Int
    fun unusedBytesAllocated(): Int
}

internal fun playbackMeterFor(
    context: Context,
    sessionTag: () -> String,
    sampleBufferTargetBytes: () -> Int,
    enabled: Boolean = Log.isLoggable(PLAYBACK_METER_TAG, Log.VERBOSE),
    allocatorProbe: SampleAllocatorProbe? = null,
    heapClassSupported: (() -> Boolean)? = null,
    digest: (String) -> String = ::sha256Hex16,
): PlaybackMeter =
    if (enabled) {
        LoggingPlaybackMeter(
            context = context,
            sessionTag = sessionTag(),
            sampleBufferTargetBytes = sampleBufferTargetBytes(),
            allocatorProbe = allocatorProbe,
            heapClassSupported = heapClassSupported,
            digest = digest,
        )
    } else {
        PlaybackMeter.NONE
    }

internal fun sha256Hex16(value: String): String {
    if (value.isEmpty()) return "none"
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return hash.take(8).joinToString("") { "%02x".format(it) }
}
