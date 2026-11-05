package dev.infusion.tv.ratings

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.ratings.PersonalRatingCapabilities
import dev.infusion.core.domain.ratings.PersonalRatingEntry
import dev.infusion.core.domain.ratings.PersonalRatingLibraryRequest
import dev.infusion.core.domain.ratings.PersonalRatingPort
import dev.infusion.core.domain.ratings.PersonalRatingRef
import dev.infusion.core.domain.ratings.PersonalRatingState
import dev.infusion.core.domain.ratings.PersonalRatingWriteResult
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.watchlist.CardAction
import dev.infusion.core.domain.watchlist.CardActionAvailability
import dev.infusion.core.domain.watchlist.CardActionId
import dev.infusion.core.domain.watchlist.CardActionOutcome
import dev.infusion.core.domain.watchlist.CardActionRequest
import dev.infusion.core.domain.watchlist.RemoteOutcome
import dev.infusion.core.domain.watchlist.WatchlistAliasResolution
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxPayload
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.sync.PersonalRatingKind
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PersonalRatingsCoordinator(
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val outbox: WatchOutbox,

    private val ratings: () -> Map<String, PersonalRatingPort>,

    private val activeTracker: () -> String?,

    private val accountRef: (String) -> String?,

    private val aliasesFor: (TitleId, WatchlistKind) -> WatchlistAliasResolution?,
    private val now: () -> Instant = Instant::now,

    private val onChanged: () -> Unit = {},
) {

    private data class RatingUnit(
        val kind: PersonalRatingKind,
        val titleId: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    )

    private data class EvidenceKey(
        val scope: String,
        val unit: RatingUnit,
    )

    private data class Library(
        val entries: List<PersonalRatingEntry>,
        val complete: Boolean,
        val startedAt: Instant,
        val loadedAt: Instant,
        val failure: String?,
    )

    private val _states = MutableStateFlow<Map<EvidenceKey, PersonalRatingState>>(emptyMap())

    private val _deliveries = MutableStateFlow<Map<EvidenceKey, RemoteOutcome>>(emptyMap())

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val libraries = HashMap<String, Library>()

    private val confirmed = HashMap<EvidenceKey, PersonalRatingState>()

    private val loading = HashSet<String>()

    private val optimistic = HashMap<EvidenceKey, Int>()

    private var generationCounter: Long = 0L

    private fun isCurrent(generation: Long): Boolean = generation == generationCounter

    private fun unitOf(request: CardActionRequest): RatingUnit? {
        val unit = request.localUnit ?: return null
        val anchor = unit.seriesId ?: unit.titleId ?: return null
        return if (unit.isEpisode) {
            val season = unit.seasonNumber
            val episode = unit.episodeNumber
            if (season == null || episode == null) {
                null
            } else {
                RatingUnit(PersonalRatingKind.EPISODE, anchor.value.toString(), season, episode)
            }
        } else {
            RatingUnit(PersonalRatingKind.MOVIE, anchor.value.toString(), null, null)
        }
    }

    private fun activePort(unit: RatingUnit): Pair<String, PersonalRatingPort>? {
        val tracker = activeTracker() ?: return null
        val port = ratings()[tracker] ?: return null
        if (!port.accountConnected()) return null
        if (!port.capabilities().supports(unit.kind)) return null
        return tracker to port
    }

    private fun portFor(unit: RatingUnit): PersonalRatingPort? = activePort(unit)?.second

    private fun activeKey(unit: RatingUnit): EvidenceKey? {
        val tracker = activePort(unit)?.first ?: return null
        return EvidenceKey(scopeKey(tracker, accountRef(tracker), generationCounter), unit)
    }

    fun actionFor(request: CardActionRequest): CardAction? {
        val unit = unitOf(request) ?: return null
        activePort(unit) ?: return null
        val state = _states.value[activeKey(unit) ?: return null] ?: PersonalRatingState.Unknown
        return CardAction(
            id = CardActionId.SET_RATING,
            label = labelFor(state),
            availability = CardActionAvailability.Enabled,
            detail = trailingScoreFor(state),
        )
    }

    private fun labelFor(state: PersonalRatingState): String = when (state) {
        is PersonalRatingState.Rated, is PersonalRatingState.RatedFractional -> "Change rating"
        PersonalRatingState.Unrated -> "Add rating"
        else -> "Set rating"
    }

    private fun trailingScoreFor(state: PersonalRatingState): String? = when (state) {
        is PersonalRatingState.Rated -> state.value.toString()
        is PersonalRatingState.RatedFractional -> formatPersonalRatingValue(state.precise)
        else -> null
    }

    fun stateFor(request: CardActionRequest): PersonalRatingState {
        val unit = unitOf(request) ?: return PersonalRatingState.Unknown
        val key = activeKey(unit) ?: return PersonalRatingState.Unknown
        return _states.value[key] ?: PersonalRatingState.Unknown
    }

    fun scoresFor(request: CardActionRequest): IntRange? {
        val unit = unitOf(request) ?: return null
        return portFor(unit)?.capabilities()?.scores
    }

    fun sideEffectNoteFor(request: CardActionRequest): String? {
        val unit = unitOf(request) ?: return null
        return portFor(unit)?.capabilities()?.sideEffectNote
    }

    fun deliveryFor(request: CardActionRequest): RemoteOutcome {
        val unit = unitOf(request) ?: return RemoteOutcome.None
        val key = activeKey(unit) ?: return RemoteOutcome.None
        return _deliveries.value[key] ?: RemoteOutcome.None
    }

    fun warm(request: CardActionRequest) {
        val unit = unitOf(request) ?: return
        val tracker = activeTracker() ?: return
        if (portFor(unit) == null) return
        val generation = generationCounter
        scope.launch { refreshUnit(unit, tracker, generation) }
    }

    fun reloadForActiveProfile() {
        libraries.clear()
        confirmed.clear()
        optimistic.clear()
        _states.value = emptyMap()
        _deliveries.value = emptyMap()

        generationCounter++
        bump()
    }

    fun onTrackerConnectionChanged() {
        libraries.clear()

        generationCounter++
        bump()
    }

    private suspend fun refreshUnit(unit: RatingUnit, tracker: String, generation: Long) {
        val port = ratings()[tracker] ?: return

        port.resolveAccountIdentity()
        if (!isCurrent(generation)) return
        val account = accountRef(tracker)
        val key = EvidenceKey(scopeKey(tracker, account, generation), unit)
        reconcileDelivery(key, unit, tracker, account, generation)
        if (!isCurrent(generation)) return
        if (optimistic.containsKey(key)) return
        val caps = port.capabilities()
        if (!caps.read) {

            publish(key, PersonalRatingState.Unknown, generation)
            return
        }
        val scope = key.scope
        val cached = libraries[scope]
        val fresh = cached != null && !cached.loadedAt.plus(LIBRARY_TTL).isBefore(now())
        if (!fresh && !loading.contains(scope)) {
            publish(key, PersonalRatingState.Loading, generation)
            loadLibrary(tracker, account, scope, generation)
        }
        if (!isCurrent(generation)) return
        val library = libraries[scope] ?: run {
            publish(key, PersonalRatingState.Loading, generation)
            return
        }
        resolveFromLibrary(key, tracker, library, generation)
    }

    private suspend fun loadLibrary(tracker: String, account: String?, scope: String, generation: Long) {
        loading += scope
        val port = ratings()[tracker]
        val startedAt = now()
        val entries = ArrayList<PersonalRatingEntry>()
        var cursor: String? = null
        var page = 1
        var complete = false
        var failure: String? = null
        if (port != null) {
            while (page <= MAX_PAGES) {
                when (val out = port.readLibrary(PersonalRatingLibraryRequest(page = page, cursor = cursor))) {
                    is TrackingInboundOutcome.Ok -> {
                        entries += out.value.entries
                        cursor = out.value.next
                        if (cursor == null) {
                            complete = !out.value.truncating
                            break
                        }
                        page++
                    }
                    is TrackingInboundOutcome.Unauthorized -> {
                        failure = out.reason
                        break
                    }
                    is TrackingInboundOutcome.Unavailable -> {
                        failure = out.reason
                        break
                    }
                    is TrackingInboundOutcome.Rejected -> {
                        failure = out.reason
                        break
                    }
                }
            }
            if (page > MAX_PAGES) complete = false
        } else {
            failure = "no rating adapter for $tracker"
        }

        if (!isCurrent(generation)) {
            loading -= scope
            return
        }
        libraries[scope] = Library(
            entries = entries,
            complete = complete,
            startedAt = startedAt,
            loadedAt = now(),
            failure = failure,
        )
        loading -= scope

        for (key in _states.value.keys + optimistic.keys) {
            if (key.scope != scope) continue
            val library = libraries[scope] ?: continue
            resolveFromLibrary(key, tracker, library, generation)
        }
    }

    private suspend fun resolveFromLibrary(key: EvidenceKey, tracker: String, library: Library, generation: Long) {
        if (!isCurrent(generation)) return
        val override = optimistic[key]
        if (override != null) {
            publish(key, PersonalRatingState.Rated(override), generation)
            return
        }
        val unit = key.unit
        val aliases = resolveAliases(unit) ?: run {

            publish(key, PersonalRatingState.Unknown, generation)
            return
        }
        val matched = matchedStateFor(library, unit, aliases)
        if (matched != null) {
            confirmed[key] = matched
            publish(key, matched, generation)
            return
        }

        val state = when {
            library.failure != null -> confirmed[key] ?: PersonalRatingState.Failed(library.failure)
            library.complete -> PersonalRatingState.Unrated
            else -> PersonalRatingState.Unknown
        }
        publish(key, state, generation)
    }

    private fun matchedStateFor(
        library: Library,
        unit: RatingUnit,
        aliases: TrackingExternalIds,
    ): PersonalRatingState? {
        for (entry in library.entries) {
            if (entry.kind != unit.kind) continue
            if (!overlaps(aliases, entry.externalIds)) continue
            if (unit.kind == PersonalRatingKind.EPISODE) {
                if (entry.seasonNumber != unit.seasonNumber) continue
                if (entry.episodeNumber != unit.episodeNumber) continue
            }
            entry.score?.let { return PersonalRatingState.Rated(it) }
            entry.preciseScore?.let { return PersonalRatingState.RatedFractional(it) }
        }
        return null
    }

    private fun overlaps(a: TrackingExternalIds, b: TrackingExternalIds): Boolean =
        (a.tmdbId != null && a.tmdbId == b.tmdbId) ||
            (a.imdbId != null && a.imdbId.equals(b.imdbId, ignoreCase = true)) ||
            (a.tvdbId != null && a.tvdbId == b.tvdbId)

    private suspend fun resolveAliases(unit: RatingUnit): TrackingExternalIds? {
        val titleId = runCatching { TitleId.fromString(unit.titleId) }.getOrNull() ?: return null
        val kind = if (unit.kind == PersonalRatingKind.EPISODE) {
            WatchlistKind.SHOW
        } else {
            WatchlistKind.MOVIE
        }
        val resolution = aliasesFor(titleId, kind) ?: return null
        return resolution.externalIds.takeUnless { it.isEmpty }
    }

    suspend fun submit(request: CardActionRequest, score: Int): CardActionOutcome {
        val unit = unitOf(request) ?: return CardActionOutcome.Refused("no proven rating unit")
        val tracker = activeTracker() ?: return CardActionOutcome.Refused("no active tracker")
        val port = ratings()[tracker]
            ?: return CardActionOutcome.Refused("$tracker has no rating adapter in this build")
        if (!port.accountConnected()) {
            return CardActionOutcome.Refused("$tracker account is not connected")
        }
        val caps = port.capabilities()
        if (!caps.supports(unit.kind)) {
            return CardActionOutcome.Refused("$tracker does not support a ${unit.kind.name} rating")
        }
        if (score !in caps.scores) {
            return CardActionOutcome.Refused("$tracker accepts scores ${caps.scaleMin}–${caps.scaleMax}")
        }

        port.resolveAccountIdentity()
        val generation = generationCounter
        val account = accountRef(tracker)
            ?: return CardActionOutcome.Refused("could not verify the active $tracker account")
        val aliases = resolveAliases(unit)
            ?: return CardActionOutcome.Refused("no proven identity for this rating")

        if (!isCurrent(generation)) {
            return CardActionOutcome.Refused("the active $tracker account changed")
        }
        val key = EvidenceKey(scopeKey(tracker, account, generation), unit)
        val occurredAt = now()

        optimistic[key] = score
        publish(key, PersonalRatingState.Rated(score), generation)
        setDelivery(key, RemoteOutcome.Pending, generation)

        val payload = OutboxPayload.PersonalRating(
            kind = unit.kind,
            titleId = unit.titleId,
            aliases = aliases.typed(),
            score = score,
            seasonNumber = unit.seasonNumber,
            episodeNumber = unit.episodeNumber,
            title = request.displayTitle.takeIf { it.isNotBlank() },
            year = request.year,
            accountRef = account,
            occurredAt = occurredAt,
        )
        outbox.enqueue(
            OutboxEntry(
                entryId = OutboxEntryId.random(),
                connectorId = tracker,
                idempotencyKey = ratingKey(tracker, account, unit, occurredAt),
                payload = payload,
                createdAt = occurredAt,
            ),
        )
        onChanged()
        return CardActionOutcome.Local(RemoteOutcome.Pending)
    }

    private fun reconcileDelivery(
        key: EvidenceKey,
        unit: RatingUnit,
        tracker: String,
        account: String?,
        generation: Long,
    ) {
        if (!isCurrent(generation)) return
        val prefix = ratingKeyPrefix(tracker, account, unit)
        val latest = outbox.allEntries()
            .filter { it.connectorId == tracker && it.idempotencyKey.startsWith(prefix) }
            .maxByOrNull { it.createdAt }
        if (latest == null) {
            val current = _deliveries.value[key]
            if (current != null && current != RemoteOutcome.None) {
                failLocal(key, "rating write is no longer queued", generation)
            }
            return
        }
        when (latest.state) {
            OutboxState.ACKNOWLEDGED -> {
                optimistic[key]?.let { confirmed[key] = PersonalRatingState.Rated(it) }
                setDelivery(key, RemoteOutcome.Delivered, generation)
            }
            OutboxState.CANCELLED -> failLocal(key, latest.lastError ?: "rating write was rejected", generation)
            OutboxState.PENDING, OutboxState.FAILED -> setDelivery(key, RemoteOutcome.Pending, generation)
        }
    }

    private fun failLocal(key: EvidenceKey, reason: String, generation: Long = generationCounter) {
        if (!isCurrent(generation)) return
        optimistic.remove(key)
        val restored = confirmed[key] ?: PersonalRatingState.Unknown
        publish(key, restored, generation)
        setDelivery(key, RemoteOutcome.Failed(reason), generation)
    }

    private fun publish(key: EvidenceKey, state: PersonalRatingState, generation: Long = generationCounter) {
        if (!isCurrent(generation)) return
        if (_states.value[key] == state) return
        _states.value = _states.value + (key to state)
        bump()
    }

    private fun setDelivery(key: EvidenceKey, outcome: RemoteOutcome, generation: Long = generationCounter) {
        if (!isCurrent(generation)) return
        if (_deliveries.value[key] == outcome) return
        _deliveries.value = _deliveries.value + (key to outcome)
        bump()
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun scopeKey(providerId: String, account: String?, generation: Long): String =
        if (account != null) "$providerId|$account" else "$providerId|conn:$generation"

    private fun ratingKeyPrefix(tracker: String, account: String?, unit: RatingUnit): String =
        "rating:$tracker:${account ?: "-"}:${unit.kind.name}:${unit.titleId}:" +
            "${unit.seasonNumber ?: "-"}:${unit.episodeNumber ?: "-"}:"

    private fun ratingKey(
        tracker: String,
        account: String?,
        unit: RatingUnit,
        at: Instant,
    ): String = ratingKeyPrefix(tracker, account, unit) + at.toEpochMilli()

    private companion object {

        val LIBRARY_TTL: Duration = Duration.ofHours(12)

        const val MAX_PAGES = 5
    }
}

internal fun formatPersonalRatingValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
