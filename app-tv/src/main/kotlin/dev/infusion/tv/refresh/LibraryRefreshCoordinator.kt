package dev.infusion.tv.refresh

import dev.infusion.core.domain.refresh.LibraryChangeHint
import dev.infusion.core.domain.refresh.LibraryFeedStatus
import dev.infusion.core.domain.refresh.LibraryRefreshReconciler
import dev.infusion.core.domain.refresh.ProvenImportTarget
import dev.infusion.core.domain.refresh.ServiceFeed
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LibraryRefreshCoordinator(
    scope: CoroutineScope,

    private val feedsProvider: () -> List<ServiceFeed>,
    private val reconcilers: List<LibraryRefreshReconciler>,
    private val clock: () -> Long = System::currentTimeMillis,

    private val coalesceMs: Long = COALESCE_MS,

    private val pollTickMs: Long = POLL_TICK_MS,

    private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
    private val retryWindowMs: Long = RETRY_WINDOW_MS,

    private val onDiagnostic: (String) -> Unit = {},
) {

    private val coordinatorJob = SupervisorJob(scope.coroutineContext[Job])
    private val coordScope = CoroutineScope(scope.coroutineContext + coordinatorJob + Dispatchers.Default)

    private val running = AtomicBoolean(false)

    private val inbox = Channel<LibraryChangeHint>(capacity = HINT_QUEUE_CAPACITY)

    private val retryWake = Channel<Unit>(capacity = Channel.CONFLATED)

    private val lock = Any()

    private val pendingChanged = HashMap<String, LinkedHashSet<String>>()
    private val pendingRemoved = HashMap<String, LinkedHashSet<String>>()
    private val pendingGeneric = HashSet<String>()
    private val pendingInstance = HashMap<String, String>()

    private var activeFeeds: List<ServiceFeed> = emptyList()
    private val feedStatus = HashMap<String, LibraryFeedStatus>()
    private val pollExponent = HashMap<String, Int>()
    private val lastCatchUpAtMs = HashMap<String, Long>()

    private var startedFingerprint: List<String> = emptyList()

    private var ingestJob: Job? = null
    private var pollJob: Job? = null
    private var retryJob: Job? = null

    private val retryWindows = LinkedHashMap<String, RetryWindow>()

    private val settledRetries = HashSet<String>()

    private class RetryWindow(
        val target: ProvenImportTarget,
        val probeServiceId: String,
        var nextDueAtMs: Long,
    )

    fun start() {
        val desired = feedsProvider()
        val fingerprint = desired.map { "${it.serviceId}|${it.serviceInstance}" }.sorted()
        synchronized(lock) {
            if (running.get() && fingerprint == startedFingerprint) return
        }
        restart(desired, fingerprint)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {

            teardown()
            return
        }
        teardown()
    }

    fun onConfigurationChanged() {
        if (!running.get()) return
        start()
    }

    fun requestServiceRefresh(serviceId: String) {
        if (!running.get()) return
        val instances: List<String>
        synchronized(lock) {
            instances = activeFeeds.filter { it.serviceId == serviceId }.map { it.serviceInstance }
        }
        if (instances.isEmpty()) return
        val atMs = clock()
        instances.forEach { instance ->
            onHint(
                LibraryChangeHint(
                    serviceId = serviceId,
                    serviceInstance = instance,
                    scopeOnly = true,
                    observedAtMs = atMs,
                ),
            )
        }
    }

    fun markImportProven(target: ProvenImportTarget, probeServiceId: String) {
        if (!running.get()) return
        val now = clock()

        if (now - target.provenAtMs >= retryWindowMs) return
        synchronized(lock) {
            if (activeFeeds.none { it.serviceId == probeServiceId }) return
            val key = target.key
            if (key in settledRetries) return
            if (retryWindows.containsKey(key)) return
            retryWindows[key] = RetryWindow(
                target = target,
                probeServiceId = probeServiceId,
                nextDueAtMs = now,
            )
        }

        retryWake.trySend(Unit)
    }

    fun clearImportRetries() {
        synchronized(lock) {
            retryWindows.clear()
            settledRetries.clear()
        }
    }

    private fun restart(desired: List<ServiceFeed>, fingerprint: List<String>) {
        teardown()
        running.set(true)
        synchronized(lock) {
            activeFeeds = desired
            startedFingerprint = fingerprint
            feedStatus.clear()
            pollExponent.clear()
            lastCatchUpAtMs.clear()
            desired.forEach { feedStatus[it.serviceId] = LibraryFeedStatus.CONNECTING }
        }
        desired.forEach { service ->
            service.feed.start()
        }
        ingestJob = coordScope.launch { ingestLoop() }
        pollJob = coordScope.launch { pollLoop() }
        retryJob = coordScope.launch { retryLoop() }
    }

    private fun teardown() {
        ingestJob?.cancel()
        ingestJob = null
        pollJob?.cancel()
        pollJob = null
        retryJob?.cancel()
        retryJob = null
        val feeds: List<ServiceFeed>
        synchronized(lock) {
            feeds = activeFeeds
            activeFeeds = emptyList()
            startedFingerprint = emptyList()
            pendingChanged.clear()
            pendingRemoved.clear()
            pendingGeneric.clear()
            pendingInstance.clear()
            feedStatus.clear()

            retryWindows.clear()
            settledRetries.clear()
        }

        feeds.forEach { runCatching { it.feed.close() } }
    }

    fun onFeedStatus(serviceId: String, instance: String, status: LibraryFeedStatus) {
        synchronized(lock) {
            if (activeFeeds.none { it.serviceId == serviceId && it.serviceInstance == instance }) return

            if (feedStatus[serviceId] == status) return
            feedStatus[serviceId] = status
            when (status) {
                LibraryFeedStatus.CONNECTED -> pollExponent[serviceId] = 0
                LibraryFeedStatus.DISCONNECTED, LibraryFeedStatus.UNSUPPORTED ->
                    pollExponent[serviceId] = min((pollExponent[serviceId] ?: 0) + 1, POLL_BACKOFF_MAX_EXPONENT)
                LibraryFeedStatus.CONNECTING -> Unit
            }
        }
    }

    fun onHint(hint: LibraryChangeHint) {
        if (!running.get()) return
        if (hint.isEmpty) return

        synchronized(lock) {
            if (activeFeeds.none { it.serviceId == hint.serviceId && it.serviceInstance == hint.serviceInstance }) {
                return
            }
        }

        if (inbox.trySend(hint).isFailure) {
            onHintOverflow(hint)
        }
    }

    private fun onHintOverflow(hint: LibraryChangeHint) {

        inbox.trySend(
            LibraryChangeHint(
                serviceId = hint.serviceId,
                serviceInstance = hint.serviceInstance,
                scopeOnly = true,
                observedAtMs = hint.observedAtMs,
            ),
        )
    }

    private suspend fun ingestLoop() {
        while (coordScope.isActive) {
            val first = inbox.receiveCatching().getOrNull() ?: return
            merge(first)

            while (true) {
                val next = withTimeoutOrNull(coalesceMs) { inbox.receiveCatching() }
                    ?: break
                if (next.isClosed) break
                merge(next.getOrNull() ?: break)
            }
            drain()
        }
    }

    private fun merge(hint: LibraryChangeHint) {
        synchronized(lock) {
            val changed = pendingChanged.getOrPut(hint.serviceId) { LinkedHashSet() }
            val removed = pendingRemoved.getOrPut(hint.serviceId) { LinkedHashSet() }
            var over = false
            hint.changedNativeIds.forEach { if (changed.size >= MAX_PENDING_IDS) over = true else changed.add(it) }
            hint.removedNativeIds.forEach { if (removed.size >= MAX_PENDING_IDS) over = true else removed.add(it) }
            if (over) {

                changed.clear()
                removed.clear()
                pendingGeneric.add(hint.serviceId)
            }
            if (hint.scopeOnly) pendingGeneric.add(hint.serviceId)
            pendingInstance[hint.serviceId] = hint.serviceInstance
        }
    }

    private suspend fun drain() {
        val snapshot: Map<String, PendingService>
        synchronized(lock) {
            snapshot = pendingInstance.mapValues { (serviceId, instance) ->
                PendingService(
                    instance = instance,
                    changed = pendingChanged[serviceId]?.toSet().orEmpty(),
                    removed = pendingRemoved[serviceId]?.toSet().orEmpty(),
                    generic = serviceId in pendingGeneric,
                )
            }
            pendingChanged.clear()
            pendingRemoved.clear()
            pendingGeneric.clear()
            pendingInstance.clear()
        }
        for ((serviceId, pending) in snapshot) {
            if (!coordScope.isActive) return
            val hint = LibraryChangeHint(
                serviceId = serviceId,
                serviceInstance = pending.instance,
                changedNativeIds = pending.changed,
                removedNativeIds = pending.removed,
                scopeOnly = pending.generic || (pending.changed.isEmpty() && pending.removed.isEmpty()),
                observedAtMs = clock(),
            )
            for (reconciler in reconcilers) {
                if (serviceId in reconciler.serviceIds()) {
                    val startedAt = clock()
                    onDiagnostic(
                        "reconcile service=$serviceId origin=push changed=${pending.changed.size}" +
                            " removed=${pending.removed.size} scope=${hint.scopeOnly}",
                    )
                    runCatching { reconciler.reconcile(hint) }
                    onDiagnostic("reconcile-done service=$serviceId origin=push ms=${clock() - startedAt}")
                }
            }
        }
    }

    private suspend fun pollLoop() {
        while (coordScope.isActive) {
            delay(pollTickMs)
            val now = clock()
            for (reconciler in reconcilers) {
                for (serviceId in reconciler.serviceIds()) {
                    val connected: Boolean
                    val exponent: Int
                    synchronized(lock) {
                        connected = feedStatus[serviceId] == LibraryFeedStatus.CONNECTED
                        exponent = pollExponent[serviceId] ?: 0
                    }

                    val retained = reconciler.catchUpWhileConnected(serviceId)
                    if (connected && !retained) continue
                    val effectiveExponent = if (retained) 0 else min(exponent, POLL_BACKOFF_MAX_EXPONENT)
                    val interval = reconciler.catchUpIntervalMs shl effectiveExponent
                    val due: Boolean
                    synchronized(lock) {
                        val last = lastCatchUpAtMs[serviceId] ?: Long.MIN_VALUE / 2
                        due = now - last >= interval
                        if (due) lastCatchUpAtMs[serviceId] = now
                    }
                    if (!due) continue
                    onDiagnostic("catchup service=$serviceId origin=fallback retained=$retained")
                    runCatching { reconciler.catchUp(serviceId) }
                }
            }
        }
    }

    private suspend fun retryLoop() {
        while (coordScope.isActive) {
            val now = clock()
            val ready = ArrayList<RetryWindow>()
            var nextDue = Long.MAX_VALUE
            synchronized(lock) {
                val entries = retryWindows.entries.iterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    val window = entry.value
                    if (now - window.target.provenAtMs >= retryWindowMs) {

                        settledRetries.add(entry.key)
                        entries.remove()
                        onDiagnostic("import-retry expired kind=${window.target.kind.name}")
                        continue
                    }
                    if (now >= window.nextDueAtMs) {

                        window.nextDueAtMs = now + retryIntervalMs
                        ready += window
                    } else {
                        nextDue = minOf(nextDue, window.nextDueAtMs)
                    }
                }
            }
            for (window in ready) {
                if (!coordScope.isActive) return
                if (probeImport(window)) {
                    synchronized(lock) {
                        retryWindows.remove(window.target.key)
                        settledRetries.add(window.target.key)
                    }
                    onDiagnostic("import-retry settled kind=${window.target.kind.name} proven=true")
                }
            }

            val waitMs = (nextDue - clock()).coerceIn(1L, retryIntervalMs)
            withTimeoutOrNull(waitMs) { retryWake.receive() }
        }
    }

    private suspend fun probeImport(window: RetryWindow): Boolean {
        val answering: List<LibraryRefreshReconciler>
        synchronized(lock) {

            if (activeFeeds.none { it.serviceId == window.probeServiceId }) return true
            answering = reconcilers.filter { window.probeServiceId in it.serviceIds() }
        }

        if (answering.isEmpty()) return true
        for (reconciler in answering) {
            onDiagnostic("import-retry probe kind=${window.target.kind.name} origin=post-import")
            val proven = runCatching { reconciler.reconcileProvenImport(window.target) }.getOrDefault(false)
            if (proven) return true
        }
        return false
    }

    private data class PendingService(
        val instance: String,
        val changed: Set<String>,
        val removed: Set<String>,
        val generic: Boolean,
    )

    companion object {

        const val COALESCE_MS = 1_500L

        const val POLL_TICK_MS = 15_000L

        const val MAX_PENDING_IDS = 500

        const val HINT_QUEUE_CAPACITY = 256

        const val POLL_BACKOFF_MAX_EXPONENT = 4

        const val RETRY_INTERVAL_MS = 5_000L

        const val RETRY_WINDOW_MS = 180_000L
    }
}
