package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.metadata.CanonicalShowHierarchy
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.domain.upnext.UpNextCacheStore
import dev.infusion.core.domain.upnext.UpNextCachedAnswer
import dev.infusion.core.domain.upnext.UpNextEpisode
import dev.infusion.core.domain.upnext.UpNextPresentationStore
import dev.infusion.core.domain.upnext.UpNextProjection
import dev.infusion.core.domain.upnext.UpNextReadRequest
import dev.infusion.core.domain.upnext.UpNextSelection
import dev.infusion.core.domain.upnext.UpNextSelectionStore
import dev.infusion.core.domain.upnext.UpNextSeriesEvidence
import dev.infusion.core.domain.upnext.UpNextSource
import dev.infusion.core.domain.upnext.UpNextSourceResolution
import dev.infusion.core.domain.upnext.UpNextSourceRule
import dev.infusion.core.domain.upnext.UpNextTrackerCandidate
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import dev.infusion.core.domain.watch.WatchStateRepository
import dev.infusion.core.model.EpisodeCardIdentification
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchStatus
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class HomeUpNextStateHolder(

    private val watchStates: WatchStateRepository,

    private val hierarchy: CanonicalShowHierarchy?,

    private val identity: TrackingInboundIdentityPort?,

    private val identityCompletion: TrackingInboundIdentityCompletionPort? = null,
    private val selectionStore: UpNextSelectionStore,
    private val presentationStore: UpNextPresentationStore,
    private val scope: CoroutineScope,
    private val activeProfileId: () -> String,

    private val availableTrackers: () -> List<UpNextTrackerRef>,

    private val accountLabelFor: (trackerId: String) -> String?,

    private val readUpNext: suspend (trackerId: String, request: UpNextReadRequest) -> TrackingInboundOutcome<UpNextTrackerPage>,

    private val enrich: suspend (UpNextCardRequest) -> UpNextCardEnrichment = { UpNextCardEnrichment() },
    private val cacheStore: UpNextCacheStore? = null,

    private val presentationRetention: UpNextPresentationRetention? = null,

    private val trace: (String) -> Unit = {},
    private val today: () -> LocalDate = LocalDate::now,

    private val now: () -> Instant = Instant::now,

    private val presentationWindow: HomePresentationWindow? = null,

    private val playbackActive: StateFlow<Boolean> = MutableStateFlow(false),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(HomeUpNextUiState())
    val state: StateFlow<HomeUpNextUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private data class LoadKey(
        val profileId: String,
        val source: UpNextSource,
        val accountLabel: String?,
    )

    private var loadKey: LoadKey? = null

    private var publishedKey: LoadKey? = null

    private val enriched: java.util.concurrent.ConcurrentHashMap<String, UpNextCardEnrichment> =
        java.util.concurrent.ConcurrentHashMap()

    private val resolvedThisVisit: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var currentCards: List<dev.infusion.core.domain.upnext.UpNextCard> = emptyList()

    private var presentJob: Job? = null

    private var presentingVisit: LoadKey? = null

    private val publicationTick = MutableStateFlow(0L)

    private val enrichGate: Semaphore = Semaphore(MAX_CONCURRENT_ENRICHMENTS)

    private val publishMutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex()

    fun reportPresentationWindow(materialized: Int) = presentationWindow?.report(materialized)

    private val surfacePresent = MutableStateFlow(true)

    @Volatile
    private var refreshPending = false

    private fun browsingWorkAllowed(): Boolean = surfacePresent.value && !playbackActive.value

    init {

        scope.launch {
            combine(surfacePresent, playbackActive) { present, playing -> present && !playing }
                .distinctUntilChanged()
                .collectLatest { allowed ->
                    if (!allowed) {

                        if (loadJob?.isActive == true || presentJob?.isActive == true) refreshPending = true
                        loadJob?.cancel()
                        presentJob?.cancel()
                        return@collectLatest
                    }
                    if (refreshPending) {
                        refreshPending = false
                        refreshNow()
                    }
                }
        }
    }

    fun onSurfacePresent() {
        surfacePresent.value = true
    }

    fun onSurfaceAbsent() {
        surfacePresent.value = false
    }

    fun refresh() {

        if (!browsingWorkAllowed()) {
            refreshPending = true
            trace("refresh deferred gate-closed")
            return
        }
        refreshNow()
    }

    private fun refreshNow() {
        val key = loadKeyOrNull()
        if (key != null && loadJob?.isActive == true && key == loadKey) {
            trace("refresh skipped partition-unchanged")
            return
        }
        loadJob?.cancel()
        presentJob?.cancel()
        loadKey = key
        loadJob = scope.launch { load() }
    }

    private fun loadKeyOrNull(): LoadKey? {
        val profileId = runCatching { activeProfileId() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        if (!runCatching { presentationStore.showUpNext(profileId) }.getOrDefault(true)) {

            return LoadKey(profileId, UpNextSource.LocalOnly, null)
        }
        val trackers = runCatching { availableTrackers() }.getOrDefault(emptyList()).map { it.trackerId }
        val resolution = UpNextSourceRule.resolve(
            runCatching { selectionStore.read(profileId) }.getOrDefault(UpNextSelection.Unset),
            trackers,
        )
        return LoadKey(profileId, resolution.effective, accountFor(resolution.effective))
    }

    private fun accountFor(source: UpNextSource): String? =
        (source as? UpNextSource.Tracker)?.let { tracker ->
            runCatching { accountLabelFor(tracker.trackerId) }.getOrNull()
        }

    private suspend fun load() {
        val startedAt = System.currentTimeMillis()
        val profileId = runCatching { activeProfileId() }.getOrNull()
        if (profileId.isNullOrBlank()) {
            _state.value = HomeUpNextUiState(row = UpNextRowState.Hidden)
            return
        }
        if (!runCatching { presentationStore.showUpNext(profileId) }.getOrDefault(true)) {

            _state.value = HomeUpNextUiState(row = UpNextRowState.Hidden)
            return
        }
        val trackers = runCatching { availableTrackers() }.getOrDefault(emptyList())
        val resolution = resolveSource(profileId, trackers)

        publish(UpNextRowState.Loading, profileId, resolution)
        trace("start source=${sourceLabel(resolution)}")

        val ledgerMs = System.currentTimeMillis()
        val ledger = withContext(ioDispatcher) { ledgerStates() }
        trace("ledger series=${ledger.size} ms=${System.currentTimeMillis() - ledgerMs}")
        val ledgerEvidence = ledgerEvidence(ledger)

        val tracker = resolution.effective as? UpNextSource.Tracker
        val answer = if (tracker != null) {
            readTrackerAnswer(profileId, tracker, resolution, ledgerEvidence, startedAt)
        } else {
            null
        }

        val visit = loadKey
        val localMs = System.currentTimeMillis()
        val evidenceBySeries = LinkedHashMap<TitleId, UpNextSeriesEvidence>()
        ledgerEvidence.forEach { evidenceBySeries[it.seriesTitleId] = it }
        var hydratedSeries = 0

        var published: List<dev.infusion.core.domain.upnext.UpNextCard>? = null
        val trackerCandidates = answer?.candidates.orEmpty()

        val orderedLedger = ledger.entries.sortedByDescending { it.value.activityAt ?: Instant.EPOCH }
        for ((seriesId, ledgerSeries) in orderedLedger) {
            if (loadKey != visit) return
            val evidence = withContext(ioDispatcher) { localEvidenceFor(seriesId, ledgerSeries) }
            if (evidence != null) {
                evidenceBySeries[seriesId] = evidence
                hydratedSeries++
            }
            val cards = UpNextProjection
                .project(evidenceBySeries.values.toList(), trackerCandidates, today(), now()).cards
            if (cards != published && publishCards(cards, profileId, resolution, label = "progress", startedAt = startedAt)) {
                published = cards
            }
        }
        val local = evidenceBySeries.values.toList()
        trace(
            "local evidence series=${local.size} hydrated=$hydratedSeries " +
                "ms=${System.currentTimeMillis() - localMs}",
        )

        if (answer == null) {

            UpNextProjection.project(local, emptyList(), today(), now()).cards.let { cards ->
                publishCards(cards, profileId, resolution, label = "local", startedAt = startedAt, authoritative = true)
            }
            trace(
                "done source=local-only row=${_state.value.row::class.simpleName} " +
                    "total_ms=${System.currentTimeMillis() - startedAt}",
            )
            return
        }
        val projected = UpNextProjection.project(local, answer.candidates, today(), now())
        val projectedCards = projected.cards

        val unresolvedByReason = projected.unresolved
            .groupingBy { it.reason }
            .eachCount()
            .entries
            .joinToString(",") { (reason, count) -> "$reason:$count" }
            .ifEmpty { "NONE" }
        trace(
            "projected cards=${projectedCards.size} " +
                "screened_out=$unresolvedByReason " +
                "total_ms=${System.currentTimeMillis() - startedAt}",
        )
        val row = when {
            projectedCards.isNotEmpty() -> {

                publishCards(
                    projectedCards,
                    profileId,
                    resolution,
                    label = "projected",
                    startedAt = startedAt,
                    authoritative = true,
                )
                UpNextRowState.Loaded(projectedCards.map { card -> toRowCard(card, enrichmentFor(card)) })
            }

            !answer.resolved && hydratedSeries == 0 -> UpNextRowState.Failed(
                answer.note ?: "Could not load Up Next.",
            )
            else -> UpNextRowState.Empty
        }

        publish(
            row,
            profileId,
            resolution,
            note = if (projectedCards.isNotEmpty()) answer.note else null,
        )
        trace("done row=${row::class.simpleName} total_ms=${System.currentTimeMillis() - startedAt}")
    }

    private fun resolveSource(profileId: String, trackers: List<UpNextTrackerRef>): UpNextSourceResolution {
        val recorded = runCatching { selectionStore.read(profileId) }.getOrDefault(UpNextSelection.Unset)
        val resolution = UpNextSourceRule.resolve(recorded, trackers.map { it.trackerId })
        resolution.adopted?.let { adopted ->

            runCatching { selectionStore.write(profileId, UpNextSelection(adopted)) }
        }
        return resolution
    }

    private fun publish(
        row: UpNextRowState,
        profileId: String,
        resolution: UpNextSourceResolution,
        note: String? = null,
    ) {
        val key = LoadKey(profileId, resolution.effective, accountFor(resolution.effective))
        val current = _state.value
        val keepVisible = row === UpNextRowState.Loading &&
            key == publishedKey &&
            current.row is UpNextRowState.Loaded
        val effectiveRow = if (keepVisible) current.row else row
        publishedKey = key
        _state.value = HomeUpNextUiState(
            row = effectiveRow,
            source = resolution.effective,
            sourceLabel = sourceLabel(resolution),
            unavailableSourceNote = unavailableNote(resolution),
            note = if (keepVisible) current.note else note,
            initialFocusKey = (effectiveRow as? UpNextRowState.Loaded)?.cards?.firstOrNull()?.key,
        )
    }

    private suspend fun episodesOf(hierarchy: CanonicalShowHierarchy, seriesId: TitleId): List<UpNextEpisode> {
        val seasons = hierarchy.seasonNumbers(seriesId).filter { it > 0 }.sorted()
        val episodes = ArrayList<UpNextEpisode>()
        for (season in seasons) {
            hierarchy.episodes(seriesId, season).forEach { episode ->
                if (episode.seasonNumber <= 0) return@forEach
                val release = parseRelease(episode.released)
                episodes += UpNextEpisode(
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitleId = null,
                    title = episode.title?.takeIf { it.isNotBlank() },
                    airedAt = release.first,
                    airedAtProvenAt = release.second,
                )
            }
        }
        return episodes
    }

    private fun parseRelease(raw: String?): Pair<LocalDate?, Instant?> =
        dev.infusion.core.domain.watch.EpisodeReleaseEvidence.parse(raw)

    private data class TrackerAnswer(
        val candidates: List<UpNextTrackerCandidate>,
        val note: String?,

        val resolved: Boolean,
    )

    private suspend fun readTrackerAnswer(
        profileId: String,
        tracker: UpNextSource.Tracker,
        resolution: UpNextSourceResolution,
        earlyEvidence: List<UpNextSeriesEvidence>,
        startedAt: Long,
    ): TrackerAnswer {
        val account = accountFor(resolution.effective)
        val cached = cacheStore?.let { store ->
            runCatching { store.read(profileId, tracker.trackerId, account) }.getOrNull()
        }
        publishCandidates(
            profileId = profileId,
            resolution = resolution,
            evidence = earlyEvidence,
            candidates = cached?.candidates.orEmpty(),
            label = "cached",
            startedAt = startedAt,
        )
        val readMs = System.currentTimeMillis()
        val outcome = withContext(ioDispatcher) { readTracker(tracker.trackerId) }
        trace("read outcome=${outcome::class.simpleName} ms=${System.currentTimeMillis() - readMs}")
        val (candidates, note) = when (outcome) {
            is TrackerRead.Resolved -> {

                cacheStore?.let { store ->
                    runCatching {
                        store.write(
                            UpNextCachedAnswer(
                                profileId = profileId,
                                trackerId = tracker.trackerId,
                                accountLabel = account,
                                fetchedAt = Instant.now(),
                                candidates = outcome.candidates,
                            ),
                        )
                        store.clearOtherAccounts(profileId, tracker.trackerId, account)
                    }
                }
                outcome.candidates to null
            }
            is TrackerRead.Revoked -> {

                cacheStore?.let { store ->
                    runCatching { store.clearAccount(profileId, tracker.trackerId, account) }
                }
                emptyList<UpNextTrackerCandidate>() to outcome.reason
            }
            is TrackerRead.Unavailable -> {

                cached?.candidates.orEmpty() to outcome.reason
            }
        }
        if (outcome is TrackerRead.Resolved) {
            publishCandidates(
                profileId = profileId,
                resolution = resolution,
                evidence = earlyEvidence,
                candidates = candidates,
                label = "tracker",
                startedAt = startedAt,
            )
        }
        return TrackerAnswer(candidates, note, outcome is TrackerRead.Resolved)
    }

    private fun publishCandidates(
        profileId: String,
        resolution: UpNextSourceResolution,
        evidence: List<UpNextSeriesEvidence>,
        candidates: List<UpNextTrackerCandidate>,
        label: String,
        startedAt: Long,
    ) {
        if (candidates.isEmpty()) return
        val cards = UpNextProjection.project(evidence, candidates, today(), now()).cards
        if (cards.isEmpty()) return
        publishCards(cards, profileId, resolution, label = label, startedAt = startedAt)
    }

    private data class LedgerSeries(
        val watchStates: Map<WatchKey, WatchStatus>,
        val activityAt: Instant? = null,
    )

    private suspend fun ledgerStates(): Map<TitleId, LedgerSeries> {
        val states = runCatching { watchStates.observeAll().first() }.getOrDefault(emptyList())
        val bySeries = linkedMapOf<TitleId, MutableMap<WatchKey, WatchStatus>>()
        val activity = mutableMapOf<TitleId, Instant>()
        states.forEach { state ->
            val key = state.watchKey
            val seriesId = key.seriesId ?: return@forEach
            if (key.seasonNumber == null || key.episodeNumber == null || key.seasonNumber == 0) return@forEach
            when (state.status) {
                WatchStatus.COMPLETED, WatchStatus.IN_PROGRESS -> Unit
                else -> return@forEach
            }
            bySeries.getOrPut(seriesId) { mutableMapOf() }[key] = state.status
            state.lastMeaningfulAt?.let { at ->
                val current = activity[seriesId]
                if (current == null || at.isAfter(current)) activity[seriesId] = at
            }
        }
        return bySeries.mapValues { (seriesId, watched) ->
            LedgerSeries(watchStates = watched, activityAt = activity[seriesId])
        }
    }

    private fun ledgerEvidence(states: Map<TitleId, LedgerSeries>): List<UpNextSeriesEvidence> =
        states.map { (seriesId, ledger) ->
            UpNextSeriesEvidence(
                seriesTitleId = seriesId,
                seriesTitle = null,
                episodes = ledger.watchStates.keys.mapNotNull { key ->
                    val season = key.seasonNumber ?: return@mapNotNull null
                    val episode = key.episodeNumber ?: return@mapNotNull null
                    UpNextEpisode(seasonNumber = season, episodeNumber = episode)
                },
                watchStates = ledger.watchStates,
                activityAt = ledger.activityAt,
            )
        }

    private suspend fun localEvidenceFor(seriesId: TitleId, ledger: LedgerSeries): UpNextSeriesEvidence? {
        val hierarchy = this.hierarchy ?: return null
        val episodes = runCatching { episodesOf(hierarchy, seriesId) }.getOrNull().orEmpty()
        if (episodes.isEmpty()) return null
        return UpNextSeriesEvidence(
            seriesTitleId = seriesId,
            seriesTitle = null,
            episodes = episodes,
            watchStates = ledger.watchStates,
            activityAt = ledger.activityAt,
        )
    }

    private suspend fun readTracker(trackerId: String): TrackerRead {
        val identity = this.identity ?: return TrackerRead.Unavailable(UNAVAILABLE_IDENTITY)
        val candidates = ArrayList<UpNextTrackerCandidate>()

        var pagesRead = 0
        var records = 0
        var pageMalformed = 0
        var pageSkipped = 0
        var unproven = 0
        var ambiguous = 0
        var completedBySeam = 0
        var capReached = false
        var page = 1
        while (page <= MAX_PAGES) {
            val outcome = runCatching {
                readUpNext(trackerId, UpNextReadRequest(page = page, limit = UpNextReadRequest.DEFAULT_LIMIT))
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                return TrackerRead.Unavailable(UNAVAILABLE_TRANSPORT)
            }
            when (outcome) {
                is TrackingInboundOutcome.Ok -> {
                    pagesRead++
                    records += outcome.value.records.size
                    pageMalformed += outcome.value.malformed
                    pageSkipped += outcome.value.skipped
                    outcome.value.records.forEach { record ->
                        when (val resolution = resolve(identity, trackerId, record)) {
                            is Resolution.Resolved -> {
                                candidates += resolution.candidate
                                if (resolution.completed) completedBySeam++
                            }
                            is Resolution.Unproven -> unproven++
                            is Resolution.Ambiguous -> ambiguous++
                        }
                    }
                    val truncated = outcome.value.truncated
                    trace(
                        "tracker page=$page records=${outcome.value.records.size} " +
                            "resolved=${candidates.size} unproven=$unproven ambiguous=$ambiguous " +
                            "malformed=${outcome.value.malformed} skipped=${outcome.value.skipped} " +
                            "truncated=$truncated",
                    )
                    if (!truncated) {
                        traceBoundary(
                            pagesRead, records, candidates.size, unproven, ambiguous,
                            pageMalformed, pageSkipped, completedBySeam, capReached = false,
                        )
                        return TrackerRead.Resolved(candidates)
                    }
                    if (page == MAX_PAGES) capReached = true
                    page++
                }
                is TrackingInboundOutcome.Unauthorized ->
                    return TrackerRead.Revoked(outcome.reason)
                is TrackingInboundOutcome.Unavailable ->
                    return TrackerRead.Unavailable(outcome.reason)
                is TrackingInboundOutcome.Rejected ->
                    return TrackerRead.Unavailable(outcome.reason)
            }
        }
        traceBoundary(
            pagesRead, records, candidates.size, unproven, ambiguous,
            pageMalformed, pageSkipped, completedBySeam, capReached,
        )
        return TrackerRead.Resolved(candidates)
    }

    private fun traceBoundary(
        pages: Int,
        records: Int,
        resolved: Int,
        unproven: Int,
        ambiguous: Int,
        malformed: Int,
        skippedNoStart: Int,
        completedBySeam: Int,
        capReached: Boolean,
    ) {
        trace(
            "tracker boundary pages=$pages records=$records resolved=$resolved " +
                "identity_completed=$completedBySeam unproven=$unproven ambiguous=$ambiguous " +
                "malformed=$malformed skipped_no_start=$skippedNoStart cap_reached=$capReached",
        )
    }

    private sealed interface Resolution {
        data class Resolved(val candidate: UpNextTrackerCandidate, val completed: Boolean = false) : Resolution
        data object Unproven : Resolution
        data object Ambiguous : Resolution
    }

    private suspend fun resolve(
        identity: TrackingInboundIdentityPort,
        trackerId: String,
        record: dev.infusion.core.domain.upnext.UpNextTrackerRecord,
    ): Resolution {
        val season = record.seasonNumber ?: return Resolution.Unproven
        val episode = record.episodeNumber ?: return Resolution.Unproven
        val lookup = identity.lookUp(
            ids = record.externalIds,
            kind = dev.infusion.core.domain.tracking.TrackingMediaKind.SHOW,
            seasonNumber = season,
            episodeNumber = episode,
        )
        var completedIdentity = false
        val watchKey = when (lookup) {
            is TrackingWatchKeyLookup.Resolved -> lookup.watchKey
            is TrackingWatchKeyLookup.Ambiguous -> return Resolution.Ambiguous
            is TrackingWatchKeyLookup.Unproven -> {

                val completed = identityCompletion?.complete(
                    ids = record.externalIds,
                    kind = dev.infusion.core.domain.tracking.TrackingMediaKind.SHOW,
                    seasonNumber = season,
                    episodeNumber = episode,
                    title = record.seriesTitle,
                    year = record.year,
                )
                if (completed !is TrackingWatchKeyLookup.Resolved) return Resolution.Unproven
                completedIdentity = true
                completed.watchKey
            }
        }
        val seriesId = watchKey.seriesId ?: return Resolution.Unproven
        return Resolution.Resolved(
            UpNextTrackerCandidate(
                trackerId = trackerId,
                seriesTitleId = seriesId,
                seasonNumber = watchKey.seasonNumber ?: season,
                episodeNumber = watchKey.episodeNumber ?: episode,
                episodeTitleId = null,
                episodeTitle = record.episodeTitle,
                seriesTitle = record.seriesTitle,
                activityAt = record.activityAt,

                airedAt = record.airedAt,
                airedAtProvenAt = record.airedAtProvenAt,
            ),
            completed = completedIdentity,
        )
    }

    private fun publishCards(
        cards: List<dev.infusion.core.domain.upnext.UpNextCard>,
        profileId: String,
        resolution: UpNextSourceResolution,
        label: String,
        startedAt: Long,
        authoritative: Boolean = false,
    ): Boolean {
        if (cards.isEmpty()) return false
        if (!authoritative && wouldDropShownSeries(cards, profileId, resolution)) {

            trace(
                "published $label withheld-shrink cards=${cards.size} " +
                    "ms=${System.currentTimeMillis() - startedAt}",
            )
            return false
        }
        currentCards = cards

        seedRetained(profileId, cards)
        val row = cards.map { card -> toRowCard(card, enrichmentFor(card)) }
        publish(UpNextRowState.Loaded(row), profileId, resolution)
        trace(
            "published $label cards=${row.size} " +
                "artwork=${row.count { it.preview.artwork != null }} " +
                "ms=${System.currentTimeMillis() - startedAt}",
        )
        startPresentation(profileId, resolution, startedAt)
        return true
    }

    private fun wouldDropShownSeries(
        cards: List<dev.infusion.core.domain.upnext.UpNextCard>,
        profileId: String,
        resolution: UpNextSourceResolution,
    ): Boolean {
        val key = LoadKey(profileId, resolution.effective, accountFor(resolution.effective))
        if (key != publishedKey) return false
        val shown = (_state.value.row as? UpNextRowState.Loaded)?.cards ?: return false
        val next = cards.map { it.seriesTitleId }.toSet()
        return shown.any { it.seriesTitleId !in next }
    }

    private fun startPresentation(
        profileId: String,
        resolution: UpNextSourceResolution,
        startedAt: Long,
    ) {
        val visit = loadKey
        if (presentJob?.isActive == true && presentingVisit == visit) {

            publicationTick.update { it + 1 }
            return
        }
        presentJob?.cancel()
        presentingVisit = visit

        resolvedThisVisit.clear()
        val cards = currentCards
        seedRetained(profileId, cards)

        val presentedCount = presentationWindow?.count?.value ?: Int.MAX_VALUE
        val missing = cards.withIndex().count { (index, card) ->
            needsPresentation(card.key) && index < presentedCount
        }
        val beyondWindow = cards.withIndex().count { (index, card) ->
            needsPresentation(card.key) && index >= presentedCount
        }

        trace(
            "present cards=${cards.size} missing=$missing " +
                "retained=${cards.size - missing - beyondWindow} beyondWindow=$beyondWindow",
        )

        if (missing == 0 && beyondWindow == 0) return
        val window = presentationWindow
        presentJob = scope.launch {
            while (loadKey == visit) {

                val publishedAtRoundStart = publicationTick.value
                val inWindow = currentCards.withIndex().filter { (index, card) ->
                    needsPresentation(card.key) && (window == null || index < window.count.value)
                }
                if (inWindow.isEmpty()) {

                    if (window == null) break
                    val presented = window.count.value
                    combine(window.count, publicationTick) { count, tick ->
                        count > presented || tick > publishedAtRoundStart
                    }.first { it }
                    continue
                }
                coroutineScope {
                    inWindow.forEach { (_, card) ->
                        launch(ioDispatcher) {
                            if (!needsPresentation(card.key)) return@launch

                            val result = enrichGate.withPermit {
                                runCatching {
                                    enrich(
                                        UpNextCardRequest(
                                            card.seriesTitleId,
                                            card.seasonNumber,
                                            card.episodeNumber,
                                        ),
                                    )
                                }.getOrDefault(UpNextCardEnrichment())
                            }

                            val merged = enriched.merge(card.key, result) { held, fresh ->
                                fresh?.mergedOver(held ?: UpNextCardEnrichment()) ?: result
                            } ?: result

                            resolvedThisVisit.add(card.key)

                            if (merged.isNotEmpty()) {
                                presentationRetention?.put(profileId, card.key, merged)
                            }
                            publishMutex.withLock {

                                if (loadKey != visit) return@withLock
                                republish(profileId, resolution)
                                trace(
                                    "presented cards=${currentCards.size} " +
                                        "artwork=${currentCards.count { enrichmentFor(it).artwork != null }} " +
                                        "ms=${System.currentTimeMillis() - startedAt}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun republish(profileId: String, resolution: UpNextSourceResolution) {
        val cards = currentCards
        if (cards.isEmpty()) return
        val current = _state.value
        val shown = current.row as? UpNextRowState.Loaded ?: return
        if (shown.cards.map { it.key } != cards.map { it.key }) return
        publish(
            UpNextRowState.Loaded(cards.map { card -> toRowCard(card, enrichmentFor(card)) }),
            profileId,
            resolution,
            note = current.note,
        )
    }

    private fun enrichmentFor(
        card: dev.infusion.core.domain.upnext.UpNextCard,
    ): UpNextCardEnrichment = enriched[card.key] ?: UpNextCardEnrichment()

    private fun needsPresentation(cardKey: String): Boolean =
        cardKey !in resolvedThisVisit && enriched[cardKey]?.settlesArtwork != true

    private fun seedRetained(
        profileId: String,
        cards: List<dev.infusion.core.domain.upnext.UpNextCard>,
    ) {
        val retention = presentationRetention ?: return
        cards.forEach { card ->
            if (!enriched.containsKey(card.key)) {
                retention.get(profileId, card.key)?.let { retained -> enriched[card.key] = retained }
            }
        }
    }

    private fun toRowCard(
        card: dev.infusion.core.domain.upnext.UpNextCard,
        enrichment: UpNextCardEnrichment,
    ): UpNextRowCard {
        val seriesTitle = enrichment.seriesTitle
            ?: card.seriesTitle
            ?: card.episodeTitle
            ?: "Up Next"
        val episodeTitle = enrichment.episodeTitle ?: card.episodeTitle
        return UpNextRowCard(
            key = card.key,
            seriesTitleId = card.seriesTitleId,
            seasonNumber = card.seasonNumber,
            episodeNumber = card.episodeNumber,
            episodeTitleId = card.episodeTitleId ?: enrichment.episodeTitleId,
            preview = MediaPreview(
                titleId = card.seriesTitleId,
                kind = dev.infusion.core.model.TitleKind.EPISODE,

                displayTitle = seriesTitle,

                watchKey = dev.infusion.core.model.watch.WatchKey.episode(
                    card.seriesTitleId,
                    card.seasonNumber,
                    card.episodeNumber,
                ),
                year = null,

                airDate = enrichment.airDate,
                subtitle = episodeIdentity(card),

                artwork = enrichment.artwork,

                fallbackArtwork = enrichment.parentBackdrop,

                progressFraction = null,

                episodeIdentification = EpisodeCardIdentification(
                    seriesContext = seriesTitle,
                    coordinate = episodeIdentity(card),
                    episodeTitle = episodeTitle,
                    runtimeMinutes = enrichment.runtimeMinutes,
                    airDate = enrichment.airDate,
                ),

                episodeTitleId = card.episodeTitleId ?: enrichment.episodeTitleId,
            ),
            episodeLabel = episodeIdentity(card),
            episodeTitle = card.episodeTitle,

            provenanceLabel = card.trackerId?.let { trackerId -> enrichment.trackerName ?: trackerId },
        )
    }

    private fun episodeIdentity(card: dev.infusion.core.domain.upnext.UpNextCard): String =
        "S${card.seasonNumber}E${card.episodeNumber}"

    private fun sourceLabel(resolution: UpNextSourceResolution): String = when (val source = resolution.effective) {
        UpNextSource.LocalOnly -> LOCAL_ONLY_LABEL
        is UpNextSource.Tracker -> source.trackerId
    }

    private fun unavailableNote(resolution: UpNextSourceResolution): String? =
        resolution.unavailableTrackerId?.let { trackerId ->

            "The $trackerId account is not connected. Showing local progress; your selection is kept."
        }

    private sealed interface TrackerRead {
        data class Resolved(val candidates: List<UpNextTrackerCandidate>) : TrackerRead

        data class Revoked(val reason: String) : TrackerRead

        data class Unavailable(val reason: String) : TrackerRead
    }

    private companion object {

        const val MAX_PAGES: Int = 10

        const val MAX_CONCURRENT_ENRICHMENTS: Int = 6

        const val LOCAL_ONLY_LABEL: String = "Local only"
        const val UNAVAILABLE_IDENTITY: String = "identity unavailable"
        const val UNAVAILABLE_TRANSPORT: String = "provider unavailable"
    }
}

data class UpNextTrackerRef(val trackerId: String, val displayName: String)

data class UpNextCardRequest(
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

data class UpNextCardEnrichment(
    val seriesTitle: String? = null,
    val episodeTitleId: TitleId? = null,
    val artwork: MediaArtworkRef? = null,
    val trackerName: String? = null,

    val episodeTitle: String? = null,

    val runtimeMinutes: Int? = null,

    val airDate: LocalDate? = null,

    val parentBackdrop: MediaArtworkRef? = null,
) {

    fun isNotEmpty(): Boolean =
        seriesTitle != null || episodeTitleId != null || artwork != null || trackerName != null ||
            episodeTitle != null || runtimeMinutes != null || airDate != null ||
            parentBackdrop != null

    val settlesArtwork: Boolean get() = artwork != null

    fun mergedOver(retained: UpNextCardEnrichment): UpNextCardEnrichment = UpNextCardEnrichment(
        seriesTitle = seriesTitle ?: retained.seriesTitle,
        episodeTitleId = episodeTitleId ?: retained.episodeTitleId,
        artwork = artwork ?: retained.artwork,
        trackerName = trackerName ?: retained.trackerName,
        episodeTitle = episodeTitle ?: retained.episodeTitle,
        runtimeMinutes = runtimeMinutes ?: retained.runtimeMinutes,
        airDate = airDate ?: retained.airDate,
        parentBackdrop = parentBackdrop ?: retained.parentBackdrop,
    )
}

interface UpNextPresentationRetention {

    fun get(profileId: String, cardKey: String): UpNextCardEnrichment?

    fun put(profileId: String, cardKey: String, enrichment: UpNextCardEnrichment)
}

data class UpNextRowCard(

    val key: String,
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitleId: TitleId?,
    val preview: MediaPreview,

    val episodeLabel: String,
    val episodeTitle: String?,

    val provenanceLabel: String?,
)

sealed interface UpNextRowState {
    data object Hidden : UpNextRowState
    data object Loading : UpNextRowState
    data object Empty : UpNextRowState
    data class Loaded(val cards: List<UpNextRowCard>) : UpNextRowState
    data class Failed(val message: String) : UpNextRowState
}

data class HomeUpNextUiState(
    val row: UpNextRowState = UpNextRowState.Loading,
    val source: UpNextSource = UpNextSource.LocalOnly,
    val sourceLabel: String = "Local only",

    val unavailableSourceNote: String? = null,

    val note: String? = null,
    val initialFocusKey: String? = null,
)
