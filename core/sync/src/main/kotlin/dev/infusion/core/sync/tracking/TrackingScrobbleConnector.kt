package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.sync.IssuedRequestOutcome
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.ratings.PersonalRatingPort
import dev.infusion.core.domain.ratings.PersonalRatingRef
import dev.infusion.core.domain.ratings.PersonalRatingWriteResult
import dev.infusion.core.domain.tracking.TrackingAccountIdentity
import dev.infusion.core.domain.tracking.TrackingDeliveryResult
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaReference
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.domain.tracking.TrackingProviderPort
import dev.infusion.core.domain.tracking.TrackingScrobbleAction
import dev.infusion.core.domain.tracking.TrackingScrobbleEvent
import dev.infusion.core.domain.tracking.TrackingWatchedWriteBatchPort
import dev.infusion.core.domain.tracking.WatchTrackingSelectionGate
import dev.infusion.core.domain.watchlist.MembershipWriteResult
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipPort
import dev.infusion.core.domain.watchlist.WatchlistMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.sync.WatchlistMembershipKind
import dev.infusion.core.model.sync.WatchlistRemoteType
import dev.infusion.core.model.sync.WatchlistWriteDirection
import dev.infusion.core.model.watch.WatchEvent
import java.time.Instant
import kotlinx.coroutines.CancellationException
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

    private val activeAccountIdentity: () -> TrackingAccountIdentity = { TrackingAccountIdentity.Unresolved },

    private val ratings: () -> PersonalRatingPort? = { null },

    private val trace: ((String) -> Unit)? = null,
) {

    val connectorId: String = provider.providerId

    private var drainJob: Job? = null

    private var wakeSignal = CompletableDeferred<Unit>()

    private val drainLifecycle = Any()

    @Volatile
    private var draining = false

    private var durableWorkSubscription: AutoCloseable? = null

    private val sessions = object : LinkedHashMap<String, SessionState>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionState>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    suspend fun syncPending(limit: Int = DEFAULT_BATCH): Int = drainPass(limit).retired

    private suspend fun drainPass(limit: Int = DEFAULT_BATCH): PassOutcome {
        val entries = outbox.pending(connectorId, now()).take(limit)

        val batchFull = entries.size >= limit

        val depth = if (trace == null || entries.isEmpty()) 0 else outbox.retryableCount(connectorId)
        var retired = 0

        val consumed = HashSet<OutboxEntryId>()
        for ((index, entry) in entries.withIndex()) {
            if (entry.entryId in consumed) continue

            when (val payload = entry.payload) {
                is OutboxPayload.WatchlistWrite -> {

                    if (supersededByNewerAcknowledgedWrite(entry, payload)) {
                        outbox.fail(entry.entryId, SUPERSEDED_BY_NEWER_ACKNOWLEDGED, retryable = false, now = now())
                        traceDecision(
                            depth,
                            entry,
                            OP_WATCHLIST_WRITE,
                            OUTCOME_SUPERSEDED,
                            reason = REASON_SUPERSEDED_BY_NEWER,
                            action = payload.direction.name,
                        )
                        retired++
                        continue
                    }
                    val suspension = watchlistSuspensionReason(payload.accountRef)
                    if (suspension != null) {
                        traceDecision(
                            depth,
                            entry,
                            OP_WATCHLIST_WRITE,
                            OUTCOME_DEFERRED,
                            reason = suspension,
                            action = payload.direction.name,
                        )
                        continue
                    }
                    val decision = deliverWatchlistWrite(entry, payload, depth)
                    when (decision) {
                        EntryDecision.Retired -> retired++
                        is EntryDecision.Stop -> return PassOutcome.Deferred(retired + decision.disposed)
                    }
                    continue
                }
                is OutboxPayload.ResumeReset -> {

                    if (supersededByNewerAcknowledgedWrite(entry, payload)) {
                        outbox.fail(entry.entryId, SUPERSEDED_BY_NEWER_ACKNOWLEDGED, retryable = false, now = now())
                        traceDecision(
                            depth,
                            entry,
                            OP_RESUME_RESET,
                            OUTCOME_SUPERSEDED,
                            reason = REASON_SUPERSEDED_BY_NEWER,
                            action = ACTION_CLEAR,
                        )
                        retired++
                        continue
                    }
                    val suspension = watchlistSuspensionReason(payload.accountRef)
                    if (suspension != null) {
                        traceDecision(
                            depth,
                            entry,
                            OP_RESUME_RESET,
                            OUTCOME_DEFERRED,
                            reason = suspension,
                            action = ACTION_CLEAR,
                        )
                        continue
                    }
                    val decision = deliverResumeReset(entry, payload, depth)
                    when (decision) {
                        EntryDecision.Retired -> retired++
                        is EntryDecision.Stop -> return PassOutcome.Deferred(retired + decision.disposed)
                    }
                    continue
                }
                is OutboxPayload.PersonalRating -> {
                    if (!ratingDeliverable(payload.accountRef)) {
                        traceDecision(
                            depth,
                            entry,
                            OP_PERSONAL_RATING,
                            OUTCOME_DEFERRED,
                            reason = DEFERRED_ACCOUNT_UNBOUND,
                            action = payload.kind.name,
                        )
                        continue
                    }
                    val decision = deliverPersonalRating(entry, payload, depth)
                    when (decision) {
                        EntryDecision.Retired -> retired++
                        is EntryDecision.Stop -> return PassOutcome.Deferred(retired + decision.disposed)
                    }
                    continue
                }
                is OutboxPayload.WatchAction -> Unit
                is OutboxPayload.PositionUpdate -> {

                    traceDecision(
                        depth,
                        entry,
                        OP_POSITION_UPDATE,
                        OUTCOME_DEFERRED,
                        reason = DEFERRED_NOT_A_TRACKER_WRITE,
                    )
                    continue
                }
            }

            if (!selectionGate.isSelected(connectorId)) {
                val disposed = outbox.disposeByUserChoice(connectorId)
                traceDecision(
                    depth,
                    entry,
                    OP_WATCH_ACTION,
                    OUTCOME_SUPERSEDED,
                    reason = REASON_NOT_SENT_BY_USER_CHOICE,
                    action = (entry.payload as? OutboxPayload.WatchAction)?.kind?.name,
                )

                return PassOutcome.Settled(retired + disposed, batchFull = false)
            }
            val action = entry.payload as? OutboxPayload.WatchAction ?: run {

                outbox.fail(entry.entryId, UNSUPPORTED_PAYLOAD, retryable = false, now = now())
                traceDecision(
                    depth,
                    entry,
                    OP_UNKNOWN,
                    OUTCOME_PERMANENT,
                    reason = UNSUPPORTED_PAYLOAD,
                )
                retired++
                continue
            }
            val sessionKey = action.sessionId?.value?.toString()
            val media = resolver.resolve(action.watchKey)
            if (media == null) {

                outbox.fail(entry.entryId, UNRESOLVED_IDENTITY, retryable = false, now = now())
                sessionKey?.let { sessions.remove(it) }
                traceDecision(
                    depth,
                    entry,
                    OP_WATCH_ACTION,
                    OUTCOME_PERMANENT,
                    reason = UNRESOLVED_IDENTITY,
                    action = action.kind.name,
                )
                retired++
                continue
            }
            val progress = progressPercent(action.positionMs, action.durationMs)
            val mapped = mapObservation(action.kind, sessionKey, action.positionMs)
            if (mapped == null) {

                outbox.acknowledge(entry.entryId)
                traceDecision(
                    depth,
                    entry,
                    OP_WATCH_ACTION,
                    OUTCOME_SUPERSEDED,
                    reason = REASON_ABSORBED,
                    action = action.kind.name,
                )
                retired++
                continue
            }

            if (mapped == TrackingScrobbleAction.MARK_WATCHED) {
                when (val batch = deliverWatchedBatch(entries, index, consumed, depth)) {
                    null -> Unit
                    is BatchDecision.Retired -> {
                        retired += batch.retired
                        continue
                    }
                    is BatchDecision.Stop -> return PassOutcome.Deferred(retired + batch.retired + batch.disposed)
                }
            }

            val result = try {
                provider.deliver(
                    TrackingScrobbleEvent(
                        action = mapped,
                        media = media,
                        progressPercent = progress,
                        occurredAt = action.occurredAt,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {

                TrackingDeliveryResult.Retryable(DRAIN_DELIVERY_THREW)
            }
            val decision = when (result) {
                is TrackingDeliveryResult.Delivered -> {

                    outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Delivered)
                    sessionKey?.let { recordDelivered(it, mapped, action.positionMs) }

                    if (mapped.isTerminal()) onTerminalDelivered()
                    traceDecision(
                        depth,
                        entry,
                        OP_WATCH_ACTION,
                        OUTCOME_DELIVERED,
                        action = mapped.name,
                    )
                    EntryDecision.Retired
                }
                is TrackingDeliveryResult.Retryable -> {

                    outbox.fail(
                        entry.entryId,
                        result.reason,
                        retryable = true,
                        now = now(),
                        retryAfterMs = result.retryAfterMs?.takeIf { it > 0L },
                    )

                    val disposed = disposeIfTrackerDeparted()
                    traceDecision(
                        depth,
                        entry,
                        OP_WATCH_ACTION,
                        if (disposed > 0) OUTCOME_SUPERSEDED else OUTCOME_RETRYABLE,
                        reason = if (disposed > 0) REASON_NOT_SENT_BY_USER_CHOICE else result.reason,
                        action = mapped.name,
                        deferMs = result.retryAfterMs,
                    )
                    EntryDecision.Stop(disposed)
                }
                is TrackingDeliveryResult.Unauthorized -> {
                    outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                    val disposed = disposeIfTrackerDeparted()
                    traceDecision(
                        depth,
                        entry,
                        OP_WATCH_ACTION,
                        if (disposed > 0) OUTCOME_SUPERSEDED else OUTCOME_RETRYABLE,
                        reason = if (disposed > 0) REASON_NOT_SENT_BY_USER_CHOICE else result.reason,
                        action = mapped.name,
                    )
                    EntryDecision.Stop(disposed)
                }
                is TrackingDeliveryResult.Rejected -> {

                    outbox.recordIssuedOutcome(
                        entry.entryId,
                        IssuedRequestOutcome.Rejected(result.reason),
                    )
                    sessionKey?.let { sessions.remove(it) }
                    traceDecision(
                        depth,
                        entry,
                        OP_WATCH_ACTION,
                        OUTCOME_PERMANENT,
                        reason = result.reason,
                        action = mapped.name,
                    )
                    EntryDecision.Retired
                }
            }
            when (decision) {
                EntryDecision.Retired -> retired++
                is EntryDecision.Stop -> return PassOutcome.Deferred(retired + decision.disposed)
            }
        }
        return PassOutcome.Settled(retired, batchFull)
    }

    private sealed interface PassOutcome {

        val retired: Int

        data class Settled(override val retired: Int, val batchFull: Boolean) : PassOutcome

        data class Deferred(override val retired: Int) : PassOutcome
    }

    private sealed interface BatchDecision {

        data class Retired(val retired: Int) : BatchDecision

        data class Stop(val retired: Int, val disposed: Int) : BatchDecision
    }

    private class BatchMember(
        val entry: OutboxEntry,
        val sessionKey: String?,
        val positionMs: Long?,
        val event: TrackingScrobbleEvent,
        val identity: String,
    )

    private suspend fun deliverWatchedBatch(
        entries: List<OutboxEntry>,
        startIndex: Int,
        consumed: MutableSet<OutboxEntryId>,
        depth: Int,
    ): BatchDecision? {
        val port = provider as? TrackingWatchedWriteBatchPort ?: return null
        val capabilities = provider.capabilities()
        if (!capabilities.historyWrite) return null

        val members = ArrayList<BatchMember>()
        val identities = HashSet<String>()
        var scan = startIndex
        while (scan < entries.size && members.size < port.maxWatchedWriteBatch) {
            val candidate = entries[scan]
            if (candidate.entryId in consumed) break
            val action = candidate.payload as? OutboxPayload.WatchAction ?: break
            val member = batchMemberOf(port, candidate, action) ?: break
            if (!identities.add(member.identity)) break
            members += member
            scan++
        }

        if (members.size < 2) return null

        val results = try {
            port.deliverWatchedBatch(members.map { it.event })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {

            List(members.size) { TrackingDeliveryResult.Retryable(DRAIN_DELIVERY_THREW) }
        }

        var retiredMembers = 0
        for ((index, member) in members.withIndex()) {
            val result = results.getOrNull(index)
                ?: TrackingDeliveryResult.Retryable(DRAIN_DELIVERY_THREW)
            when (result) {
                is TrackingDeliveryResult.Delivered -> {

                    outbox.recordIssuedOutcome(member.entry.entryId, IssuedRequestOutcome.Delivered)
                    member.sessionKey?.let {
                        recordDelivered(it, TrackingScrobbleAction.MARK_WATCHED, member.positionMs)
                    }
                    consumed += member.entry.entryId
                    retiredMembers++
                    traceDecision(
                        depth,
                        member.entry,
                        OP_WATCH_ACTION,
                        OUTCOME_DELIVERED,
                        action = TrackingScrobbleAction.MARK_WATCHED.name,
                    )
                }
                is TrackingDeliveryResult.Rejected -> {
                    outbox.recordIssuedOutcome(
                        member.entry.entryId,
                        IssuedRequestOutcome.Rejected(result.reason),
                    )
                    member.sessionKey?.let { sessions.remove(it) }
                    consumed += member.entry.entryId
                    retiredMembers++
                    traceDecision(
                        depth,
                        member.entry,
                        OP_WATCH_ACTION,
                        OUTCOME_PERMANENT,
                        reason = result.reason,
                        action = TrackingScrobbleAction.MARK_WATCHED.name,
                    )
                }
                is TrackingDeliveryResult.Retryable -> {
                    return stopBatchOnRetry(members, index, result, result.retryAfterMs, depth, retiredMembers)
                }
                is TrackingDeliveryResult.Unauthorized -> {
                    return stopBatchOnRetry(
                        members,
                        index,
                        TrackingDeliveryResult.Retryable(result.reason),
                        null,
                        depth,
                        retiredMembers,
                    )
                }
            }
        }
        return BatchDecision.Retired(retiredMembers)
    }

    private fun stopBatchOnRetry(
        members: List<BatchMember>,
        fromIndex: Int,
        result: TrackingDeliveryResult.Retryable,
        retryAfterMs: Long?,
        depth: Int,
        retiredBefore: Int,
    ): BatchDecision {

        for (index in fromIndex until members.size) {
            outbox.fail(
                members[index].entry.entryId,
                result.reason,
                retryable = true,
                now = now(),
                retryAfterMs = retryAfterMs?.takeIf { it > 0L },
            )
        }

        val disposed = disposeIfTrackerDeparted()
        for (index in fromIndex until members.size) {
            traceDecision(
                depth,
                members[index].entry,
                OP_WATCH_ACTION,
                if (disposed > 0) OUTCOME_SUPERSEDED else OUTCOME_RETRYABLE,
                reason = if (disposed > 0) REASON_NOT_SENT_BY_USER_CHOICE else result.reason,
                action = TrackingScrobbleAction.MARK_WATCHED.name,
                deferMs = retryAfterMs,
            )
        }
        return BatchDecision.Stop(retiredBefore, disposed)
    }

    private fun batchMemberOf(
        port: TrackingWatchedWriteBatchPort,
        candidate: OutboxEntry,
        action: OutboxPayload.WatchAction,
    ): BatchMember? {
        if (action.kind != WatchEvent.Kind.EXPLICIT_WATCHED) return null
        val sessionKey = action.sessionId?.value?.toString()
        val mapped = mapObservation(action.kind, sessionKey, action.positionMs)
        if (mapped != TrackingScrobbleAction.MARK_WATCHED) return null
        val media = resolver.resolve(action.watchKey) ?: return null
        val identity = identityOf(media) ?: return null
        val event = TrackingScrobbleEvent(
            action = TrackingScrobbleAction.MARK_WATCHED,
            media = media,
            progressPercent = progressPercent(action.positionMs, action.durationMs),
            occurredAt = action.occurredAt,
        )

        return if (port.isWatchedWriteBatchable(event)) {
            BatchMember(
                entry = candidate,
                sessionKey = sessionKey,
                positionMs = action.positionMs,
                event = event,
                identity = identity,
            )
        } else {
            null
        }
    }

    private fun identityOf(media: TrackingMediaReference): String? {
        val ids = media.externalIds
        val imdb = ids.imdbId?.trim().orEmpty()
        val tmdb = ids.tmdbId?.toString().orEmpty()
        val tvdb = ids.tvdbId?.toString().orEmpty()
        if (imdb.isEmpty() && tmdb.isEmpty() && tvdb.isEmpty()) return null
        return "${media.kind}|imdb=$imdb;tmdb=$tmdb;tvdb=$tvdb|s=${media.seasonNumber};e=${media.episodeNumber}"
    }

    private sealed interface EntryDecision {
        data object Retired : EntryDecision

        data class Stop(val disposed: Int) : EntryDecision
    }

    private fun disposeIfTrackerDeparted(): Int =
        if (selectionGate.isSelected(connectorId)) 0 else outbox.disposeByUserChoice(connectorId)

    private fun watchlistSuspensionReason(payloadAccountRef: String?): String? {
        if (!selectionGate.isSelected(connectorId)) return DEFERRED_NOT_SELECTED
        if (payloadAccountRef == null) return null
        val identity = activeAccountIdentity()
        if (identity.provesRemoteWrite(payloadAccountRef)) return null
        return if (identity.matches(payloadAccountRef)) DEFERRED_ACCOUNT_UNPROVEN else DEFERRED_ACCOUNT_MISMATCH
    }

    private fun supersededByNewerAcknowledgedWrite(entry: OutboxEntry, payload: OutboxPayload): Boolean {
        val identity = activeAccountIdentity()
        val newer = outbox.allEntries().filter { other ->
            other.connectorId == entry.connectorId &&
                other.entryId != entry.entryId &&
                other.state == OutboxState.ACKNOWLEDGED &&
                other.createdAt.isAfter(entry.createdAt)
        }
        if (newer.isEmpty()) return false
        return when (payload) {
            is OutboxPayload.WatchlistWrite -> {
                if (!identity.ownsBinding(payload.accountRef)) return false
                newer.any { other ->
                    val write = other.payload as? OutboxPayload.WatchlistWrite ?: return@any false
                    write.titleId == payload.titleId &&
                        write.kind == payload.kind &&
                        identity.ownsBinding(write.accountRef) &&
                        when (payload.direction) {
                            WatchlistWriteDirection.ADD -> true
                            WatchlistWriteDirection.REMOVE ->
                                write.direction == WatchlistWriteDirection.ADD ||
                                    write.remoteRef == payload.remoteRef
                        }
                }
            }
            is OutboxPayload.ResumeReset -> {
                if (!identity.ownsBinding(payload.accountRef)) return false
                newer.any { other ->
                    val reset = other.payload as? OutboxPayload.ResumeReset ?: return@any false
                    reset.watchKey == payload.watchKey &&
                        identity.ownsBinding(reset.accountRef)
                }
            }
            else -> false
        }
    }

    private fun ratingDeliverable(payloadAccountRef: String?): Boolean {
        if (!selectionGate.isSelected(connectorId)) return false
        if (payloadAccountRef == null) return false
        return activeAccountIdentity().verifiedId == payloadAccountRef
    }

    private suspend fun deliverWatchlistWrite(
        entry: OutboxEntry,
        payload: OutboxPayload.WatchlistWrite,
        depth: Int,
    ): EntryDecision {
        val op = OP_WATCHLIST_WRITE
        val actionName = payload.direction.name
        val port = membership() ?: return unsupported(depth, entry, op, actionName, WATCHLIST_UNSUPPORTED)
        val caps = port.capabilities()
        val supported = if (payload.direction == WatchlistWriteDirection.ADD) caps.add else caps.remove
        if (!supported) {
            return unsupported(
                depth,
                entry,
                op,
                actionName,
                if (payload.direction == WatchlistWriteDirection.ADD) {
                    WATCHLIST_ADD_UNSUPPORTED
                } else {
                    WATCHLIST_REMOVE_UNSUPPORTED
                },
            )
        }
        val ids = TrackingExternalIds.fromTyped(payload.aliases)
        if (ids.isEmpty) return rejected(depth, entry, op, actionName, NO_PROVEN_ALIAS)
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
        return membershipDecision(depth, entry, op, actionName, result)
    }

    private suspend fun deliverResumeReset(
        entry: OutboxEntry,
        payload: OutboxPayload.ResumeReset,
        depth: Int,
    ): EntryDecision {
        val op = OP_RESUME_RESET
        val port = membership() ?: return unsupported(depth, entry, op, ACTION_CLEAR, RESUME_CLEAR_UNSUPPORTED)
        if (!port.capabilities().progressClear) {
            return unsupported(depth, entry, op, ACTION_CLEAR, RESUME_CLEAR_UNSUPPORTED)
        }
        val ids = TrackingExternalIds.fromTyped(payload.aliases)
        if (ids.isEmpty) return rejected(depth, entry, op, ACTION_CLEAR, NO_PROVEN_ALIAS)
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
        return membershipDecision(depth, entry, op, ACTION_CLEAR, port.clearResumeProgress(ref))
    }

    private suspend fun deliverPersonalRating(
        entry: OutboxEntry,
        payload: OutboxPayload.PersonalRating,
        depth: Int,
    ): EntryDecision {
        val op = OP_PERSONAL_RATING
        val actionName = payload.kind.name
        val port = ratings() ?: return unsupported(depth, entry, op, actionName, RATING_UNSUPPORTED)
        val caps = port.capabilities()
        if (!caps.supports(payload.kind)) {
            return unsupported(depth, entry, op, actionName, RATING_UNIT_UNSUPPORTED)
        }
        if (payload.score !in caps.scores) {
            return rejected(depth, entry, op, actionName, RATING_SCORE_OUT_OF_RANGE)
        }
        val ids = TrackingExternalIds.fromTyped(payload.aliases)
        if (ids.isEmpty) return rejected(depth, entry, op, actionName, NO_PROVEN_ALIAS)
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
                traceDecision(depth, entry, op, OUTCOME_DELIVERED, action = actionName)
                EntryDecision.Retired
            }
            is PersonalRatingWriteResult.Retryable -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                traceDecision(depth, entry, op, OUTCOME_RETRYABLE, reason = result.reason, action = actionName)
                EntryDecision.Stop(0)
            }
            is PersonalRatingWriteResult.Unauthorized -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                traceDecision(depth, entry, op, OUTCOME_RETRYABLE, reason = result.reason, action = actionName)
                EntryDecision.Stop(0)
            }
            is PersonalRatingWriteResult.Rejected -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Rejected(result.reason))
                traceDecision(depth, entry, op, OUTCOME_PERMANENT, reason = result.reason, action = actionName)
                EntryDecision.Retired
            }
            is PersonalRatingWriteResult.Unsupported -> {
                outbox.fail(entry.entryId, result.reason, retryable = false, now = now())
                traceDecision(depth, entry, op, OUTCOME_PERMANENT, reason = result.reason, action = actionName)
                EntryDecision.Retired
            }
        }
    }

    private fun membershipDecision(
        depth: Int,
        entry: OutboxEntry,
        operation: String,
        actionName: String,
        result: MembershipWriteResult,
    ): EntryDecision =
        when (result) {
            MembershipWriteResult.Delivered -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Delivered)
                traceDecision(depth, entry, operation, OUTCOME_DELIVERED, action = actionName)
                EntryDecision.Retired
            }
            is MembershipWriteResult.Retryable -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                traceDecision(depth, entry, operation, OUTCOME_RETRYABLE, reason = result.reason, action = actionName)
                EntryDecision.Stop(0)
            }
            is MembershipWriteResult.Unauthorized -> {
                outbox.fail(entry.entryId, result.reason, retryable = true, now = now())
                traceDecision(depth, entry, operation, OUTCOME_RETRYABLE, reason = result.reason, action = actionName)
                EntryDecision.Stop(0)
            }
            is MembershipWriteResult.Rejected -> {
                outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Rejected(result.reason))
                traceDecision(depth, entry, operation, OUTCOME_PERMANENT, reason = result.reason, action = actionName)
                EntryDecision.Retired
            }
            is MembershipWriteResult.Unsupported -> {
                outbox.fail(entry.entryId, result.reason, retryable = false, now = now())
                traceDecision(depth, entry, operation, OUTCOME_PERMANENT, reason = result.reason, action = actionName)
                EntryDecision.Retired
            }
        }

    private fun unsupported(
        depth: Int,
        entry: OutboxEntry,
        operation: String,
        actionName: String?,
        reason: String,
    ): EntryDecision {
        outbox.fail(entry.entryId, reason, retryable = false, now = now())
        traceDecision(depth, entry, operation, OUTCOME_PERMANENT, reason = reason, action = actionName)
        return EntryDecision.Retired
    }

    private fun rejected(
        depth: Int,
        entry: OutboxEntry,
        operation: String,
        actionName: String?,
        reason: String,
    ): EntryDecision {
        outbox.recordIssuedOutcome(entry.entryId, IssuedRequestOutcome.Rejected(reason))
        traceDecision(depth, entry, operation, OUTCOME_PERMANENT, reason = reason, action = actionName)
        return EntryDecision.Retired
    }

    private fun traceDecision(
        depth: Int,
        entry: OutboxEntry,
        operation: String,
        outcome: String,
        reason: String? = null,
        action: String? = null,
        deferMs: Long? = null,
    ) {
        val sink = trace ?: return
        val fields = mutableListOf(
            connectorId,
            "drain",
            "depth=$depth",
            "attempt=${entry.attempts + 1}",
            "op=$operation",
        )
        action?.let { fields += "action=${boundedField(it)}" }
        fields += "outcome=$outcome"
        reason?.let { fields += "reason=${boundedField(it)}" }
        deferMs?.let { fields += "defer_ms=$it" }
        sink(fields.joinToString(" "))
    }

    private fun boundedField(raw: String): String {
        for (char in raw) {
            if (char.isWhitespace()) continue
            if ((char.isLetterOrDigit() && char.code < 128) || char in "_.:-") continue
            return UNKNOWN_FIELD
        }
        val bounded = StringBuilder(minOf(raw.length, MAX_TRACE_FIELD))
        for (char in raw) {
            if (bounded.length >= MAX_TRACE_FIELD) break
            if (char.isWhitespace()) {
                if (bounded.isNotEmpty() && bounded.last() != '-') bounded.append('-')
            } else {
                bounded.append(char)
            }
        }
        val trimmed = bounded.toString().trim('-')
        return if (trimmed.isEmpty()) UNKNOWN_FIELD else trimmed
    }

    fun startDraining(scope: CoroutineScope) {
        synchronized(drainLifecycle) {
            draining = true

            if (durableWorkSubscription == null) {
                durableWorkSubscription = outbox.onDurableWrite(::onDurableWrite)
            }
            if (drainJob?.isActive == true) {

                wakeSignal.complete(Unit)
                return
            }

            drainJob = scope.launch {
                while (isActive) {
                    if (!draining) {

                        awaitWake()
                        continue
                    }
                    val outcome = runCatching { drainPass() }
                        .getOrDefault(PassOutcome.Settled(0, batchFull = false))
                    if (!draining) continue
                    val waitMs = nextWaitMs(outcome)
                    when {
                        waitMs == null -> awaitWake()
                        waitMs > 0L -> withTimeoutOrNull(waitMs) { awaitWake() }

                        else -> Unit
                    }
                }
            }
        }
    }

    private fun nextWaitMs(outcome: PassOutcome): Long? {
        if (outcome is PassOutcome.Settled && outcome.batchFull && outcome.retired > 0) return 0L
        val nowInstant = now()
        val next = runCatching { outbox.nextEligibleAt(connectorId, nowInstant) }.getOrNull()
            ?: return null
        val millis = java.time.Duration.between(nowInstant, next).toMillis()
        return if (millis <= 0L) 0L else millis
    }

    private fun onDurableWrite(connectorId: String) {
        if (connectorId != this.connectorId) return
        synchronized(drainLifecycle) {
            if (!draining) return
            wakeSignal.complete(Unit)
        }
    }

    private suspend fun awaitWake() {
        val signal = synchronized(drainLifecycle) { wakeSignal }
        if (signal.isCompleted) {
            synchronized(drainLifecycle) {
                if (wakeSignal === signal) wakeSignal = CompletableDeferred()
            }
            return
        }
        try {
            signal.join()
        } finally {
            synchronized(drainLifecycle) {
                if (wakeSignal === signal) wakeSignal = CompletableDeferred()
            }
        }
    }

    fun stopDraining() {
        synchronized(drainLifecycle) {
            draining = false
            durableWorkSubscription?.close()
            durableWorkSubscription = null
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

            WatchEvent.Kind.UNWATCHED,

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

        const val MAX_TRACKED_SESSIONS: Int = 64

        internal const val UNRESOLVED_IDENTITY = "no proven tracking identity for this item"
        internal const val UNSUPPORTED_PAYLOAD = "unsupported outbox payload for the scrobble connector"

        internal const val DRAIN_DELIVERY_THREW = "delivery_threw"

        internal const val OP_WATCH_ACTION = "watch_action"
        internal const val OP_WATCHLIST_WRITE = "watchlist_write"
        internal const val OP_RESUME_RESET = "resume_reset"
        internal const val OP_PERSONAL_RATING = "personal_rating"
        internal const val OP_POSITION_UPDATE = "position_update"
        internal const val OP_UNKNOWN = "unknown"

        internal const val OUTCOME_DELIVERED = "delivered"
        internal const val OUTCOME_RETRYABLE = "retryable"
        internal const val OUTCOME_PERMANENT = "permanent"
        internal const val OUTCOME_SUPERSEDED = "superseded"
        internal const val OUTCOME_DEFERRED = "deferred"

        internal const val REASON_ABSORBED = "absorbed"
        internal const val REASON_NOT_SENT_BY_USER_CHOICE = "not_sent_by_user_choice"
        internal const val DEFERRED_NOT_SELECTED = "tracker_not_selected"
        internal const val DEFERRED_ACCOUNT_UNBOUND = "account_not_active"

        internal const val DEFERRED_ACCOUNT_MISMATCH = "tracker_account_mismatch"

        internal const val DEFERRED_ACCOUNT_UNPROVEN = "tracker_account_unproven"

        internal const val SUPERSEDED_BY_NEWER_ACKNOWLEDGED = "SUPERSEDED_BY_NEWER_ACKNOWLEDGED"

        internal const val REASON_SUPERSEDED_BY_NEWER = "superseded_by_newer_acknowledged_write"
        internal const val DEFERRED_NOT_A_TRACKER_WRITE = "not_a_tracker_write"

        internal const val ACTION_CLEAR = "clear"

        internal const val MAX_TRACE_FIELD: Int = 64
        internal const val UNKNOWN_FIELD = "unknown"

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
