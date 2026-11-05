package dev.infusion.core.artworkimage

import android.graphics.Bitmap
import android.os.Looper
import dev.infusion.core.model.ArtworkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

private const val DECODE_FORMAT_REVISION = "exif-orientation-1"

private const val ARTWORK_LANE_THREADS = 4

private val artworkLaneThreadIds = java.util.concurrent.atomic.AtomicInteger(0)

internal val ARTWORK_LANE: CoroutineDispatcher by lazy {
    java.util.concurrent.Executors
        .newFixedThreadPool(ARTWORK_LANE_THREADS) { runnable ->
            Thread(runnable, "infusion-artwork-${artworkLaneThreadIds.incrementAndGet()}")
                .apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
}

class ArtworkLoader(
    private val bytesSource: ArtworkBytesSource,

    private val sourceVariants: ArtworkSourceVariants = ArtworkSourceVariants.Unchanged,
    private val decoder: ArtworkDecoder = BitmapFactoryArtworkDecoder(),
    private val metrics: ArtworkDecodeMetrics = ArtworkDecodeMetrics.None,

    private val loadMetrics: ArtworkLoadMetrics = ArtworkLoadMetrics.None,
    private val decodedBudgetBytes: Long = DEFAULT_DECODED_BUDGET_BYTES,

    private val decodeDispatcher: CoroutineDispatcher = ARTWORK_LANE,

    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + ARTWORK_LANE + artworkFailureHandler(),
    ),
    private val isMainThread: () -> Boolean = { onMainThread() },
) {
    init {
        require(decodedBudgetBytes > 0) { "decodedBudgetBytes must be positive" }
    }

    private val cacheLock = Any()

    private val cache = LinkedHashMap<DecodeKey, Bitmap>(64, 0.75f, true)
    private var cacheBytes = 0L

    private var lastBackdrop: Bitmap? = null

    private var lastBackdropVariant: String = ""

    private var generation = 0L

    val decodedEntryCount: Int get() = synchronized(cacheLock) { cache.size }

    val decodedBytesUsed: Long get() = synchronized(cacheLock) { cacheBytes }

    fun purgeAll() {
        synchronized(cacheLock) {
            generation++
            cache.clear()
            cacheBytes = 0
            lastBackdrop = null
            lastBackdropVariant = ""

            settledMisses.clear()
        }
    }

    private val settledMisses = LinkedHashMap<SlotKey, Unit>(64, 0.75f, true)

    val settledMissCount: Int get() = synchronized(cacheLock) { settledMisses.size }

    fun settledAbsent(locator: String, policy: ArtworkPolicy): Boolean =
        synchronized(cacheLock) { settledMisses.containsKey(SlotKey(locator, policy)) }

    private fun rememberSettledMiss(slot: SlotKey) {
        synchronized(cacheLock) {
            settledMisses[slot] = Unit
            while (settledMisses.size > MaxSettledMisses) {
                val iterator = settledMisses.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun forgetSettledMiss(slot: SlotKey) {
        synchronized(cacheLock) { settledMisses.remove(slot) }
    }

    fun effective(request: ArtworkRequest): ArtworkRequest = sourceVariants.resolve(request)

    fun effectiveLocator(locator: String, policy: ArtworkPolicy): String =
        sourceVariants.resolve(ArtworkRequest(locator, policy, 0, 0)).locator

    suspend fun load(request: ArtworkRequest): Bitmap? {

        val effective = sourceVariants.resolve(request)
        val variantToken = sourceVariants.variantToken(request)
        val key = DecodeKey.forRequest(effective)

        val lookupStartedAtNanos = System.nanoTime()
        cacheGet(key)?.let {
            reportLoad(
                policy = request.policy,
                tier = ArtworkLoadTier.DECODED_TIER_HIT,
                byteFetchMs = 0,
                decodeMs = 0,
                startedAtNanos = lookupStartedAtNanos,
                payloadBytes = 0,
            )
            return it
        }
        val entry = acquire(key, effective, variantToken, SlotKey(request.locator, request.policy))
        return try {
            entry.deferred.await()
                ?: previousSuccess(request.policy, request.retainsPreviousSuccess, variantToken)
        } catch (c: CancellationException) {

            throw c
        } catch (t: Throwable) {
            null
        } finally {
            release(key, entry)
        }
    }

    fun peekDecoded(request: ArtworkRequest): Bitmap? = synchronized(cacheLock) {
        val key = DecodeKey.forRequest(sourceVariants.resolve(request))

        var found: Bitmap? = null
        for ((candidate, bitmap) in cache) {
            if (candidate == key) {
                found = bitmap
                break
            }
        }
        found
    }

    suspend fun loadEncoded(
        encoded: ByteArray,
        policy: ArtworkPolicy,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap? = try {
        withContext(decodeDispatcher) {
            val key = DecodeKey.forEncoded(encoded, policy, targetWidthPx, targetHeightPx)
            cacheGet(key)
                ?: decodeAndCache(
                    key = key,
                    encoded = encoded,
                    policy = policy,
                    targetWidthPx = targetWidthPx,
                    targetHeightPx = targetHeightPx,

                    retainsPreviousSuccess = true,

                    variantToken = "",
                    startedGeneration = generation,
                )
                ?: previousSuccess(policy, retainsPreviousSuccess = true, variantToken = "")
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    private val availabilityLock = Any()

    private var producing = 0

    private var missedInWindow = false

    private var answeredInWindow = false

    private val availabilityGeneration = MutableStateFlow(0L)

    val availability: StateFlow<Long> = availabilityGeneration.asStateFlow()

    private fun onProduceStarted() {
        synchronized(availabilityLock) { producing++ }
    }

    private fun onProduceFinished(produced: Bitmap?, cancelled: Boolean) {
        val advance = synchronized(availabilityLock) {
            producing--
            if (!cancelled) {
                if (produced != null) answeredInWindow = true else missedInWindow = true
            }

            if (producing == 0) {
                val fire = missedInWindow && answeredInWindow
                missedInWindow = false
                answeredInWindow = false
                fire
            } else {
                false
            }
        }
        if (advance) availabilityGeneration.value = availabilityGeneration.value + 1
    }

    private val inflightLock = Any()
    private val inflight = HashMap<DecodeKey, Inflight>()

    private class Inflight {
        var refs: Int = 0
        lateinit var deferred: Deferred<Bitmap?>
    }

    val inFlightCount: Int get() = synchronized(inflightLock) { inflight.size }

    private fun acquire(
        key: DecodeKey,
        request: ArtworkRequest,
        variantToken: String,
        slot: SlotKey,
    ): Inflight {
        val entry: Inflight
        val start: Deferred<Bitmap?>
        synchronized(inflightLock) {
            inflight[key]?.let {
                it.refs++
                return it
            }
            val fresh = Inflight()
            fresh.refs = 1
            fresh.deferred = scope.async(start = CoroutineStart.LAZY) {
                produce(key, request, variantToken, slot)
            }
            inflight[key] = fresh
            entry = fresh
            start = fresh.deferred
        }

        start.start()
        return entry
    }

    private fun release(key: DecodeKey, entry: Inflight) {
        synchronized(inflightLock) {
            entry.refs--
            if (entry.refs <= 0) {
                if (inflight[key] === entry) inflight.remove(key)
                if (!entry.deferred.isCompleted) entry.deferred.cancel()
            }
        }
    }

    private suspend fun produce(
        key: DecodeKey,
        request: ArtworkRequest,
        variantToken: String,
        slot: SlotKey,
    ): Bitmap? {

        onProduceStarted()

        val produceStartedAtNanos = System.nanoTime()
        var produced: Bitmap? = null
        var cancelled = false
        var fetchedBytes = false
        var byteFetchMs = 0L
        var decodeMs = 0L
        var payloadBytes = 0
        try {
            produced = cacheGet(key) ?: run {
                val startedGeneration = currentGeneration()
                val fetchStartedAtNanos = System.nanoTime()

                val encoded = withContext(decodeDispatcher) { bytesSource.bytes(request) }
                fetchedBytes = true
                byteFetchMs = elapsedMs(fetchStartedAtNanos)
                if (encoded == null || encoded.isEmpty()) {

                    null
                } else {
                    payloadBytes = encoded.size
                    val decodeStartedAtNanos = System.nanoTime()
                    val decoded = withContext(decodeDispatcher) {
                        decodeAndCache(
                            key = key,
                            encoded = encoded,
                            policy = request.policy,
                            targetWidthPx = request.targetWidthPx,
                            targetHeightPx = request.targetHeightPx,
                            retainsPreviousSuccess = request.retainsPreviousSuccess,
                            variantToken = variantToken,
                            startedGeneration = startedGeneration,
                        )
                    }
                    decodeMs = elapsedMs(decodeStartedAtNanos)
                    decoded
                }
            }
            return produced
        } catch (c: CancellationException) {

            cancelled = true
            throw c
        } catch (t: Throwable) {
            produced = null
            return null
        } finally {
            onProduceFinished(produced = produced, cancelled = cancelled)

            if (!cancelled) {
                if (produced == null) rememberSettledMiss(slot) else forgetSettledMiss(slot)
            }

            if (!cancelled) {
                reportLoad(
                    policy = request.policy,
                    tier = when {
                        produced == null -> ArtworkLoadTier.ABSENT
                        fetchedBytes -> ArtworkLoadTier.PRODUCED

                        else -> ArtworkLoadTier.DECODED_TIER_HIT
                    },
                    byteFetchMs = byteFetchMs,
                    decodeMs = decodeMs,
                    startedAtNanos = produceStartedAtNanos,
                    payloadBytes = payloadBytes,
                )
            }
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    private fun reportLoad(
        policy: ArtworkPolicy,
        tier: ArtworkLoadTier,
        byteFetchMs: Long,
        decodeMs: Long,
        startedAtNanos: Long,
        payloadBytes: Int,
    ) {
        loadMetrics.loadFinished(
            ArtworkLoadRecord(
                policy = policy,
                tier = tier,
                byteFetchMs = byteFetchMs,
                decodeMs = decodeMs,
                totalMs = elapsedMs(startedAtNanos),
                payloadBytes = payloadBytes,
            ),
        )
    }

    private fun decodeAndCache(
        key: DecodeKey,
        encoded: ByteArray,
        policy: ArtworkPolicy,
        targetWidthPx: Int,
        targetHeightPx: Int,
        retainsPreviousSuccess: Boolean,
        variantToken: String,
        startedGeneration: Long,
    ): Bitmap? {
        val source = decoder.sourceSize(encoded) ?: return null
        val sampleSize = ArtworkDownsampling.sampleSize(source, targetWidthPx, targetHeightPx)
        val startedAt = System.nanoTime()
        val bitmap = decoder.decode(encoded, sampleSize)
        metrics.decoded(
            ArtworkDecodeRecord(
                policy = policy,
                sourceWidth = source.width,
                sourceHeight = source.height,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
                sampleSize = sampleSize,
                durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                onMainThread = isMainThread(),
            ),
        )
        if (bitmap == null) return null
        synchronized(cacheLock) {

            if (startedGeneration != generation) return null
            cachePutLocked(key, bitmap)

            if (retainsPreviousSuccess && policy == ArtworkPolicy.BACKDROP) {
                lastBackdrop = bitmap
                lastBackdropVariant = variantToken
            }
        }
        return bitmap
    }

    private fun previousSuccess(
        policy: ArtworkPolicy,
        retainsPreviousSuccess: Boolean,
        variantToken: String,
    ): Bitmap? =
        if (retainsPreviousSuccess && policy == ArtworkPolicy.BACKDROP) {
            synchronized(cacheLock) {

                if (lastBackdropVariant == variantToken) lastBackdrop else null
            }
        } else {
            null
        }

    private fun cacheGet(key: DecodeKey): Bitmap? = synchronized(cacheLock) { cache[key] }

    private fun currentGeneration(): Long = synchronized(cacheLock) { generation }

    private fun cachePutLocked(key: DecodeKey, bitmap: Bitmap) {
        cache.remove(key)?.let { cacheBytes -= decodedBytes(it) }
        cache[key] = bitmap
        cacheBytes += decodedBytes(bitmap)
        while (cacheBytes > decodedBudgetBytes && cache.size > 1) {
            val iterator = cache.entries.iterator()
            val victim = iterator.next()
            iterator.remove()
            cacheBytes -= decodedBytes(victim.value)
        }
    }

    private fun decodedBytes(bitmap: Bitmap): Long = bitmap.width.toLong() * bitmap.height * 4

    private data class SlotKey(
        val locator: String,
        val policy: ArtworkPolicy,
    )

    private data class DecodeKey(
        val token: String,
        val policy: ArtworkPolicy,
        val widthPx: Int,
        val heightPx: Int,
    ) {
        companion object {
            fun forRequest(request: ArtworkRequest) = DecodeKey(
                token = "locator:$DECODE_FORMAT_REVISION:${request.locator}",
                policy = request.policy,
                widthPx = request.targetWidthPx,
                heightPx = request.targetHeightPx,
            )

            fun forEncoded(
                encoded: ByteArray,
                policy: ArtworkPolicy,
                widthPx: Int,
                heightPx: Int,
            ) = DecodeKey(
                token = "encoded:$DECODE_FORMAT_REVISION:${sha256Hex(encoded)}",
                policy = policy,
                widthPx = widthPx,
                heightPx = heightPx,
            )

            private fun sha256Hex(bytes: ByteArray): String {
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                val sb = StringBuilder(digest.size * 2)
                for (b in digest) sb.append("%02x".format(b.toInt() and 0xff))
                return sb.toString()
            }
        }
    }

    companion object {

        const val DEFAULT_DECODED_BUDGET_BYTES: Long = 32L * 1024 * 1024

        const val MaxSettledMisses: Int = 512

        private fun onMainThread(): Boolean =
            runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)

        private fun artworkFailureHandler(): CoroutineExceptionHandler =
            CoroutineExceptionHandler { _, _ -> }
    }
}
