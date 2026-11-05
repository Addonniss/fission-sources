package dev.infusion.source.jellyfin.sync

import dev.infusion.core.domain.sync.PlaybackSessionContext
import dev.infusion.core.domain.sync.PlaybackSessionContextStore
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinFailure
import dev.infusion.source.jellyfin.playback.JellyfinTransportCodec
import dev.infusion.source.jellyfin.session.JellyfinSessionReporter
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JellyfinWatchConnector(
    private val sourceId: SourceId,
    private val outbox: WatchOutbox,
    private val reporter: JellyfinSessionReporter,
    private val sessionProvider: () -> JellyfinSession?,
    private val scope: CoroutineScope,
    private val contextStore: PlaybackSessionContextStore,
    private val activeLifecycleOwned: Boolean = false,
    private val now: () -> Instant = Instant::now,

    private val postPlaybackSync: JellyfinPostPlaybackSync? = null,

    private val videoPlaybackActive: () -> Boolean = { false },
) {

    val connectorId: String = "jellyfin:${sourceId.value}"

    private var pingJob: Job? = null
    private var drainJob: Job? = null

    private val lifecycleOps = Channel<LifecycleOp>(Channel.UNLIMITED)
    private val lifecycleWorker: Job = scope.launch {
        for (op in lifecycleOps) {
            when (op) {
                is LifecycleOp.Bind -> {
                    try {
                        contextStore.put(op.context)
                    } finally {
                        op.done.complete(Unit)
                    }
                }
                is LifecycleOp.Unbind -> {
                    try {
                        doUnbindPlayback(op.watchSessionId)
                    } finally {
                        op.done.complete(Unit)
                    }
                }
            }
        }
    }

    private sealed interface LifecycleOp {
        data class Bind(
            val context: PlaybackSessionContext,
            val done: CompletableDeferred<Unit>,
        ) : LifecycleOp

        data class Unbind(
            val watchSessionId: String,
            val done: CompletableDeferred<Unit>,
        ) : LifecycleOp
    }

    fun onLocalEvent(event: WatchEvent.Local) {
        try {
            if (!isForThisSource(event)) {

                enqueuePostPlayback(event)
                return
            }

            if (activeLifecycleOwned && (event.kind == WatchEvent.Kind.START ||
                event.kind == WatchEvent.Kind.PROGRESS ||
                event.kind == WatchEvent.Kind.PAUSE ||
                event.kind == WatchEvent.Kind.CANCELLED ||
                event.kind == WatchEvent.Kind.COMPLETED
            )) return
            val key = idempotencyKey(event)
            outbox.enqueue(
                OutboxEntry(
                    entryId = OutboxEntryId.random(),
                    connectorId = connectorId,
                    idempotencyKey = key,
                    payload = OutboxPayload.WatchAction(
                        watchKey = event.watchKey,
                        kind = event.kind,
                        positionMs = event.positionMs,
                        durationMs = event.durationMs,
                        sessionId = event.sessionId,
                        occurredAt = event.occurredAt,
                    ),
                    createdAt = now(),
                ),
            )
        } catch (_: Exception) {

        }
    }

    private fun enqueuePostPlayback(event: WatchEvent.Local) {
        if (postPlaybackSync == null) return
        if (event.kind != WatchEvent.Kind.CANCELLED && event.kind != WatchEvent.Kind.COMPLETED) return
        val position = event.positionMs ?: return
        outbox.enqueue(
            OutboxEntry(
                entryId = OutboxEntryId.random(),
                connectorId = connectorId,
                idempotencyKey = "jellyfin:$sourceId:pos:${postPlaybackKey(event.watchKey)}:" +
                    event.occurredAt.toEpochMilli(),
                payload = OutboxPayload.PositionUpdate(
                    watchKey = event.watchKey,
                    positionMs = position.coerceAtLeast(0L),
                    durationMs = event.durationMs,
                    completed = event.kind == WatchEvent.Kind.COMPLETED,
                    occurredAt = event.occurredAt,
                ),
                createdAt = now(),
            ),
        )
    }

    private fun postPlaybackKey(key: WatchKey): String = buildString {
        append(key.titleId?.value?.toString() ?: "")
        if (key.isEpisode) append(":${key.seasonNumber}:${key.episodeNumber}")
    }

    private suspend fun drainPositionUpdate(entry: OutboxEntry, payload: OutboxPayload.PositionUpdate): Boolean {
        val sync = postPlaybackSync ?: return false

        if (videoPlaybackActive()) return false
        if (supersededByNewerPending(entry, payload)) {
            outbox.fail(entry.entryId, "superseded-by-newer-observation", retryable = false, now = now())
            return false
        }
        return when (
            val outcome = sync.apply(
                watchKey = payload.watchKey,
                positionMs = payload.positionMs,
                durationMs = payload.durationMs,
                completed = payload.completed,
                occurredAt = payload.occurredAt,
            )
        ) {
            is JellyfinPostPlaybackSync.Outcome.Delivered -> {
                outbox.acknowledge(entry.entryId)
                true
            }
            is JellyfinPostPlaybackSync.Outcome.Failed -> {
                outbox.fail(entry.entryId, "post-playback-sync-failed", retryable = outcome.retryable, now = now())
                false
            }
            is JellyfinPostPlaybackSync.Outcome.Skipped -> {

                outbox.fail(entry.entryId, "not-synced:${outcome.reason.name.lowercase()}", retryable = false, now = now())
                false
            }
        }
    }

    private fun supersededByNewerPending(entry: OutboxEntry, payload: OutboxPayload.PositionUpdate): Boolean =
        outbox.allEntries().any { other ->
            other.entryId != entry.entryId &&
                other.connectorId == connectorId &&
                other.state != OutboxState.ACKNOWLEDGED &&
                other.state != OutboxState.CANCELLED &&
                (other.payload as? OutboxPayload.PositionUpdate)
                    ?.let { it.watchKey == payload.watchKey && it.occurredAt.isAfter(payload.occurredAt) } == true
        }

    suspend fun syncPending(limit: Int = 20): Int {
        val session = sessionProvider() ?: return 0
        val entries = outbox.pending(connectorId, now()).take(limit)
        var acked = 0
        for (entry in entries) {

            val positionUpdate = entry.payload as? OutboxPayload.PositionUpdate
            if (positionUpdate != null) {
                if (drainPositionUpdate(entry, positionUpdate)) acked++
                continue
            }
            val action = entry.payload as? OutboxPayload.WatchAction ?: continue
            val payload = sessionRoute(action) ?: run {

                if (action.sessionId == null) {
                    outbox.fail(entry.entryId, "no play session id", retryable = false, now = now())
                } else {
                    outbox.fail(entry.entryId, "no play session context yet", retryable = true, now = now())
                }
                continue
            }
            val result = when (payload) {
                is RouteEvent.Playing -> reporter.playing(session, payload.itemId, payload.mediaSourceId, payload.playSessionId, payload.positionMs, payload.isPaused)
                is RouteEvent.Progress -> reporter.progress(session, payload.itemId, payload.mediaSourceId, payload.playSessionId, payload.positionMs, payload.durationMs, payload.isPaused)
                is RouteEvent.Stopped -> reporter.stopped(session, payload.itemId, payload.mediaSourceId, payload.playSessionId, payload.positionMs, payload.durationMs)
            }
            when (result) {
                is ApiResult.Ok -> {
                    outbox.acknowledge(entry.entryId)
                    acked++

                    val watchSessionId = action.sessionId?.value?.toString()
                    if (watchSessionId != null && !contextStillNeeded(watchSessionId)) {
                        contextStore.remove(sourceId.value.toString(), watchSessionId)
                    }
                }
                is ApiResult.Err -> {
                    val retryable = result.failure.retryable
                    outbox.fail(entry.entryId, result.failure.message, retryable = retryable)
                }
            }
        }
        return acked
    }

    fun startDraining(intervalMs: Long = DRAIN_INTERVAL_MS) {
        stopDraining()
        drainJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    syncPending()
                } catch (_: Exception) {

                }
            }
        }
    }

    fun stopDraining() {
        drainJob?.cancel()
        drainJob = null
    }

    fun startPinging(intervalMs: Long = PING_INTERVAL_MS) {
        stopPinging()
        pingJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                pingActiveContexts()
            }
        }
    }

    fun stopPinging() {
        pingJob?.cancel()
        pingJob = null
    }

    private suspend fun pingActiveContexts() {
        val session = sessionProvider() ?: return
        val contexts = contextStore.active(sourceId.value.toString())
        for (context in contexts) {
            reporter.ping(session, context.playSessionId)
        }
    }

    private fun isForThisSource(event: WatchEvent.Local): Boolean {
        val anchor = event.anchor ?: return false
        return anchor.nativeItemId.sourceId == sourceId
    }

    private fun idempotencyKey(event: WatchEvent.Local): String {
        val scope = when (event.kind) {
            WatchEvent.Kind.COMPLETED, WatchEvent.Kind.EXPLICIT_WATCHED, WatchEvent.Kind.EXPLICIT_RESET ->
                event.sessionId?.let { "session:$it:${event.kind}" } ?: "event:${event.eventId.value}"
            else -> "event:${event.eventId.value}"
        }
        return "jellyfin:$sourceId:$scope"
    }

    private fun sessionRoute(
        action: OutboxPayload.WatchAction,
    ): RouteEvent? {

        val watchSessionId = action.sessionId?.value?.toString() ?: return null
        val context = contextStore.get(sourceId.value.toString(), watchSessionId) ?: return null
        val mediaSourceId = context.mediaSourceId
        val playSessionId = context.playSessionId
        if (mediaSourceId.isBlank() || playSessionId.isBlank()) return null

        val nativeId = context.itemId
        val positionMs = action.positionMs ?: 0L
        return when (action.kind) {
            WatchEvent.Kind.START ->
                RouteEvent.Playing(nativeId, mediaSourceId, playSessionId, positionMs, isPaused = false)
            WatchEvent.Kind.PROGRESS ->
                RouteEvent.Progress(nativeId, mediaSourceId, playSessionId, positionMs, action.durationMs, isPaused = false)
            WatchEvent.Kind.PAUSE ->
                RouteEvent.Progress(nativeId, mediaSourceId, playSessionId, positionMs, action.durationMs, isPaused = true)
            WatchEvent.Kind.COMPLETED, WatchEvent.Kind.CANCELLED, WatchEvent.Kind.EXPLICIT_WATCHED ->
                RouteEvent.Stopped(nativeId, mediaSourceId, playSessionId, positionMs, action.durationMs)
            else -> null
        }
    }

    suspend fun bindPlayback(
        watchSessionId: dev.infusion.core.model.SessionId,
        itemId: String,
        mediaSourceId: String,
        playSessionId: String,
    ) {
        val done = CompletableDeferred<Unit>()
        lifecycleOps.send(
            LifecycleOp.Bind(
                context = PlaybackSessionContext(
                    sourceId = sourceId.value.toString(),
                    watchSessionId = watchSessionId.value.toString(),
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
                ),
                done = done,
            ),
        )
        done.await()
    }

    suspend fun unbindPlayback(watchSessionId: dev.infusion.core.model.SessionId) {
        val done = CompletableDeferred<Unit>()
        lifecycleOps.send(LifecycleOp.Unbind(watchSessionId.value.toString(), done))
        done.await()
    }

    private fun doUnbindPlayback(sid: String) {
        val key = sourceId.value.toString()
        val hasUnacked = outbox.allEntries().any { entry ->
            entry.connectorId == connectorId &&
                entry.state != OutboxState.ACKNOWLEDGED &&
                entry.state != OutboxState.CANCELLED &&
                (entry.payload as? OutboxPayload.WatchAction)?.sessionId?.value?.toString() == sid
        }
        if (!hasUnacked) contextStore.remove(key, sid)
    }

    private fun contextStillNeeded(sid: String): Boolean =
        outbox.allEntries().any { entry ->
            entry.connectorId == connectorId &&
                entry.state != OutboxState.ACKNOWLEDGED &&
                entry.state != OutboxState.CANCELLED &&
                (entry.payload as? OutboxPayload.WatchAction)?.sessionId?.value?.toString() == sid
        }

    private sealed interface RouteEvent {
        val itemId: String
        val mediaSourceId: String
        val playSessionId: String

        data class Playing(
            override val itemId: String,
            override val mediaSourceId: String,
            override val playSessionId: String,
            val positionMs: Long,
            val isPaused: Boolean,
        ) : RouteEvent

        data class Progress(
            override val itemId: String,
            override val mediaSourceId: String,
            override val playSessionId: String,
            val positionMs: Long,
            val durationMs: Long?,
            val isPaused: Boolean,
        ) : RouteEvent

        data class Stopped(
            override val itemId: String,
            override val mediaSourceId: String,
            override val playSessionId: String,
            val positionMs: Long,
            val durationMs: Long?,
        ) : RouteEvent
    }

    companion object {

        const val PING_INTERVAL_MS: Long = 60_000L

        const val DRAIN_INTERVAL_MS: Long = 30_000L
    }
}
