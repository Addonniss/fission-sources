package dev.infusion.core.playback.media3

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.source.LoadEventInfo
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

internal class LoggingPlaybackMeter(
    private val context: Context,

    private val sessionTag: String,

    private val sampleBufferTargetBytes: Int? = null,

    private val allocatorProbe: SampleAllocatorProbe? = null,

    private val heapClassSupported: (() -> Boolean)? = null,

    private val digest: (String) -> String = ::sha256Hex16,
) : PlaybackMeter {

    private var loadStarts = 0L
    private var loadCompletions = 0L
    private var loadFailures = 0L
    private var bytesLoaded = 0L
    private var largestLoadBytes = 0L
    private var rangeStarts = 0L
    private var fullStarts = 0L
    private var partialResponses = 0L
    private var contentLengthResponses = 0L
    private var loadDurationTotalMs = 0L
    private var highestRequestedPosition = 0L
    private var rebuffers = 0L
    private var rebufferTotalMs = 0L
    private var rebufferStartedAtMs = -1L
    private var samples = 0L
    private var droppedFramesTotal = 0L
    private var prepareAtMs = -1L
    private var firstFrameAtMs = -1L
    private var summarised = false
    private var lastIdentity: String = IDENTITY_UNKNOWN

    private var resolvedBudget: MeasuredSampleBufferBudget? = null

    private val effectiveTargetBytes: Int?
        get() = resolvedBudget?.targetBufferBytes ?: sampleBufferTargetBytes

    override fun onPrepareStarted() {
        prepareAtMs = elapsedMs()
        val memoryClassMb = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass
        }.getOrNull()
        log(
            "meter-start session=$sessionTag budgetBytes=${sampleBufferTargetBytes ?: "unset"} " +
                "budgetMb=${sampleBufferTargetBytes?.let { it / MIB } ?: "unset"} " +
                "heapClassMb=${memoryClassMb ?: "unknown"} javaMaxMb=${Runtime.getRuntime().maxMemory() / MIB} " +
                "heapClassSupported=${heapClassSupported?.invoke() ?: "unknown"}",
        )
    }

    override fun onPlayerCreated() {
        log("meter-player live=${LIVE_PLAYERS.incrementAndGet()} event=created session=$sessionTag")
    }

    override fun onPlayerReleased() {
        val live = LIVE_PLAYERS.decrementAndGet().coerceAtLeast(0)
        log("meter-player live=$live event=released session=$sessionTag")
        summarise("released")
    }

    override fun onLoadStarted(loadEventInfo: LoadEventInfo) {
        loadStarts++
        val dataSpec = loadEventInfo.dataSpec
        val requestedPosition = dataSpec.position
        val requestedLength = dataSpec.length
        if (requestedPosition > highestRequestedPosition) highestRequestedPosition = requestedPosition
        if (requestedPosition > 0L || requestedLength != C.LENGTH_UNSET.toLong()) {
            rangeStarts++
        } else {
            fullStarts++
        }
        lastIdentity = identity(loadEventInfo)
    }

    override fun onLoadCompleted(loadEventInfo: LoadEventInfo) {
        loadCompletions++
        val loaded = loadEventInfo.bytesLoaded
        bytesLoaded += loaded
        loadDurationTotalMs += loadEventInfo.loadDurationMs
        if (loaded > largestLoadBytes) largestLoadBytes = loaded
        val hasContentRange = loadEventInfo.responseHeaders.containsKey("content-range")
        val contentLength = loadEventInfo.responseHeaders["content-length"]?.firstOrNull()
        if (hasContentRange) partialResponses++
        if (contentLength != null) contentLengthResponses++
        val dataSpec = loadEventInfo.dataSpec
        log(
            "meter-load n=$loadStarts reqPos=${dataSpec.position} reqLen=${dataSpec.length} " +
                "bytes=$loaded durMs=${loadEventInfo.loadDurationMs} contentRange=$hasContentRange " +
                "contentLength=${contentLength ?: "none"} bytesPerSec=${bytesPerSecond(loaded, loadEventInfo.loadDurationMs)} " +
                "identity=$lastIdentity",
        )
    }

    override fun onLoadError(loadEventInfo: LoadEventInfo) {
        loadFailures++
        log(
            "meter-load-error n=$loadStarts reqPos=${loadEventInfo.dataSpec.position} " +
                "reqLen=${loadEventInfo.dataSpec.length} bytes=${loadEventInfo.bytesLoaded} " +
                "durMs=${loadEventInfo.loadDurationMs} identity=$lastIdentity",
        )
    }

    override fun onLoadCanceled(loadEventInfo: LoadEventInfo) {
        log(
            "meter-load-canceled n=$loadStarts reqPos=${loadEventInfo.dataSpec.position} " +
                "reqLen=${loadEventInfo.dataSpec.length} bytes=${loadEventInfo.bytesLoaded}",
        )
    }

    override fun onBufferingChanged(isBuffering: Boolean, bufferedPositionMs: Long?) {
        val now = elapsedMs()
        if (isBuffering) {
            if (rebufferStartedAtMs < 0L) rebufferStartedAtMs = now
            rebuffers++
            log("meter-rebuffer event=start count=$rebuffers wallMs=$now bufferedMs=${bufferedPositionMs ?: "unknown"}")
        } else {
            val startedAt = rebufferStartedAtMs
            if (startedAt >= 0L) {
                rebufferTotalMs += now - startedAt
                rebufferStartedAtMs = -1L
            }
            log(
                "meter-rebuffer event=end count=$rebuffers totalMs=$rebufferTotalMs " +
                    "wallMs=$now bufferedMs=${bufferedPositionMs ?: "unknown"}",
            )
        }
    }

    override fun onPositionSample(nowMs: Long, positionMs: Long, bufferedPositionMs: Long?, isPlaying: Boolean) {
        samples++
        if (samples % SAMPLE_EVERY_N_TICKS != 0L) return
        log(
            "meter-sample wallMs=$nowMs posMs=$positionMs bufMs=${bufferedPositionMs ?: "unknown"} " +
                "playing=$isPlaying loads=$loadStarts loadsDone=$loadCompletions loadsFailed=$loadFailures " +
                "bytes=$bytesLoaded ranges=$rangeStarts-full=$fullStarts partial=$partialResponses " +
                "highestReqPos=$highestRequestedPosition rebuffers=$rebuffers rebufferMs=$rebufferTotalMs " +
                "dropped=$droppedFramesTotal livePlayers=${LIVE_PLAYERS.get()} " +
                "allocatorInUseBytes=$allocatorInUseBytes allocatorPoolBytes=$allocatorPoolBytes " +
                "allocatorTargetBytes=${effectiveTargetBytes ?: "unset"} " +
                "$memoryFields",
        )
    }

    override fun onSampleBufferBudgetResolved(budget: MeasuredSampleBufferBudget) {
        resolvedBudget = budget
        log(
            "meter-budget session=$sessionTag measured=${budget.measured} feasible=${budget.feasible} " +
                "ceilingBytes=${budget.ceilingBytes} liveHeapBytes=${budget.liveHeapBytes} " +
                "sampleBufferInUseBytes=${budget.sampleBufferInUseBytes} " +
                "nonBufferLiveBytes=${budget.nonBufferLiveBytes} headroomBytes=${budget.headroomBytes} " +
                "normalTargetBytes=${budget.normalTargetBytes} measuredCapBytes=${budget.measuredCapBytes} " +
                "effectiveTargetBytes=${budget.targetBufferBytes} " +
                "effectiveTargetMb=${budget.targetBufferBytes / MIB} " +
                "normalTargetPercent=${budget.normalTargetPercent} reserveBytes=${budget.reserveBytes}",
        )
    }

    override fun onFirstFrame(nowMs: Long) {
        firstFrameAtMs = nowMs
        val startupMs = prepareAtMs.takeIf { it >= 0L }?.let { nowMs - it }
        log("meter-first-frame wallMs=$nowMs startupMs=${startupMs ?: "unknown"} $memoryFields")
    }

    override fun onDroppedFrames(total: Long) {
        droppedFramesTotal = total
    }

    override fun summarise(cause: String) {
        if (summarised) return
        summarised = true
        log(
            "meter-summary cause=$cause session=$sessionTag loads=$loadStarts loadsDone=$loadCompletions " +
                "loadsFailed=$loadFailures bytes=$bytesLoaded largestLoadBytes=$largestLoadBytes " +
                "ranges=$rangeStarts-full=$fullStarts partial=$partialResponses " +
                "loadMs=$loadDurationTotalMs bytesPerSec=${
                    bytesPerSecond(bytesLoaded, loadDurationTotalMs)
                } rebuffers=$rebuffers rebufferMs=$rebufferTotalMs dropped=$droppedFramesTotal " +
                "startupMs=${startupMs() ?: "unknown"} livePlayers=${LIVE_PLAYERS.get()} " +
                "allocatorInUseBytes=$allocatorInUseBytes allocatorPoolBytes=$allocatorPoolBytes " +
                "allocatorTargetBytes=${effectiveTargetBytes ?: "unset"} " +
                "$memoryFields $postGcFields",
        )
    }

    private fun startupMs(): Long? =
        firstFrameAtMs.takeIf { it >= 0L && prepareAtMs >= 0L }?.let { it - prepareAtMs }

    private val allocatorInUseBytes: String
        get() = allocatorProbe?.totalBytesAllocated()?.takeIf { it >= 0 }?.toString() ?: "unknown"

    private val allocatorPoolBytes: String
        get() = allocatorProbe?.unusedBytesAllocated()?.takeIf { it >= 0 }?.toString() ?: "unknown"

    private val memoryFields: String
        get() {
            val runtime = Runtime.getRuntime()
            val free = runtime.freeMemory()
            val capacity = runtime.totalMemory()
            val javaAllocatedMb = (capacity - free) / MIB
            val javaCapacityMb = capacity / MIB
            val javaFreeMb = free / MIB
            val javaMaxMb = runtime.maxMemory() / MIB
            val memoryInfo = runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                    ?.firstOrNull()
            }.getOrNull()
            val nativeHeapMb = runCatching { Debug.getNativeHeapAllocatedSize() / MIB }.getOrDefault(-1L)

            fun stat(name: String): Long? =
                runCatching { memoryInfo?.getMemoryStat(name)?.toLongOrNull() }.getOrNull()
            return "javaAllocatedMb=$javaAllocatedMb javaCapacityMb=$javaCapacityMb javaFreeMb=$javaFreeMb " +
                "javaMaxMb=$javaMaxMb " +
                "summaryPssTotalMb=${stat("summary.total-pss")?.div(KIB) ?: "unknown"} " +
                "pssJavaHeapMb=${stat("summary.java-heap")?.div(KIB) ?: "unknown"} " +
                "pssNativeHeapMb=${stat("summary.native-heap")?.div(KIB) ?: "unknown"} " +
                "pssCodeMb=${stat("summary.code")?.div(KIB) ?: "unknown"} " +
                "pssStackMb=${stat("summary.stack")?.div(KIB) ?: "unknown"} " +
                "pssGraphicsMb=${stat("summary.graphics")?.div(KIB) ?: "unknown"} " +
                "pssPrivateOtherMb=${stat("summary.private-other")?.div(KIB) ?: "unknown"} " +
                "pssSystemMb=${stat("summary.system")?.div(KIB) ?: "unknown"} " +
                "pssTotalLegacyPssMb=${memoryInfo?.totalPss?.div(KIB) ?: "unknown"} " +
                "pssLegacyDalvikMb=${memoryInfo?.dalvikPss?.div(KIB) ?: "unknown"} " +
                "pssLegacyNativeMb=${memoryInfo?.nativePss?.div(KIB) ?: "unknown"} " +
                "nativeHeapMb=$nativeHeapMb " +
                "gcCount=${runtimeStat("art.gc.gc-count")} gcTimeMs=${runtimeStat("art.gc.gc-time-ms")} " +
                "blockingGcCount=${runtimeStat("art.gc.blocking-gc-count")} " +
                "blockingGcTimeMs=${runtimeStat("art.gc.blocking-gc-time-ms")} " +
                "bytesAllocatedMb=${runtimeStatLong("art.gc.bytes-allocated")?.div(MIB) ?: "unknown"}"
        }

    private val postGcFields: String
        get() = runCatching {
            System.gc()
            val runtime = Runtime.getRuntime()
            val free = runtime.freeMemory()
            val capacity = runtime.totalMemory()
            "postGcJavaAllocatedMb=${(capacity - free) / MIB} postGcJavaCapacityMb=${capacity / MIB} " +
                "postGcJavaFreeMb=${free / MIB} postGcJavaMaxMb=${runtime.maxMemory() / MIB} " +
                "postGcGcCount=${runtimeStat("art.gc.gc-count")}"
        }.getOrDefault("postGcJavaAllocatedMb=unknown")

    private fun runtimeStat(name: String): String =
        runtimeStatLong(name)?.toString() ?: "unknown"

    private fun runtimeStatLong(name: String): Long? =
        runCatching { Debug.getRuntimeStat(name)?.toLongOrNull() }.getOrNull()

    private fun identity(loadEventInfo: LoadEventInfo): String {
        val uri = loadEventInfo.uri
        val contentLength = loadEventInfo.responseHeaders["content-length"]?.firstOrNull() ?: "none"
        return "url=${digest(uri.toString())} host=${digest(uri.host ?: "")} contentLength=$contentLength"
    }

    private fun bytesPerSecond(bytes: Long, durationMs: Long): Long =
        if (durationMs > 0L) bytes * 1000L / durationMs else 0L

    private fun log(message: String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, message)
    }

    private fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()

    internal companion object {
        const val TAG = PLAYBACK_METER_TAG
        const val IDENTITY_UNKNOWN = "url=unknown host=unknown contentLength=none"
        const val MIB = 1024L * 1024L
        const val KIB = 1024L

        const val SAMPLE_EVERY_N_TICKS = 5L

        val LIVE_PLAYERS = AtomicInteger(0)

        fun sessionTag(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
    }
}
