package dev.infusion.tv.watchlist

import android.util.Log
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.watchlist.CardAction
import dev.infusion.core.domain.watchlist.CardActionAvailability
import dev.infusion.core.domain.watchlist.CardActionId
import dev.infusion.core.domain.watchlist.CardActionOutcome
import dev.infusion.core.domain.watchlist.CardActionOwner
import dev.infusion.core.domain.watchlist.CardActionSurface
import dev.infusion.core.domain.watchlist.CardActionRequest
import dev.infusion.core.domain.watchlist.RemoteOutcome
import dev.infusion.core.domain.watchlist.ResolvedRemote
import dev.infusion.core.domain.watchlist.ResumeResetDelivery
import dev.infusion.core.domain.watchlist.UnresolvedMembershipRef
import dev.infusion.core.domain.watchlist.WatchlistEntry
import dev.infusion.core.domain.watchlist.WatchlistIdentityCompletion
import dev.infusion.core.domain.watchlist.WatchlistIdentityResolver
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistMembershipPort
import dev.infusion.core.domain.watchlist.WatchlistOps
import dev.infusion.core.domain.watchlist.WatchlistReadRequest
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteState
import dev.infusion.core.domain.watchlist.WatchlistSnapshot
import dev.infusion.core.domain.watchlist.WatchlistStore
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.sync.WatchlistMembershipKind
import dev.infusion.core.model.sync.WatchlistRemoteType
import dev.infusion.core.model.sync.WatchlistWriteDirection
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.feature.vertical.details.MarkWatchedActions
import dev.infusion.feature.vertical.watchlist.ResumeResetAction
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchlistCoordinator(
    private val store: WatchlistStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val watchCoordinator: WatchSyncCoordinator,
    private val outbox: WatchOutbox,
    private val identity: WatchlistIdentityResolver,

    private val identityCompletion: WatchlistIdentityCompletion? = null,
    private val memberships: () -> Map<String, WatchlistMembershipPort>,

    private val activeTracker: () -> String?,

    private val accountRef: (String) -> String?,

    private val onChanged: () -> Unit = {},

    private val isResumeEligible: (WatchKey) -> Boolean = { false },

    private val isWatchedUnit: (WatchKey) -> Boolean = { false },

    private val acquisition: dev.infusion.tv.acquisition.AcquisitionCoordinator? = null,

    private val ratings: dev.infusion.tv.ratings.PersonalRatingsCoordinator? = null,
    private val now: () -> Instant = Instant::now,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CardActionOwner {

    private val _state = MutableStateFlow(store.read(profileScope.activeProfileId))
    val state: StateFlow<WatchlistSnapshot> = _state.asStateFlow()

    private val _remote = MutableStateFlow(WatchlistRemoteState.IDLE)
    val remoteState: StateFlow<WatchlistRemoteState> = _remote.asStateFlow()

    private val _report = MutableStateFlow(WatchlistReadReport())
    val report: StateFlow<WatchlistReadReport> = _report.asStateFlow()

    fun snapshot(): WatchlistSnapshot = _state.value

    fun activeTrackerIdOrNull(): String? = activeTracker()

    private fun persist(next: WatchlistSnapshot) {
        if (next == _state.value) return
        store.write(profileScope.activeProfileId, next)
        _state.value = next
        onChanged()
    }

    fun reloadForActiveProfile() {
        _state.value = store.read(profileScope.activeProfileId)
    }

    fun refresh() {
        scope.launch { refreshNow() }
    }

    suspend fun refreshNow() {
        val trackerId = activeTracker()
        if (trackerId == null) {

            _remote.value = WatchlistRemoteState.IDLE
            _report.value = WatchlistReadReport(state = WatchlistRemoteState.IDLE)
            persist(WatchlistOps.withdrawOtherAccounts(_state.value, null, null))
            return
        }
        val port = memberships()[trackerId] ?: return
        if (!port.accountConnected()) return
        val account = accountRef(trackerId)

        _remote.value = WatchlistRemoteState.LOADING
        val resolved = ArrayList<ResolvedRemote>()
        val seenEntryIds = HashSet<String>()
        var unresolved = 0
        var received = 0
        val unresolvedByKind = LinkedHashMap<WatchlistKind, Int>()

        val unresolvedMemberships = LinkedHashSet<UnresolvedMembershipRef>()
        val unresolvedIdentities = ArrayList<WatchlistUnresolvedMembership>()
        val incomplete = LinkedHashSet<WatchlistRemoteKind>()
        var page = 1
        var cursor: String? = null
        var completed = false
        var failure: String? = null
        while (page <= MAX_PAGES) {
            when (val result = port.read(WatchlistReadRequest(page = page, cursor = cursor))) {
                is TrackingInboundOutcome.Ok -> {
                    received += result.value.records.size
                    incomplete += result.value.incompleteRemoteKinds
                    for (record in result.value.records) {

                        val r = identity.resolve(record) ?: identityCompletion?.complete(record)
                        if (r == null) {
                            unresolved++
                            val row = record.remoteKind.normalizesTo()
                            unresolvedByKind[row] = (unresolvedByKind[row] ?: 0) + 1
                            unresolvedMemberships +=
                                UnresolvedMembershipRef(record.remoteKind, record.remoteRef)
                            unresolvedIdentities += WatchlistUnresolvedMembership(
                                kind = row,
                                remoteKind = record.remoteKind,
                                remoteRef = record.remoteRef,
                                title = record.title,
                            )
                            continue
                        }

                        seenEntryIds += entryIdOf(r.kind, r.titleId)
                        resolved += r
                    }
                    cursor = result.value.next
                    if (cursor == null) {
                        completed = true
                        break
                    }
                    page++
                }
                else -> {

                    completed = false
                    failure = when (result) {
                        is TrackingInboundOutcome.Unauthorized -> "authorization"
                        is TrackingInboundOutcome.Unavailable -> result.reason
                        is TrackingInboundOutcome.Rejected -> result.reason
                        is TrackingInboundOutcome.Ok -> null
                    }
                    break
                }
            }
        }

        val completeKinds: Set<WatchlistKind> = if (!completed) {
            emptySet()
        } else {
            WatchlistKind.entries.filterTo(LinkedHashSet()) { kind ->
                incomplete.none { it.normalizesTo() == kind }
            }
        }

        var next = WatchlistOps.withdrawOtherAccounts(_state.value, trackerId, account)
        next = WatchlistOps.reconcile(
            snapshot = next,
            providerId = trackerId,
            accountRef = account,
            resolved = resolved,
            unresolved = unresolved,
            completed = completed,
            now = now(),
            completeKinds = completeKinds,

            unresolvedMemberships = unresolvedMemberships,
        )
        next = WatchlistOps.retireAbsentSuppressions(
            next,
            trackerId,
            account,
            seenEntryIds,
            completed,
            completeKinds,
        )
        persist(next)
        _remote.value = if (completed && completeKinds.isNotEmpty()) {
            WatchlistRemoteState.LOADED
        } else {
            WatchlistRemoteState.FAILED
        }
        _report.value = WatchlistReadReport(
            state = _remote.value,
            received = received,
            published = resolved.size,
            unresolved = unresolved,
            completeKinds = completeKinds,
            unresolvedByKind = unresolvedByKind,
            reason = failure,
            unresolvedMemberships = unresolvedIdentities,
        )

        if (unresolved > 0) {
            Log.i(
                WATCHLIST_LOG_TAG,
                "sweep provider=$trackerId received=$received published=${resolved.size} " +
                    "unresolved=$unresolved complete=${completeKinds.joinToString(",") { it.name }} " +
                    "unresolved_records=" + unresolvedIdentities.joinToString(";") { record ->
                        "${record.remoteKind.name}:${record.remoteRef}" +
                            (record.title?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: "")
                    },
            )
        }
    }

    private fun enqueueAdd(
        trackerId: String,
        account: String?,
        titleId: TitleId,
        kind: WatchlistKind,
        title: String,
        year: Int?,
    ) {
        val resolution = identity.aliasesFor(titleId, kind) ?: return
        val payload = OutboxPayload.WatchlistWrite(
            direction = WatchlistWriteDirection.ADD,
            kind = kind.toMembershipKind(),
            titleId = resolution.titleId.value.toString(),
            aliases = resolution.externalIds.typed(),
            remoteType = if (kind == WatchlistKind.MOVIE) {
                WatchlistRemoteType.MOVIE
            } else {
                WatchlistRemoteType.SHOW
            },
            remoteRef = null,
            title = title.takeIf { it.isNotBlank() } ?: resolution.title,
            year = year ?: resolution.year,
            accountRef = account,
            occurredAt = now(),
        )
        enqueue(trackerId, key(DIR_ADD, trackerId, account, payload.titleId, "-"), payload)
    }

    private fun enqueueRemovals(
        trackerId: String,
        account: String?,
        entry: WatchlistEntry,
        refs: List<String>,
    ) {
        val resolution = identity.aliasesFor(entry.titleId, entry.kind) ?: return
        val targets: List<String?> = if (refs.isEmpty()) listOf(null) else refs
        for (ref in targets) {
            val payload = OutboxPayload.WatchlistWrite(
                direction = WatchlistWriteDirection.REMOVE,
                kind = entry.kind.toMembershipKind(),
                titleId = resolution.titleId.value.toString(),
                aliases = resolution.externalIds.typed(),
                remoteType = if (entry.kind == WatchlistKind.MOVIE) {
                    WatchlistRemoteType.MOVIE
                } else {
                    WatchlistRemoteType.SHOW
                },
                remoteRef = ref,
                title = entry.title,
                year = entry.year,
                accountRef = account,
                occurredAt = now(),
            )
            enqueue(trackerId, key(DIR_REMOVE, trackerId, account, payload.titleId, ref ?: "-"), payload)
        }
    }

    private fun enqueueResumeReset(trackerId: String, account: String?, unit: WatchKey) {
        val seriesOrTitle = unit.seriesId ?: unit.titleId ?: return
        val kind = if (unit.isEpisode) WatchlistKind.SHOW else WatchlistKind.MOVIE
        val resolution = identity.aliasesFor(seriesOrTitle, kind) ?: return
        val payload = OutboxPayload.ResumeReset(
            watchKey = unit,
            kind = kind.toMembershipKind(),
            aliases = resolution.externalIds.typed(),
            titleId = resolution.titleId.value.toString(),
            seasonNumber = unit.seasonNumber,
            episodeNumber = unit.episodeNumber,
            accountRef = account,
            occurredAt = now(),
        )
        enqueue(
            trackerId,
            key(DIR_RESET, trackerId, account, payload.titleId ?: "-", "-"),
            payload,
        )
    }

    private fun enqueue(connectorId: String, idempotencyKey: String, payload: OutboxPayload) {
        outbox.enqueue(
            OutboxEntry(
                entryId = OutboxEntryId.random(),
                connectorId = connectorId,
                idempotencyKey = idempotencyKey,
                payload = payload,
                createdAt = now(),
            ),
        )
    }

    private fun cancelPendingAdds(
        trackerId: String,
        account: String?,
        titleId: TitleId,
        kind: WatchlistKind,
    ) {
        val target = titleId.value.toString()
        for (entry in outbox.allEntries()) {
            if (entry.connectorId != trackerId) continue
            val payload = entry.payload as? OutboxPayload.WatchlistWrite ?: continue
            if (payload.direction != WatchlistWriteDirection.ADD) continue
            if (payload.titleId != target) continue
            if (payload.kind != kind.toMembershipKind()) continue
            if (payload.accountRef != account) continue
            outbox.fail(entry.entryId, SUPERSEDED_BY_REMOVE, retryable = false, now = now())
        }
    }

    override fun warm(request: CardActionRequest) {
        acquisition?.warm(request)

        ratings?.warm(request)
    }

    override fun actionsFor(request: CardActionRequest): List<CardAction> {
        val acquisitionRow = acquisition?.actionFor(request)?.let { listOf(it) } ?: emptyList()

        val ratingRow = ratings?.actionFor(request)?.let { listOf(it) } ?: emptyList()

        if (request.kind == TitleKind.SEASON) return acquisitionRow

        val playFromBeginningRow = playFromBeginningAction(request)

        val browseShowRow = browseShowAction(request)

        val target = targetOf(request)
        if (target == null) {
            return listOf(
                CardAction(
                    CardActionId.ADD_TO_WATCHLIST,
                    "Add to Watchlist",
                    CardActionAvailability.Disabled("no proven identity for this card"),
                ),
            ) + browseShowRow + watchedAction(request) + playFromBeginningRow +
                ratingRow + resetAction(request) + acquisitionRow
        }
        val entry = _state.value.entry(entryIdOf(target.second, target.first))
        val inWatchlist = entry?.let { it.isLocal || hasActiveRemote(it) } == true
        val watchlistAction = if (inWatchlist) {
            CardAction(CardActionId.REMOVE_FROM_WATCHLIST, "Remove from Watchlist")
        } else {
            CardAction(CardActionId.ADD_TO_WATCHLIST, "Add to Watchlist")
        }
        return browseShowRow + watchlistAction + watchedAction(request) + playFromBeginningRow +
            ratingRow + resetAction(request) + acquisitionRow
    }

    private fun browseShowAction(request: CardActionRequest): List<CardAction> {
        if (request.surface != CardActionSurface.BROWSE) return emptyList()
        if (request.kind != TitleKind.EPISODE) return emptyList()
        if (request.watchKey?.isEpisode != true) return emptyList()
        if (request.provenShowId == null) return emptyList()
        return listOf(CardAction(CardActionId.BROWSE_SHOW, "Browse show"))
    }

    private fun playFromBeginningAction(request: CardActionRequest): List<CardAction> {
        if (request.kind != TitleKind.EPISODE) return emptyList()
        if (request.playableTitleId == null) return emptyList()
        if (request.watchKey?.isEpisode != true) return emptyList()
        return listOf(CardAction(CardActionId.PLAY_FROM_BEGINNING, "Play from beginning"))
    }

    private fun watchedAction(request: CardActionRequest): List<CardAction> {
        val unit = request.watchedUnit ?: return emptyList()
        return if (isWatchedUnit(unit)) {
            listOf(CardAction(CardActionId.MARK_UNWATCHED, "Mark as unwatched"))
        } else {
            listOf(CardAction(CardActionId.MARK_WATCHED, "Mark as watched"))
        }
    }

    private fun resetAction(request: CardActionRequest): List<CardAction> {
        val unit = request.resumeUnit ?: return emptyList()
        return if (isResumeEligible(unit)) {
            listOf(CardAction(CardActionId.RESET_RESUME, "Reset resume point"))
        } else {
            emptyList()
        }
    }

    override suspend fun perform(action: CardAction, request: CardActionRequest): CardActionOutcome =
        withContext(ioDispatcher) { performNow(action, request) }

    private suspend fun performNow(action: CardAction, request: CardActionRequest): CardActionOutcome {
        val target = targetOf(request)
        return when (action.id) {
            CardActionId.ADD_TO_WATCHLIST,
            CardActionId.REMOVE_FROM_WATCHLIST,
            -> {
                val (titleId, kind) = target ?: return CardActionOutcome.Refused("no proven identity")
                when (action.id) {
                    CardActionId.ADD_TO_WATCHLIST -> performAdd(request, titleId, kind)
                    else -> performRemove(request, titleId, kind)
                }
            }
            CardActionId.MARK_WATCHED -> performWatched(request, watched = true)
            CardActionId.MARK_UNWATCHED -> performWatched(request, watched = false)
            CardActionId.RESET_RESUME -> performReset(request)

            CardActionId.SET_RATING ->
                CardActionOutcome.Refused("a personal rating needs a chosen score")

            CardActionId.ADD_TO_JELLYFIN ->
                acquisition?.perform(request)
                    ?: CardActionOutcome.Refused("acquisition is not available")

            CardActionId.PLAY_FROM_BEGINNING ->
                CardActionOutcome.Refused("the shell routes Play from beginning to playback")

            CardActionId.BROWSE_SHOW ->
                CardActionOutcome.Refused("the shell routes Browse show to navigation")
        }
    }

    private fun performWatched(request: CardActionRequest, watched: Boolean): CardActionOutcome {
        val unit = request.watchedUnit ?: return CardActionOutcome.Refused("no proven unit")
        if (watched) {
            MarkWatchedActions.markWatched(watchCoordinator, unit, now)
        } else {
            MarkWatchedActions.markUnwatched(watchCoordinator, unit, now)
        }
        return CardActionOutcome.Done
    }

    private fun performAdd(
        request: CardActionRequest,
        titleId: TitleId,
        kind: WatchlistKind,
    ): CardActionOutcome {
        persist(
            WatchlistOps.addLocal(
                snapshot = _state.value,
                titleId = titleId,
                kind = kind,
                title = request.displayTitle,
                year = request.year,
                artwork = request.artwork,
                now = now(),
            ),
        )
        val tracker = activeTracker()
        if (tracker != null) {
            val account = accountRef(tracker)
            if (canWrite(tracker, MembershipOp.ADD)) {
                enqueueAdd(tracker, account, titleId, kind, request.displayTitle, request.year)
            }
        }
        return CardActionOutcome.Local(remoteStatusFor(tracker, MembershipOp.ADD))
    }

    private fun performRemove(
        request: CardActionRequest,
        titleId: TitleId,
        kind: WatchlistKind,
    ): CardActionOutcome {
        val tracker = activeTracker()
        val account = tracker?.let { accountRef(it) }
        val key = entryIdOf(kind, titleId)
        val before = _state.value.entry(key)
        val result = WatchlistOps.remove(
            snapshot = _state.value,
            entryId = key,
            providerId = tracker,
            accountRef = account,
            now = now(),
        )
        persist(result.snapshot)
        if (tracker != null) {
            cancelPendingAdds(tracker, account, titleId, kind)
            if (canWrite(tracker, MembershipOp.REMOVE) && before != null) {
                enqueueRemovals(tracker, account, before, result.remoteRefs)
            }
        }
        return CardActionOutcome.Local(remoteStatusFor(tracker, MembershipOp.REMOVE))
    }

    private fun performReset(request: CardActionRequest): CardActionOutcome {
        val unit = request.resumeUnit ?: return CardActionOutcome.Refused("no resume unit")
        ResumeResetAction.reset(watchCoordinator, unit, now)

        val tracker = activeTracker()
        if (tracker == null) {

            return CardActionOutcome.Local(RemoteOutcome.None)
        }
        val account = accountRef(tracker)
        val clearable = canWrite(tracker, MembershipOp.RESET)
        val remote = remoteStatusFor(tracker, MembershipOp.RESET)
        if (clearable) enqueueResumeReset(tracker, account, unit)
        persist(
            WatchlistOps.recordResumeGuard(
                snapshot = _state.value,
                watchKey = unit,
                providerId = tracker,
                accountRef = account,
                now = now(),
                sequence = now().toEpochMilli(),
                delivery = if (clearable) ResumeResetDelivery.PENDING else ResumeResetDelivery.UNSUPPORTED,
            ),
        )
        return CardActionOutcome.Local(remote)
    }

    fun blocksInboundResume(watchKey: WatchKey): Boolean =
        WatchlistOps.guardBlocksInbound(_state.value, watchKey)

    fun notePlaybackRead(startedAt: Instant, completed: Boolean, seenWatchKeys: Set<WatchKey>) {
        if (!completed) return
        val snapshot = _state.value
        val retire = snapshot.guards.filter { guard ->
            guard.watchKey !in seenWatchKeys && startedAt.isAfter(guard.resetAt)
        }
        if (retire.isEmpty()) return
        var next = snapshot
        for (guard in retire) next = WatchlistOps.retireResumeGuard(next, guard.watchKey)
        persist(next)
    }

    private var visibilityJob: Job? = null

    fun onPageVisible(genuineVisit: Boolean = true) {
        if (visibilityJob?.isActive == true) return
        visibilityJob = scope.launch {
            var last = activeTracker()
            if (genuineVisit) refreshNow()
            while (isActive) {
                delay(SELECTION_POLL_MS)
                val current = activeTracker()
                if (current != last) {
                    last = current
                    refreshNow()
                }
            }
        }
    }

    fun onPageHidden() {
        visibilityJob?.cancel()
        visibilityJob = null
    }

    private enum class MembershipOp { ADD, REMOVE, RESET }

    private fun canWrite(trackerId: String, op: MembershipOp): Boolean {
        val port = memberships()[trackerId] ?: return false
        if (!port.accountConnected()) return false
        val caps = port.capabilities()
        return when (op) {
            MembershipOp.ADD -> caps.add
            MembershipOp.REMOVE -> caps.remove
            MembershipOp.RESET -> caps.progressClear
        }
    }

    private fun remoteStatusFor(trackerId: String?, op: MembershipOp): RemoteOutcome {
        if (trackerId == null) return RemoteOutcome.None
        val port = memberships()[trackerId]
            ?: return RemoteOutcome.Failed("$trackerId has no watchlist adapter in this build")
        if (!port.accountConnected()) return RemoteOutcome.Failed("$trackerId account is not connected")
        val caps = port.capabilities()
        return when (op) {
            MembershipOp.ADD -> if (caps.add) {
                RemoteOutcome.Pending
            } else {
                RemoteOutcome.Unsupported("$trackerId does not expose a watchlist Add")
            }
            MembershipOp.REMOVE -> if (caps.remove) {
                RemoteOutcome.Pending
            } else {
                RemoteOutcome.Unsupported(
                    "$trackerId documents no watchlist removal that avoids history/ratings",
                )
            }
            MembershipOp.RESET -> if (caps.progressClear) {
                RemoteOutcome.Pending
            } else {
                RemoteOutcome.Unsupported("$trackerId exposes no progress-only clear")
            }
        }
    }

    private fun hasActiveRemote(entry: WatchlistEntry): Boolean {
        val tracker = activeTracker() ?: return false
        return entry.hasRemoteMembership(tracker, accountRef(tracker))
    }

    private fun targetOf(request: CardActionRequest): Pair<TitleId, WatchlistKind>? {
        val show = request.provenShowId
        if (show != null) return show to WatchlistKind.SHOW
        val movie = request.provenMovieId
        if (movie != null) return movie to WatchlistKind.MOVIE
        return null
    }

    private fun entryIdOf(kind: WatchlistKind, titleId: TitleId): String =
        "${kind.name}:${titleId.value}"

    private fun WatchlistKind.toMembershipKind(): WatchlistMembershipKind = when (this) {
        WatchlistKind.MOVIE -> WatchlistMembershipKind.MOVIE
        WatchlistKind.SHOW -> WatchlistMembershipKind.SHOW
    }

    private fun key(
        dir: String,
        trackerId: String,
        account: String?,
        titleId: String,
        ref: String,
    ): String = "wl:$dir:$trackerId:${account ?: "-"}:$titleId:$ref:${now().toEpochMilli()}"

    private companion object {
        const val MAX_PAGES = 20
        const val DIR_ADD = "add"
        const val DIR_REMOVE = "remove"
        const val DIR_RESET = "reset"
        const val SUPERSEDED_BY_REMOVE = "SUPERSEDED_BY_REMOVE"
        const val SELECTION_POLL_MS = 3_000L

        const val WATCHLIST_LOG_TAG = "InfusionWatchlist"
    }
}

data class WatchlistUnresolvedMembership(
    val kind: WatchlistKind,
    val remoteKind: WatchlistRemoteKind,
    val remoteRef: String,
    val title: String? = null,
)

data class WatchlistReadReport(
    val state: WatchlistRemoteState = WatchlistRemoteState.IDLE,
    val received: Int = 0,
    val published: Int = 0,
    val unresolved: Int = 0,
    val completeKinds: Set<WatchlistKind> = emptySet(),

    val unresolvedByKind: Map<WatchlistKind, Int> = emptyMap(),
    val reason: String? = null,

    val unresolvedMemberships: List<WatchlistUnresolvedMembership> = emptyList(),
)
