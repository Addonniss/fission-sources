package dev.infusion.core.playback.media3

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultAllocator

internal class PlaybackSampleAllocator(
    val allocator: DefaultAllocator = DefaultAllocator(
 true,
 C.DEFAULT_BUFFER_SEGMENT_SIZE,
    ),
) : SampleAllocatorProbe {

    override fun totalBytesAllocated(): Int =
        runCatching { allocator.getTotalBytesAllocated() }.getOrDefault(ALLOCATOR_UNKNOWN)

    override fun unusedBytesAllocated(): Int =
        runCatching { allocator.getUnusedBytesAllocated() }.getOrDefault(ALLOCATOR_UNKNOWN)

    val individualAllocationLength: Int =
        runCatching { allocator.getIndividualAllocationLength() }.getOrDefault(0)
}
