package dev.infusion.source.jellyfin.sync

import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.source.jellyfin.catalog.PostPlaybackItemState
import dev.infusion.source.jellyfin.session.JellyfinForensicsLog
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JellyfinInboundWatchSync(
    private val connectorId: String = CONNECTOR_ID,

    private val scope: CoroutineScope,

    private val enabled: () -> Boolean = { true },

    private val readServerState: suspend (WatchKey) -> PostPlaybackItemState?,

    private val readLocalState: (WatchKey) -> WatchState?,

    private val watchedStatementInFlight: (WatchKey) -> Boolean = { false },

    private val coordinator: WatchSyncCoordinator,

    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,

    private val maxPerPass: Int = DEFAULT_MAX_PER_PASS,

    private val freshMs: Long = DEFAULT_FRESH_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,

    private val trace: (String) -> Unit = { line -> JellyfinForensicsLog.event("inbound-watch", line) },
) {

    private val pending = LinkedHashSet<WatchKey>()

    private val answered = HashMap<WatchKey, Long>()

    private val queueLock = Any()

    private val passMutex = Mutex()

    @Volatile
    private var flushJob: Job? = null

    fun demand(watchKey: WatchKey) {
        synchronized(queueLock) {
            pending.remove(watchKey)
            pending.add(watchKey)
        }
        scheduleFlush()
    }

    fun demandAll(watchKeys: Collection<WatchKey>) {
        if (watchKeys.isEmpty()) return
        synchronized(queueLock) {
            for (key in watchKeys) {
                pending.remove(key)
                pending.add(key)
            }
        }
        scheduleFlush()
    }

    fun clear() {
        synchronized(queueLock) {
            pending.clear()
            answered.clear()
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(dispatcher) {
            delay(coalesceWindowMs)
            pass()
        }
    }

    private suspend fun pass() {
        passMutex.withLock {
            if (!enabled()) {

                synchronized(queueLock) { pending.clear() }
                return
            }
            val batch = synchronized(queueLock) {
                val now = clock()
                val fresh = pending.filter { key ->
                    val previous = answered[key]
                    previous == null || now - previous >= freshMs
                }
                val take = fresh.take(maxPerPass.coerceAtLeast(1))
                pending.removeAll(take.toSet())
                for (key in take) answered[key] = now
                take
            }
            if (batch.isEmpty()) {

                maybeContinue()
                return
            }
            var imported = 0
            var progress = 0
            var withdrawn = 0
            var alreadyKnown = 0
            var unstated = 0
            var inFlight = 0
            var failed = 0
            for (key in batch) {
                val answer = runCatching { readServerState(key) }.getOrNull()
                if (answer == null) {
                    failed++
                    continue
                }
                val local = runCatching { readLocalState(key) }.getOrNull()
                val serverInstant = answer.lastPlayedAt

                val kind = when {
                    answer.played && serverInstant != null -> WatchEvent.Kind.COMPLETED
                    answer.played -> {

                        unstated++
                        continue
                    }

                    answer.positionMs > 0L && serverInstant != null -> WatchEvent.Kind.PROGRESS
                    answer.positionMs > 0L -> {
                        unstated++
                        continue
                    }

                    local?.isWatched == true -> WatchEvent.Kind.UNWATCHED

                    else -> {

                        unstated++
                        continue
                    }
                }
                val observedAt = serverInstant ?: Instant.ofEpochMilli(clock())
                if (kind == WatchEvent.Kind.UNWATCHED && watchedStatementInFlight(key)) {

                    inFlight++
                    continue
                }
                if (alreadyRecorded(local, kind, answer, observedAt)) {
                    alreadyKnown++
                    continue
                }
                val applied = runCatching {
                    coordinator.importObservation(
                        connectorId = connectorId,
                        watchKey = key,
                        kind = kind,
                        observedAt = observedAt,

                        positionMs = if (kind == WatchEvent.Kind.PROGRESS) answer.positionMs else null,

                        durationMs = answer.runtimeMs,
                        cursor = null,
                    )
                }.isSuccess
                if (!applied) {
                    failed++
                    continue
                }
                imported++
                if (kind == WatchEvent.Kind.PROGRESS) progress++
                if (kind == WatchEvent.Kind.UNWATCHED) withdrawn++
            }
            if (batch.isNotEmpty()) {
                trace(
                    "requested=${batch.size} imported=$imported progress=$progress withdrawn=$withdrawn " +
                        "known=$alreadyKnown unstated=$unstated inflight=$inFlight failed=$failed",
                )
            }
            maybeContinue()
        }
    }

    private fun alreadyRecorded(
        local: WatchState?,
        kind: WatchEvent.Kind,
        answer: PostPlaybackItemState,
        observedAt: Instant,
    ): Boolean {
        if (local == null) return false
        return when (kind) {
            WatchEvent.Kind.UNWATCHED -> !local.isWatched

            WatchEvent.Kind.COMPLETED -> {
                val watchedAt = local.watchedAt
                local.isWatched && watchedAt != null && !observedAt.isAfter(watchedAt)
            }

            else -> {
                val lastMeaningful = local.lastMeaningfulAt
                local.status == WatchStatus.IN_PROGRESS &&
                    local.positionMs == answer.positionMs &&
                    lastMeaningful != null &&
                    !observedAt.isAfter(lastMeaningful)
            }
        }
    }

    private fun maybeContinue() {
        val remaining = synchronized(queueLock) { pending.isNotEmpty() }
        if (remaining) {
            flushJob = null
            scheduleFlush()
        }
    }

    companion object {

        const val CONNECTOR_ID: String = "jellyfin"

        const val DEFAULT_COALESCE_WINDOW_MS: Long = 250L

        const val DEFAULT_MAX_PER_PASS: Int = 16

        const val DEFAULT_FRESH_MS: Long = 60_000L
    }
}
