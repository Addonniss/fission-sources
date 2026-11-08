package dev.infusion.core.playback.media3

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

@UnstableApi
internal class MeasuredSampleBufferLoadControl(
    allocator: DefaultAllocator = DefaultAllocator(
 true,
 C.DEFAULT_BUFFER_SEGMENT_SIZE,
    ),
    private val ceilingBytes: Long = PlaybackSampleBufferBudget.maxMemoryBytes(),
    private val liveHeapBytes: () -> Long = PlaybackSampleBufferBudget::liveJavaHeapBytes,

    private val sampleBufferInUseBytes: () -> Long = { 0L },

    private val onBudgetResolved: (MeasuredSampleBufferBudget) -> Unit = {},
) : DefaultLoadControl(
    allocator,
    DEFAULT_MIN_BUFFER_MS,
    DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    DEFAULT_MAX_BUFFER_MS,
    DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
 C.LENGTH_UNSET,
 false,
 false,
    DEFAULT_BACK_BUFFER_DURATION_MS,
    DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
    mapOf(PlayerId.PRELOAD.name to DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD),
) {

    @Volatile
    private var resolved: MeasuredSampleBufferBudget? = null

    internal fun budget(): MeasuredSampleBufferBudget {
        resolved?.let { return it }
        return synchronized(this) {
            resolved ?: PlaybackSampleBufferBudget.measuredTargetBufferBytes(
                ceilingBytes = ceilingBytes,
                liveHeapBytes = liveHeapBytes(),
                sampleBufferInUseBytes = sampleBufferInUseBytes(),
            ).also {
                resolved = it
                onBudgetResolved(it)
            }
        }
    }

    override fun calculateTargetBufferBytes(trackSelections: Array<ExoTrackSelection>): Int =
        budget().targetBufferBytes
}
