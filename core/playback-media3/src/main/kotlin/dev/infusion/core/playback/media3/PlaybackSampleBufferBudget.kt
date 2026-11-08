package dev.infusion.core.playback.media3

import android.app.ActivityManager
import android.content.Context

internal object PlaybackSampleBufferBudget {

    const val MEDIA3_NETWORK_VIDEO_BUFFER_BYTES: Int = 131_072_000

    const val MEDIA3_NETWORK_AUDIO_BUFFER_BYTES: Int = 13_107_200

    const val MIN_TARGET_BYTES: Int = MEDIA3_NETWORK_AUDIO_BUFFER_BYTES

    const val MAX_TARGET_BYTES: Int = MEDIA3_NETWORK_VIDEO_BUFFER_BYTES

    private const val BYTES_PER_MIB: Long = 1024L * 1024L

    const val MEASURED_NON_BUFFER_LIVE_PEAK_BYTES: Long = 60L * BYTES_PER_MIB

    const val SAFETY_MARGIN_NUMERATOR: Long = 5
    const val SAFETY_MARGIN_DENOMINATOR: Long = 4

    const val MIN_RESERVE_SHARE_DIVISOR: Long = 2

    const val MIN_SUPPORTED_HEAP_CLASS_BYTES: Long = 96L * BYTES_PER_MIB

    const val FUTURE_ALLOCATION_HEADROOM_BYTES: Long = 12L * BYTES_PER_MIB

    fun reserveBytes(maxMemoryBytes: Long): Long {
        if (maxMemoryBytes <= 0L) return 0L
        val workingSet = MEASURED_NON_BUFFER_LIVE_PEAK_BYTES * SAFETY_MARGIN_NUMERATOR /
            SAFETY_MARGIN_DENOMINATOR
        val share = maxMemoryBytes / MIN_RESERVE_SHARE_DIVISOR
        val availableForApp = (maxMemoryBytes - MIN_TARGET_BYTES).coerceAtLeast(0L)
        return maxOf(workingSet, share).coerceAtMost(availableForApp)
    }

    fun targetBufferBytes(maxMemoryBytes: Long): Int {
        if (maxMemoryBytes <= 0L) return MIN_TARGET_BYTES
        val available = maxMemoryBytes - reserveBytes(maxMemoryBytes)
        return available.coerceIn(MIN_TARGET_BYTES.toLong(), MAX_TARGET_BYTES.toLong()).toInt()
    }

    fun measuredWorkingSetWithMarginBytes(): Long =
        MEASURED_NON_BUFFER_LIVE_PEAK_BYTES * SAFETY_MARGIN_NUMERATOR / SAFETY_MARGIN_DENOMINATOR

    fun supportsMeasuredWorkingSet(maxMemoryBytes: Long): Boolean =
        maxMemoryBytes >= MIN_SUPPORTED_HEAP_CLASS_BYTES &&
            reserveBytes(maxMemoryBytes) >= measuredWorkingSetWithMarginBytes() &&
            targetBufferBytes(maxMemoryBytes).toLong() <= maxMemoryBytes

    fun targetBufferBytesFor(context: Context): Int = targetBufferBytes(maxMemoryBytes(context))

    fun supportsMeasuredWorkingSet(context: Context): Boolean =
        supportsMeasuredWorkingSet(maxMemoryBytes(context))

    fun measuredTargetBufferBytes(
        ceilingBytes: Long,
        liveHeapBytes: Long,
        sampleBufferInUseBytes: Long,
        headroomBytes: Long = FUTURE_ALLOCATION_HEADROOM_BYTES,
    ): MeasuredSampleBufferBudget {
        val normalTarget = targetBufferBytes(ceilingBytes)
        val safeHeadroom = headroomBytes.coerceAtLeast(0L)
        if (ceilingBytes <= 0L || liveHeapBytes <= 0L) {

            return MeasuredSampleBufferBudget(
                ceilingBytes = ceilingBytes,
                liveHeapBytes = liveHeapBytes,
                sampleBufferInUseBytes = 0L,
                nonBufferLiveBytes = 0L,
                headroomBytes = safeHeadroom,
                normalTargetBytes = normalTarget,
                measuredCapBytes = normalTarget.toLong(),
                targetBufferBytes = normalTarget,
                feasible = false,
            )
        }
        val inUse = sampleBufferInUseBytes.coerceIn(0L, liveHeapBytes)
        val nonBufferLive = liveHeapBytes - inUse

        val measuredCap = when {
            nonBufferLive >= ceilingBytes -> -1L
            ceilingBytes - nonBufferLive <= safeHeadroom -> -1L
            else -> ceilingBytes - nonBufferLive - safeHeadroom
        }
        val target = if (measuredCap < MIN_TARGET_BYTES.toLong()) {
            MIN_TARGET_BYTES
        } else {
            measuredCap.coerceAtMost(normalTarget.toLong()).toInt()
        }
        return MeasuredSampleBufferBudget(
            ceilingBytes = ceilingBytes,
            liveHeapBytes = liveHeapBytes,
            sampleBufferInUseBytes = inUse,
            nonBufferLiveBytes = nonBufferLive,
            headroomBytes = safeHeadroom,
            normalTargetBytes = normalTarget,
            measuredCapBytes = measuredCap,
            targetBufferBytes = target,
            feasible = measuredCap >= MIN_TARGET_BYTES.toLong(),
        )
    }

    fun liveJavaHeapBytes(): Long = runCatching {
        val runtime = Runtime.getRuntime()
        runtime.totalMemory() - runtime.freeMemory()
    }.getOrDefault(0L)

    fun measuredTargetBufferBytesFor(
        context: Context,
        liveHeapBytes: Long,
        sampleBufferInUseBytes: Long,
    ): MeasuredSampleBufferBudget = measuredTargetBufferBytes(
        ceilingBytes = maxMemoryBytes(context),
        liveHeapBytes = liveHeapBytes,
        sampleBufferInUseBytes = sampleBufferInUseBytes,
    )

    fun maxMemoryBytes(context: Context): Long = maxMemoryBytes()

    fun maxMemoryBytes(): Long =
        runCatching { Runtime.getRuntime().maxMemory() }.getOrDefault(0L)

    fun memoryClassMb(context: Context): Int = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
    }.getOrDefault(0)
}

internal data class MeasuredSampleBufferBudget(

    val ceilingBytes: Long,

    val liveHeapBytes: Long,

    val sampleBufferInUseBytes: Long,

    val nonBufferLiveBytes: Long,

    val headroomBytes: Long,

    val normalTargetBytes: Int,

    val measuredCapBytes: Long,

    val targetBufferBytes: Int,

    val feasible: Boolean,
) {

    val measured: Boolean get() = ceilingBytes > 0L && liveHeapBytes > 0L

    val reserveBytes: Long get() = (ceilingBytes - targetBufferBytes.toLong()).coerceAtLeast(0L)

    val normalTargetPercent: Int
        get() = if (normalTargetBytes <= 0) 0
        else ((targetBufferBytes.toLong() * 100L) / normalTargetBytes).toInt()
}
