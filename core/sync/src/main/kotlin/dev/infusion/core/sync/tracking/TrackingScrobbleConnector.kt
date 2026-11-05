package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.sync.IssuedRequestOutcome
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.ratings.PersonalRatingPort
import dev.infusion.core.domain.ratings.PersonalRatingRef
import dev.infusion.core.domain.ratings.PersonalRatingWriteResult
import dev.infusion.core.domain.tracking.TrackingDeliveryResult
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.domain.tracking.TrackingProviderPort
import dev.infusion.core.domain.tracking.TrackingScrobbleAction
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import dev.infusion.core.domain.tracking.WatchTrackingSelectionGate
import dev.infusion.core.domain.watchlist.MembershipWriteResult
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipPort
import dev.infusion.core.domain.watchlist.WatchlistMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.sync.WatchlistMembershipKind
import dev.infusion.core.model.sync.WatchlistRemoteType
import dev.infusion.core.model.sync.WatchlistWriteDirection
import dev.infusion.core.model.watch.WatchEvent
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TrackingScrobbleConnector(
    private val outbox: WatchOutbox,
    private val resolver: TrackingMediaResolverPort,
    private val provider: TrackingProviderPort,
    private val now: () -> Instant = Instant::now,

    private val selectionGate: WatchTrackingSelectionGate =
        WatchTrackingSelectionGate.AllowAllForOfflineConstructionSites,

    private val onTerminalDelivered: () -> Unit = {},

    private val membership: () -> WatchlistMembershipPort? = { null },

    private val activeAccountRef: () -> String? = { null },

    private val ratings: () -> PersonalRatingPort? = { null },
) {

    val connectorId: String = provider.providerId

    private var drainJob: Job? = null

    private var wakeSignal = CompletableDeferred<Unit>()

    private val drainLifecycle = Any()

    @Volatile
    private var draining = false

    @Volatile
    private var tickIntervalMs: Long = DRAIN_INTERVAL_MS

    private val sessions = object : LinkedHashMap<String, SessionState>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionState>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    suspend fun syncPending(limit: Int = DEFAULT_BATCH): Int {
        val entries = outbox.pending(connectorId, now()).take(limit)
        var retired = 0
        for (entry in entries) {

            when (val payload = entry.payload) {
                is OutboxPayload.WatchlistWrite -> {
                    if (!watchlistDeliverable(payload.accountRef)) continue
                    when (deliverWatchlistWrite(entry, payload)) {
                        EntryDecision.Retired -> retired++
                        is EntryDecision.Stop -> return retired
                    }
                    continue
                }
                is OutboxPayload.ResumeReset -> {
                    if (!watchlistDeliverable(payload.accountRef)) continue
                    when (deliverResumeReset(entry, payload)) {
                        EntryDecision.Retired -> retired++
                        is EntryDecision.Stop -> return retired
                    }
                    continue
                }
                is OutboxPayload.PersonalRating -> {
                    if (!ratingDeliverable(payload.accountRef)) continue
                    when (deliverPersonalRating(entry, payload)) {
                        EntryDecision.Retired -> retired++
                        is EntryDecision.Stop -> return retired
                    }
                    continue
                }
                is OutboxPayload.WatchAction -> Unit
                is OutboxPayload.PositionUpdate -> {

                    continue
                }
            }

            if (!selectionGate.isSelected(connectorId)) {
                return retired + outbox.disposeByUserChoice(connectorId)
            }
            val action = entry.payload as? OutboxPayload.WatchAction ?: run {

                outbox.fail(entry.entryId, UNSUPPORTED_PAYLOAD, retryable = false, now = now())
                retired++
                continue
            }
            val sessionKey = action.sessionId?.value?.toString()
            val media = resolver.resolve(action.watchKey)
            if (media == null) {

                outbox.fail(entry.entryId, UNRESOLVED_IDENTITY, retryable = false, now = now())
                sessionKey?.let { sessions.remove(it) }
                retired++
                continue
            }
            val progress = progressPercent(action.positionMs, action.durationMs)
            val mapped = mapObservation(action.kind, sessionKey, action.positionMs)
            if (mapped == null) {

                outbox.acknowledge(entry.entryId)
                retired++
                continue
            }

            val result = provider.deliver(
                TrackingScrobbleEvent(
                    action = mapped,
                    media = media,
                    progressPercent = progress,
                    occurredAt = action.occurredAt,
                ),
            )
            val decision = when (result) {
                is TrackingDeliveryResult.Delivered -> {

                    outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Delivered)
                    sessionKey?.let { recordDelivered(it, mapped, action.positionMs) }

                    if (mapped.isTerminal()) onTerminalDelivered()
                    EntryDecision.Retired
                }
                is TrackingDeliveryResult.Retryable -> {
                    outbox.fail(entry.entryId, result.reason, retryable = true, now = now())

                    EntryDecision.Stop(disposeIfTrackerDeparted())
                }
                is TrackingDeliveryResult.Unauthorized -> {
                    outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                    EntryDecision.Stop(disposeIfTrackerDeparted())
                }
                is TrackingDeliveryResult.Rejected -> {

                    outbox.recordIssuedOutcome(
                        entry.entryId,
                        IssuedRequestOutcome.Rejected(result.reason),
                    )
                    sessionKey?.let { sessions.remove(it) }
                    EntryDecision.Retired
                }
            }
            when (decision) {
                EntryDecision.Retired -> retired++
                is EntryDecision.Stop -> return retired + decision.disposed
            }
        }
        return retired
    }

    private sealed interface EntryDecision {
        data object Retired : EntryDecision

        data class Stop(val disposed: Int) : EntryDecision
    }

    private fun disposeIfTrackerDeparted(): Int =
        if (selectionGate.isSelected(connectorId)) 0 else outbox.disposeByUserChoice(connectorId)

    private fun watchlistDeliverable(payloadAccountRef: String?): Boolean {
        if (!selectionGate.isSelected(connectorId)) return false
        if (payloadAccountRef != null && activeAccountRef() != payloadAccountRef) return false
        return true
    }

    private fun ratingDeliverable(payloadAccountRef: String?): Boolean {
        if (!selectionGate.isSelected(connectorId)) return false
        if (payloadAccountRef == null) return false
        return activeAccountRef() == payloadAccountRef
    }

    private suspend fun deliverWatchlistWrite(
        entry: OutboxEntry,
        payload: OutboxPayload.WatchlistWrite,
    ): EntryDecision {
        val port = membership() ?: return unsupported(entry, WATCHLIST_UNSUPPORTED)
        val caps = port.capabilities()
        val supported = if (payload.direction == WatchlistWriteDirection.ADD) caps.add else caps.remove
        if (!supported) {
            return unsupported(
                entry,
                if (payload.direction == WatchlistWriteDirection.ADD) {
                    WATCHLIST_ADD_UNSUPPORTED
                } else {
                    WATCHLIST_REMOVE_UNSUPPORTED
                },
            )
        }
        val ids = TrackingExternalIds.fromTyped(payload.aliases)
        if (ids.isEmpty) return rejected(entry, NO_PROVEN_ALIAS)
        val ref = WatchlistMembershipRef(
            kind = payload.kind.toWatchlistKind(),
            externalIds = ids,
            remoteKind = payload.remoteType.toWatchlistRemoteKind(),
            remoteRef = payload.remoteRef,
            title = payload.title,
            year = payload.year,
            seasonNumber = payload.seasonNumber,
            episodeNumber = payload.episodeNumber,
        )
        val result = if (payload.direction == WatchlistWriteDirection.ADD) {
            port.add(ref)
        } else {
            port.remove(ref)
        }
        return membershipDecision(entry, result)
    }

    private suspend fun deliverResumeReset(
        entry: OutboxEntry,
        payload: OutboxPayload.ResumeReset,
    ): EntryDecision {
        val port = membership() ?: return unsupported(entry, RESUME_CLEAR_UNSUPPORTED)
        if (!port.capabilities().progressClear) return unsupported(entry, RESUME_CLEAR_UNSUPPORTED)
        val ids = TrackingExternalIds.fromTyped(payload.aliases)
        if (ids.isEmpty) return rejected(entry, NO_PROVEN_ALIAS)
        val episode = payload.watchKey.isEpisode
        val ref = WatchlistMembershipRef(
            kind = if (episode) WatchlistKind.SHOW else WatchlistKind.MOVIE,
            externalIds = ids,
            remoteKind = if (episode) WatchlistRemoteKind.EPISODE else WatchlistRemoteKind.MOVIE,
            remoteRef = null,
            title = payload.titleId,
            year = null,
            seasonNumber = payload.seasonNumber ?: payload.watchKey.seasonNumber,
            episodeNumber = payload.episodeNumber ?: payload.watchKey.episodeNumber,
        )
        return membershipDecision(entry, port.clearResumeProgress(ref))
    }

    private suspend fun deliverPersonalRating(
        entry: OutboxEntry,
        payload: OutboxPayload.PersonalRating,
    ): EntryDecision {
        val port = ratings() ?: return unsupported(entry, RATING_UNSUPPORTED)
        val caps = port.capabilities()
        if (!caps.supports(payload.kind)) return unsupported(entry, RATING_UNIT_UNSUPPORTED)
        if (payload.score !in caps.scores) return rejected(entry, RATING_SCORE_OUT_OF_RANGE)
        val ids = TrackingExternalIds.fromTyped(payload.aliases)
        if (ids.isEmpty) return rejected(entry, NO_PROVEN_ALIAS)
        val ref = PersonalRatingRef(
            kind = payload.kind,
            externalIds = ids,
            seasonNumber = payload.seasonNumber,
            episodeNumber = payload.episodeNumber,
            title = payload.title,
            year = payload.year,
        )
        return when (val result = port.write(ref, payload.score)) {
            PersonalRatingWriteResult.Delivered -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Delivered)
                EntryDecision.Retired
            }
            is PersonalRatingWriteResult.Retryable -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                EntryDecision.Stop(0)
            }
            is PersonalRatingWriteResult.Unauthorized -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                EntryDecision.Stop(0)
            }
            is PersonalRatingWriteResult.Rejected -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Rejected(result.reason))
                EntryDecision.Retired
            }
            is PersonalRatingWriteResult.Unsupported -> {
                outbox.fail(entry.entryId, result.reason, retryable = false, now = now())
                EntryDecision.Retired
            }
        }
    }

    private fun membershipDecision(entry: OutboxEntry, result: MembershipWriteResult): EntryDecision =
        when (result) {
            MembershipWriteResult.Delivered -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Delivered)
                EntryDecision.Retired
            }
            is MembershipWriteResult.Retryable -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                EntryDecision.Stop(0)
            }
            is MembershipWriteResult.Unauthorized -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                EntryDecision.Stop(0)
            }
            is MembershipWriteResult.Rejected -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Rejected(result.reason))
                EntryDecision.Retired
            }
            is MembershipWriteResult.Unsupported -> {
                outbox.fail(entry.entryId, result.reason, retryable = false, now = now())
                EntryDecision.Retired
            }
        }

    private fun unsupported(entry: OutboxEntry, reason: String): EntryDecision {
        outbox.fail(entry.entryId, reason, retryable = false, now = now())
        return EntryDecision.Retired
    }

    private fun rejected(entry: OutboxEntry, reason: String): EntryDecision {
        outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Rejected(reason))
        return EntryDecision.Retired
    }

    fun startDraining(scope: CoroutineScope, intervalMs: Long = DRAIN_INTERVAL_MS) {
        tickIntervalMs = intervalMs
        synchronized(drainLifecycle) {
            draining = true

            wakeSignal.complete(Unit)
            if (drainJob?.isActive == true) return
            drainJob = scope.launch {
                while (isActive) {

                    if (draining) {
                        withTimeoutOrNull(tickIntervalMs) { awaitWake() }
                    } else {

                        awaitWake()
                    }
                    if (!draining) continue
                    runCatching { syncPending() }
                }
            }
        }
    }

    private suspend fun awaitWake() {
        val signal = synchronized(drainLifecycle) {
            if (wakeSignal.isCompleted) wakeSignal = CompletableDeferred()
            wakeSignal
        }
        signal.join()
    }

    fun stopDraining() {
        synchronized(drainLifecycle) {
            draining = false
            wakeSignal.complete(Unit)
        }
    }

    private fun mapObservation(
        kind: WatchEvent.Kind,
        sessionKey: String?,
        positionMs: Long?,
    ): TrackingScrobbleAction? {
        val state = sessionKey?.let { sessions[it] }
        val last = state?.lastDelivered
        val lastPosition = state?.lastDeliveredPositionMs
        return when (kind) {

            WatchEvent.Kind.START -> if (last == TrackingScrobbleAction.START && lastPosition == positionMs) {
                null
            } else {
                TrackingScrobbleAction.START
            }
            WatchEvent.Kind.PAUSE -> if (last == TrackingScrobbleAction.PAUSE && lastPosition == positionMs) {
                null
            } else {
                TrackingScrobbleAction.PAUSE
            }
            WatchEvent.Kind.PROGRESS -> if (last == TrackingScrobbleAction.PAUSE && (positionMs ?: 0L) > (lastPosition ?: 0L)) {

                TrackingScrobbleAction.RESUME
            } else {

                null
            }
            WatchEvent.Kind.COMPLETED ->
                if (last == TrackingScrobbleAction.COMPLETE || last == TrackingScrobbleAction.STOP) {
                    null
                } else {
                    TrackingScrobbleAction.COMPLETE
                }
            WatchEvent.Kind.CANCELLED ->
                if (last == TrackingScrobbleAction.STOP || last == TrackingScrobbleAction.COMPLETE) {

                    null
                } else {
                    TrackingScrobbleAction.STOP
                }
            WatchEvent.Kind.SOURCE_FAILURE ->
                if (last == TrackingScrobbleAction.START ||
                    last == TrackingScrobbleAction.RESUME ||
                    last == TrackingScrobbleAction.PAUSE
                ) {

                    TrackingScrobbleAction.STOP
                } else {
                    null
                }

            WatchEvent.Kind.EXPLICIT_WATCHED ->
                if (provider.capabilities().historyWrite) TrackingScrobbleAction.MARK_WATCHED else null

            WatchEvent.Kind.EXPLICIT_RESET,
            WatchEvent.Kind.HANDOFF,

            WatchEvent.Kind.RESUME_RESET,
            -> null
        }
    }

    private fun recordDelivered(sessionKey: String, action: TrackingScrobbleAction, positionMs: Long?) {
        sessions[sessionKey] = SessionState(lastDelivered = action, lastDeliveredPositionMs = positionMs)
    }

    private fun TrackingScrobbleAction.isTerminal(): Boolean =
        this == TrackingScrobbleAction.STOP || this == TrackingScrobbleAction.COMPLETE

    private fun progressPercent(positionMs: Long?, durationMs: Long?): Double {
        if (positionMs == null || durationMs == null || durationMs <= 0L) return 0.0
        return (positionMs.toDouble() / durationMs.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

    private data class SessionState(
        val lastDelivered: TrackingScrobbleAction,
        val lastDeliveredPositionMs: Long?,
    )

    companion object {

        const val DEFAULT_BATCH: Int = 20

        const val DRAIN_INTERVAL_MS: Long = 5_000L

        const val MAX_TRACKED_SESSIONS: Int = 64

        internal const val UNRESOLVED_IDENTITY = "no proven tracking identity for this item"
        internal const val UNSUPPORTED_PAYLOAD = "unsupported outbox payload for the scrobble connector"

        internal const val WATCHLIST_UNSUPPORTED = "WATCHLIST_UNSUPPORTED"
        internal const val WATCHLIST_ADD_UNSUPPORTED = "WATCHLIST_ADD_UNSUPPORTED"
        internal const val WATCHLIST_REMOVE_UNSUPPORTED = "WATCHLIST_REMOVE_UNSUPPORTED"
        internal const val RESUME_CLEAR_UNSUPPORTED = "RESUME_CLEAR_UNSUPPORTED"
        internal const val NO_PROVEN_ALIAS = "NO_PROVEN_ALIAS"
        internal const val RATING_UNSUPPORTED = "RATING_UNSUPPORTED"
        internal const val RATING_UNIT_UNSUPPORTED = "RATING_UNIT_UNSUPPORTED"
        internal const val RATING_SCORE_OUT_OF_RANGE = "RATING_SCORE_OUT_OF_RANGE"
    }
}

private fun WatchlistMembershipKind.toWatchlistKind(): WatchlistKind = when (this) {
    WatchlistMembershipKind.MOVIE -> WatchlistKind.MOVIE
    WatchlistMembershipKind.SHOW -> WatchlistKind.SHOW
}

private fun WatchlistRemoteType.toWatchlistRemoteKind(): WatchlistRemoteKind = when (this) {
    WatchlistRemoteType.MOVIE -> WatchlistRemoteKind.MOVIE
    WatchlistRemoteType.SHOW -> WatchlistRemoteKind.SHOW
    WatchlistRemoteType.SEASON -> WatchlistRemoteKind.SEASON
    WatchlistRemoteType.EPISODE -> WatchlistRemoteKind.EPISODE
}
