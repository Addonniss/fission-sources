package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingInboundSkipReason
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.domain.tracking.TrackingWatchedAnswer
import dev.infusion.core.domain.tracking.TrackingWatchedCacheEntry
import dev.infusion.core.domain.tracking.TrackingWatchedCacheStore
import dev.infusion.core.domain.tracking.TrackingWatchedCacheVerdict
import dev.infusion.core.domain.tracking.TrackingWatchedKeys
import dev.infusion.core.domain.tracking.TrackingWatchedLookupPort
import dev.infusion.core.domain.tracking.TrackingWatchedLookupResult
import dev.infusion.core.domain.tracking.TrackingWatchedQuery
import dev.infusion.core.domain.tracking.TrackingWatchedVerdict
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TrackingWatchedReconciler(

    private val providerId: String,

    private val lookup: TrackingWatchedLookupPort,

    private val resolver: TrackingMediaResolverPort,

    private val cache: TrackingWatchedCacheStore,
    private val outbox: WatchOutbox,
    private val coordinator: WatchSyncCoordinator,

    private val scope: CoroutineScope,

    private val batchSize: Int = DEFAULT_BATCH_SIZE,

    private val maxBatchesPerPass: Int = DEFAULT_MAX_BATCHES,

    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,

    private val recentWindow: Int = DEFAULT_RECENT_WINDOW,

    private val enabled: () -> Boolean = { true },

    private val onPass: (TrackingWatchedLookupResult) -> Unit = {},

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    init {
        require(batchSize in 1..TrackingWatchedLookupPort.MAX_BATCH) {
            "batchSize must be in 1..${TrackingWatchedLookupPort.MAX_BATCH} (the provider's own per-call bound)"
        }
        require(maxBatchesPerPass >= 1) { "maxBatchesPerPass must be positive" }
        require(recentWindow >= batchSize) { "the recent window must be able to hold at least one batch" }
    }

    private val pending = LinkedHashSet<WatchKey>()

    private val recent = LinkedHashSet<WatchKey>()

    @Volatile
    private var activityGate: Instant? = null

    private val queueLock = Any()

    private val passMutex = Mutex()

    @Volatile
    private var flushJob: Job? = null

    @Volatile
    private var generation: Int = 0

    @Volatile
    var lastResult: TrackingWatchedLookupResult? = null
        private set

    fun demand(watchKey: WatchKey) {
        synchronized(queueLock) { addPending(watchKey) }
        scheduleFlush()
    }

    fun demandAll(watchKeys: Collection<WatchKey>) {
        if (watchKeys.isEmpty()) return
        synchronized(queueLock) { watchKeys.forEach(::addPending) }
        scheduleFlush()
    }

    private fun addPending(watchKey: WatchKey) {
        pending.remove(watchKey)
        pending.add(watchKey)
        while (pending.size > MAX_PENDING) pending.remove(pending.first())
    }

    fun noteWatchedActivity(gate: Instant?) {
        if (gate == null) return
        val previous = activityGate
        if (previous != null && !gate.isAfter(previous)) return
        activityGate = gate
        val rearmed = synchronized(queueLock) {
            val rearm = recent.toList()
            pending.addAll(rearm)
            rearm.size
        }
        if (rearmed > 0) scheduleFlush()
    }

    fun reset() {
        generation++
        synchronized(queueLock) {
            pending.clear()
            recent.clear()
        }
        activityGate = null
        flushJob?.cancel()
        flushJob = null
    }

    fun clear() {
        reset()
        runCatching { cache.clear(providerId) }
    }

    suspend fun reconcile(): TrackingWatchedLookupResult {
        if (!runCatching { enabled() }.getOrDefault(false)) {

            return TrackingWatchedLookupResult()
        }
        val demanded = synchronized(queueLock) {
            val taken = pending.toList()
            pending.clear()
            if (taken.isNotEmpty()) {
                recent.addAll(taken)
                while (recent.size > recentWindow) recent.remove(recent.first())
            }
            taken
        }
        if (demanded.isEmpty()) return TrackingWatchedLookupResult()
        val generationAtStart = generation
        val result = passMutex.withLock {
            withContext(dispatcher) { runPass(demanded, generationAtStart) }
        }
        lastResult = result
        runCatching { onPass(result) }

        val outstanding = synchronized(queueLock) { pending.isNotEmpty() }
        if (outstanding) scheduleFlush()
        return result
    }

    private suspend fun runPass(demanded: List<WatchKey>, generationAtStart: Int): TrackingWatchedLookupResult {
        val skipped = LinkedHashMap<TrackingInboundSkipReason, Int>()
        val cached = runCatching { cache.entries(providerId, demanded.map(TrackingWatchedKeys::of)) }
            .getOrDefault(emptyMap())

        val questions = LinkedHashMap<String, Question>()
        var cacheHits = 0
        var unresolved = 0
        val unresolvedEntries = ArrayList<TrackingWatchedCacheEntry>()
        for (key in demanded) {
            val cacheKey = TrackingWatchedKeys.of(key)
            val entry = cached[cacheKey]
            if (entry != null && !isStale(entry)) {
                cacheHits++
                continue
            }
            val reference = runCatching { resolver.resolve(key) }.getOrNull()
            if (reference == null) {

                unresolved++
                unresolvedEntries += TrackingWatchedCacheEntry(cacheKey, TrackingWatchedCacheVerdict.UNRESOLVED)
                continue
            }
            val query = TrackingWatchedQuery(
                externalIds = reference.externalIds,
                kind = reference.kind,
                seasonNumber = reference.seasonNumber,
                episodeNumber = reference.episodeNumber,
            )
            val id = queryId(query)
            val existing = questions[id]
            if (existing == null) {
                questions[id] = Question(query, mutableListOf(key))
            } else {
                existing.keys += key
            }
        }

        if (unresolvedEntries.isNotEmpty()) runCatching { cache.put(providerId, unresolvedEntries) }

        var imported = 0
        var notWatched = 0
        var queried = 0
        var requests = 0
        var failure: String? = null
        val queue = ArrayDeque(questions.values)
        var batches = 0
        while (queue.isNotEmpty() && batches < maxBatchesPerPass) {
            if (generation != generationAtStart) {

                synchronized(queueLock) { pending.addAll(queue.flatMap { it.keys }) }
                return TrackingWatchedLookupResult(
                    demanded = demanded.size,
                    cached = cacheHits,
                    unresolved = unresolved,
                    queried = queried,
                    requests = requests,
                    imported = imported,
                    notWatched = notWatched,
                    skipped = skipped,
                )
            }
            val batch = ArrayList<Question>(batchSize)
            while (batch.size < batchSize && queue.isNotEmpty()) batch += queue.removeFirst()
            batches++
            val answers = when (val outcome = lookup.readWatched(batch.map { it.query })) {
                is TrackingInboundOutcome.Ok -> outcome.value
                is TrackingInboundOutcome.Unauthorized -> {
                    failure = outcome.reason
                    null
                }
                is TrackingInboundOutcome.Unavailable -> {
                    failure = outcome.reason
                    null
                }
                is TrackingInboundOutcome.Rejected -> {
                    failure = outcome.reason
                    null
                }
            }
            if (answers == null) {

                synchronized(queueLock) {
                    pending.addAll(batch.flatMap { it.keys })
                    pending.addAll(queue.flatMap { it.keys })
                }
                break
            }
            if (generation != generationAtStart) {

                synchronized(queueLock) {
                    pending.addAll(batch.flatMap { it.keys })
                    pending.addAll(queue.flatMap { it.keys })
                }
                break
            }
            requests++
            queried += batch.size
            val entries = ArrayList<TrackingWatchedCacheEntry>(batch.size)
            for ((index, question) in batch.withIndex()) {

                val answer = answers.getOrNull(index)
                if (answer == null) {
                    count(skipped, TrackingInboundSkipReason.MALFORMED, 1)
                    continue
                }
                when (answer.verdict) {
                    TrackingWatchedVerdict.WATCHED -> {
                        when (applyWatched(question, answer, skipped)) {
                            WatchedApply.UNUSABLE -> continue
                            WatchedApply.APPLIED -> imported++

                            WatchedApply.ALREADY_RECORDED -> Unit
                        }

                        entries += question.keys.map {
                            TrackingWatchedCacheEntry(
                                key = TrackingWatchedKeys.of(it),
                                verdict = TrackingWatchedCacheVerdict.WATCHED,
                                watchedAt = answer.watchedAt,
                                resolvedUnderGate = activityGate,
                            )
                        }
                    }
                    TrackingWatchedVerdict.NOT_WATCHED, TrackingWatchedVerdict.NOT_FOUND -> {
                        notWatched++
                        entries += question.keys.map {
                            TrackingWatchedCacheEntry(
                                key = TrackingWatchedKeys.of(it),
                                verdict = TrackingWatchedCacheVerdict.NOT_WATCHED,
                                resolvedUnderGate = activityGate,
                            )
                        }
                    }
                    TrackingWatchedVerdict.MALFORMED -> count(skipped, TrackingInboundSkipReason.MALFORMED, 1)
                }
            }
            if (entries.isNotEmpty()) runCatching { cache.put(providerId, entries) }
        }
        if (queue.isNotEmpty()) {

            synchronized(queueLock) { pending.addAll(queue.flatMap { it.keys }) }
        }
        return TrackingWatchedLookupResult(
            demanded = demanded.size,
            cached = cacheHits,
            unresolved = unresolved,
            queried = queried,
            requests = requests,
            imported = imported,
            notWatched = notWatched,
            skipped = skipped,
            failure = failure,
        )
    }

    private enum class WatchedApply {

        UNUSABLE,

        APPLIED,

        ALREADY_RECORDED,
    }

    private fun applyWatched(
        question: Question,
        answer: TrackingWatchedAnswer,
        skipped: MutableMap<TrackingInboundSkipReason, Int>,
    ): WatchedApply {
        val watchedAt = answer.watchedAt
        if (watchedAt == null) {

            count(skipped, TrackingInboundSkipReason.NO_OBSERVATION_TIME, 1)
            return WatchedApply.UNUSABLE
        }
        var appended = false
        var alreadyRecorded = false
        for (key in question.keys) {
            if (InboundPrecedence.newerUnsentLocalWork(outbox, providerId, key, watchedAt)) {

                count(skipped, TrackingInboundSkipReason.UNSENT_LOCAL_WORK, 1)
                continue
            }

            if (coordinator.alreadyImportedCompletion(providerId, key, watchedAt)) {
                count(skipped, TrackingInboundSkipReason.ALREADY_RECORDED, 1)
                alreadyRecorded = true
                continue
            }
            coordinator.importObservation(
                connectorId = providerId,
                watchKey = key,
                kind = WatchEvent.Kind.COMPLETED,
                observedAt = watchedAt,

                positionMs = null,
                durationMs = null,
                cursor = null,
            )
            appended = true
        }
        return when {
            appended -> WatchedApply.APPLIED
            alreadyRecorded -> WatchedApply.ALREADY_RECORDED
            else -> WatchedApply.UNUSABLE
        }
    }

    private fun isStale(entry: TrackingWatchedCacheEntry): Boolean {
        when (entry.verdict) {
            TrackingWatchedCacheVerdict.WATCHED -> return false
            TrackingWatchedCacheVerdict.UNRESOLVED -> return true
            TrackingWatchedCacheVerdict.NOT_WATCHED -> {
                val gate = activityGate ?: return false
                val resolved = entry.resolvedUnderGate ?: return true
                return gate.isAfter(resolved)
            }
        }
    }

    private class Question(
        val query: TrackingWatchedQuery,
        val keys: MutableList<WatchKey> = mutableListOf(),
    )

    private fun queryId(query: TrackingWatchedQuery): String = buildString {
        append(query.kind)
        append('|')
        append(query.externalIds.tmdbId)
        append('|')
        append(query.externalIds.imdbId)
        append('|')
        append(query.externalIds.tvdbId)
        append('|')
        append(query.seasonNumber)
        append('x')
        append(query.episodeNumber)
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            if (coalesceWindowMs > 0L) delay(coalesceWindowMs)
            runCatching { reconcile() }
        }
    }

    private fun count(map: MutableMap<TrackingInboundSkipReason, Int>, reason: TrackingInboundSkipReason, by: Int) {
        if (by <= 0) return
        map[reason] = (map[reason] ?: 0) + by
    }

    companion object {

        const val DEFAULT_BATCH_SIZE: Int = 20

        const val DEFAULT_MAX_BATCHES: Int = 4

        const val DEFAULT_COALESCE_WINDOW_MS: Long = 350L

        const val DEFAULT_RECENT_WINDOW: Int = 64

        const val MAX_PENDING: Int = 256
    }
}
