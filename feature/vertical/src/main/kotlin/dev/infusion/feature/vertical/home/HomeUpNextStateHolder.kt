package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.metadata.CanonicalShowHierarchy
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.domain.upnext.UpNextAnswerReuse
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
import kotlinx.coroutines.async
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

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

    private val episodeEvidenceRetention: UpNextEpisodeEvidenceRetention =
        InMemoryUpNextEpisodeEvidenceRetention.Shared,

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

    @Volatile
    private var loadKey: LoadKey? = null

    @Volatile
    private var publishedKey: LoadKey? = null

    private val enriched: java.util.concurrent.ConcurrentHashMap<String, UpNextCardEnrichment> =
        java.util.concurrent.ConcurrentHashMap()

    private val heldAnswers: java.util.concurrent.ConcurrentHashMap<LoadKey, TrackerAnswer> =
        java.util.concurrent.ConcurrentHashMap()

    private val resolvedThisVisit: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    private var currentCards: List<dev.infusion.core.domain.upnext.UpNextCard> = emptyList()

    private var presentJob: Job? = null

    @Volatile
    private var presentingVisit: LoadKey? = null

    private val publicationTick = MutableStateFlow(0L)

    private val enrichGate: Semaphore = Semaphore(MAX_CONCURRENT_ENRICHMENTS)

    private val publishMutex: java.util.concurrent.locks.ReentrantLock =
        java.util.concurrent.locks.ReentrantLock()

    private val attribution = UpNextAttribution()

    private class UpNextAttribution {

        var ledgerDispatchMs: Long = 0
        var ledgerFlowMs: Long = 0
        var ledgerGroupMs: Long = 0
        var ledgerEvidenceMs: Long = 0
        var ledgerRows: Int = 0

        var seriesDispatchMs: Long = 0
        var hydrateResumeMs: Long = 0
        var retentionGetMs: Long = 0
        var sufficiencyMs: Long = 0
        var sufficiencyChecks: Int = 0
        var episodesBuildMs: Long = 0
        var retentionPutMs: Long = 0
        var snapshotMs: Long = 0
        var snapshotBuilds: Int = 0
        var orderMs: Long = 0
        var orderRuns: Int = 0
        var decisionCalls: Int = 0

        var seedLookups: Int = 0
        var seedHits: Int = 0
        var seedLookupMs: Long = 0

        var pubAttempts: Int = 0
        var pubWithheld: Int = 0
        var pubEmitted: Int = 0
        var pubSameVisible: Int = 0
        var pubMainThread: Int = 0
        var pubAddedKeys: Int = 0
        var pubRemovedKeys: Int = 0
        var pubReplacedKeys: Int = 0
        var pubWouldDropMs: Long = 0
        var pubSeedMs: Long = 0
        var pubRowBuildMs: Long = 0
        var pubEmitMs: Long = 0
        var pubWakeMs: Long = 0
        var rowCardsBuilt: Int = 0
        var rowCardsReused: Int = 0

        val enrichIssued: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger()
        val enrichMs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong()
        val presentWakes: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger()

        fun reset() {
            ledgerDispatchMs = 0
            ledgerFlowMs = 0
            ledgerGroupMs = 0
            ledgerEvidenceMs = 0
            ledgerRows = 0
            seriesDispatchMs = 0
            hydrateResumeMs = 0
            retentionGetMs = 0
            sufficiencyMs = 0
            sufficiencyChecks = 0
            episodesBuildMs = 0
            retentionPutMs = 0
            snapshotMs = 0
            snapshotBuilds = 0
            orderMs = 0
            orderRuns = 0
            decisionCalls = 0
            seedLookups = 0
            seedHits = 0
            seedLookupMs = 0
            pubAttempts = 0
            pubWithheld = 0
            pubEmitted = 0
            pubSameVisible = 0
            pubMainThread = 0
            pubAddedKeys = 0
            pubRemovedKeys = 0
            pubReplacedKeys = 0
            pubWouldDropMs = 0
            pubSeedMs = 0
            pubRowBuildMs = 0
            pubEmitMs = 0
            pubWakeMs = 0
            rowCardsBuilt = 0
            rowCardsReused = 0
            enrichIssued.set(0)
            enrichMs.set(0)
            presentWakes.set(0)
        }
    }

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
        heldAnswers.clear()
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
        attribution.reset()

        seedAsked.clear()
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

        val ledgerAt = System.nanoTime()
        val ledgerRead = withContext(ioDispatcher) { ledgerStates() }
        val ledger = ledgerRead.series
        val ledgerWallMs = (System.nanoTime() - ledgerAt) / 1_000_000
        val evidenceAt = System.nanoTime()
        val ledgerEvidence = ledgerEvidence(ledger)
        attribution.ledgerDispatchMs = ledgerWallMs - ledgerRead.flowMs - ledgerRead.groupMs
        attribution.ledgerFlowMs = ledgerRead.flowMs
        attribution.ledgerGroupMs = ledgerRead.groupMs
        attribution.ledgerEvidenceMs = (System.nanoTime() - evidenceAt) / 1_000_000
        attribution.ledgerRows = ledgerRead.rows
        trace(
            "ledger series=${ledger.size} ms=$ledgerWallMs rows=${ledgerRead.rows} " +
                "dispatch_ms=${attribution.ledgerDispatchMs} flow_ms=${ledgerRead.flowMs} " +
                "group_ms=${ledgerRead.groupMs} evidence_ms=${attribution.ledgerEvidenceMs}",
        )

        coroutineScope { loadVisit(profileId, resolution, ledger, ledgerEvidence, startedAt) }
    }

    private suspend fun CoroutineScope.loadVisit(
        profileId: String,
        resolution: UpNextSourceResolution,
        ledger: Map<TitleId, LedgerSeries>,
        ledgerEvidence: List<UpNextSeriesEvidence>,
        startedAt: Long,
    ) {
        val visit = loadKey
        val tracker = resolution.effective as? UpNextSource.Tracker

        val cachedCandidates = if (tracker != null) {
            publishCachedAnswer(profileId, tracker, resolution, ledgerEvidence, startedAt)
        } else {
            emptyList()
        }

        val sharedCandidates =
            java.util.concurrent.atomic.AtomicReference<List<UpNextTrackerCandidate>>(cachedCandidates)
        val sharedEvidence =
            java.util.concurrent.atomic.AtomicReference<List<UpNextSeriesEvidence>>(ledgerEvidence)

        var published: List<dev.infusion.core.domain.upnext.UpNextCard>? = null
        val trackerRead = tracker?.let { selected ->
            async {
                val arrived = readTrackerVisit(profileId, selected, resolution, cachedCandidates)
                sharedCandidates.set(arrived.candidates)

                publishCandidates(
                    profileId = profileId,
                    resolution = resolution,
                    evidence = sharedEvidence.get(),
                    candidates = arrived.candidates,
                    label = "tracker",
                    startedAt = startedAt,
                )
                trace("tracker answer published")
                arrived
            }
        }

        val localMs = System.currentTimeMillis()
        val evidenceBySeries = LinkedHashMap<TitleId, UpNextSeriesEvidence>()
        ledgerEvidence.forEach { evidenceBySeries[it.seriesTitleId] = it }
        var hydratedSeries = 0

        var hydrateNanos = 0L
        var decisionNanos = 0L
        var publishNanos = 0L
        var publications = 0
        var derivations = 0
        var reusedSeries = 0
        var episodesMapped = 0
        var seasonsMapped = 0
        var hierarchySeasonCalls = 0
        var hierarchyEpisodeCalls = 0
        var hierarchyMs = 0L
        var seriesIndex = 0

        val decisions = HashMap<TitleId, dev.infusion.core.domain.upnext.UpNextCard?>(ledger.size * 2)
        var decisionsCandidates: List<UpNextTrackerCandidate>? = null

        var candidateOrder: Map<TitleId, Int> = emptyMap()

        fun rebuildEveryDecision(candidates: List<UpNextTrackerCandidate>) {
            decisions.clear()
            val candidateBySeries = candidates.groupBy { it.seriesTitleId }.mapValues { (_, entries) -> entries.first() }

            candidateOrder = candidates.withIndex()
                .groupBy({ (_, candidate) -> candidate.seriesTitleId }, { (index, _) -> index })
                .mapValues { (_, indexes) -> indexes.first() }
            for ((id, evidence) in evidenceBySeries) {
                decisions[id] = UpNextProjection.decideSeries(
                    evidence,
                    candidateBySeries[id],
                    today(),
                    now(),
                    candidateOrder[id],
                ).card
                derivations++
                attribution.decisionCalls++
            }
            for ((id, candidate) in candidateBySeries) {
                if (id !in evidenceBySeries) {
                    decisions[id] = UpNextProjection.decideSeries(
                        null,
                        candidate,
                        today(),
                        now(),
                        candidateOrder[id],
                    ).card
                    derivations++
                    attribution.decisionCalls++
                }
            }
        }

        val orderedLedger = ledger.entries.sortedByDescending { it.value.activityAt ?: Instant.EPOCH }
        for ((seriesId, ledgerSeries) in orderedLedger) {
            if (loadKey != visit) {

                trackerRead?.cancel()
                return
            }
            val enqueuedAt = System.nanoTime()
            val hydrateAt = System.nanoTime()
            val built = withContext(ioDispatcher) { localEvidenceFor(profileId, seriesId, ledgerSeries, enqueuedAt) }
            val hydrateMs = (System.nanoTime() - hydrateAt) / 1_000_000
            hydrateNanos += hydrateMs * 1_000_000
            attribution.hydrateResumeMs += (hydrateMs - built.waitMs - built.insideMs).coerceAtLeast(0)
            if (built.reused) reusedSeries++
            built.hydrated?.let { hydrated ->
                hierarchyMs += hydrated.callMs
                hierarchySeasonCalls += hydrated.seasonCalls
                hierarchyEpisodeCalls += hydrated.episodeCalls
            }
            val evidence = built.evidence
            if (evidence != null) {
                evidenceBySeries[seriesId] = evidence
                hydratedSeries++
                seasonsMapped += evidence.episodes.map { it.seasonNumber }.distinct().size
                episodesMapped += evidence.episodes.size
            }
            seriesIndex++
            val snapshotAt = System.nanoTime()
            val snapshot = evidenceBySeries.values.toList()
            attribution.snapshotMs += (System.nanoTime() - snapshotAt) / 1_000_000
            attribution.snapshotBuilds++

            sharedEvidence.set(snapshot)
            val decisionAt = System.nanoTime()
            val candidatesNow = sharedCandidates.get()
            if (candidatesNow !== decisionsCandidates) {
                decisionsCandidates = candidatesNow
                rebuildEveryDecision(candidatesNow)
            } else {
                val candidate = candidatesNow.firstOrNull { it.seriesTitleId == seriesId }
                decisions[seriesId] =
                    UpNextProjection.decideSeries(
                        evidenceBySeries[seriesId],
                        candidate,
                        today(),
                        now(),
                        candidateOrder[seriesId],
                    ).card
                derivations++
                attribution.decisionCalls++
            }
            val orderAt = System.nanoTime()
            val cards = UpNextProjection.ordered(decisions.values.filterNotNull())
            attribution.orderMs += (System.nanoTime() - orderAt) / 1_000_000
            attribution.orderRuns++
            decisionNanos += (System.nanoTime() - decisionAt)
            val publishAt = System.nanoTime()
            if (cards != published) recordPublicationShape(published, cards)
            if (cards != published && publishCards(cards, profileId, resolution, label = "progress", startedAt = startedAt)) {
                published = cards
                publications++
            }
            publishNanos += (System.nanoTime() - publishAt)
        }
        val local = evidenceBySeries.values.toList()
        trace(
            "local evidence series=${local.size} hydrated=$hydratedSeries " +
                "ms=${System.currentTimeMillis() - localMs}",
        )
        trace(
            "local owners hydrate_ms=${hydrateNanos / 1_000_000} reused=$reusedSeries " +
                "decide_ms=${decisionNanos / 1_000_000} derived=$derivations " +
                "publish_ms=${publishNanos / 1_000_000} publications=$publications " +
                "hier_season_calls=$hierarchySeasonCalls hier_episode_calls=$hierarchyEpisodeCalls " +
                "hier_ms=$hierarchyMs seasons=$seasonsMapped episodes=$episodesMapped " +
                "series_touched=$seriesIndex",
        )
        traceLocalAttribution(publications)

        val providerAnswer = trackerRead?.await()

        if (providerAnswer == null) {

            UpNextProjection.project(local, emptyList(), today(), now()).cards.let { cards ->
                publishCards(cards, profileId, resolution, label = "local", startedAt = startedAt, authoritative = true)
            }
            trace(
                "done source=local-only row=${_state.value.row::class.simpleName} " +
                    "total_ms=${System.currentTimeMillis() - startedAt}",
            )
            return
        }
        val projected = UpNextProjection.project(local, providerAnswer.candidates, today(), now())
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

            !providerAnswer.resolved -> UpNextRowState.Failed(
                providerAnswer.note ?: "Could not load Up Next.",
            )
            else -> UpNextRowState.Empty
        }

        publish(
            row,
            profileId,
            resolution,
            note = if (projectedCards.isNotEmpty()) providerAnswer.note else null,
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
    ) = publishMutex.withLock {
        val key = partitionFor(profileId, resolution)
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

    private class HydratedHierarchy(
        val episodes: List<UpNextEpisode>,
        val callMs: Long,
        val seasonCalls: Int,
        val episodeCalls: Int,
    )

    private suspend fun episodesOf(
        hierarchy: CanonicalShowHierarchy,
        seriesId: TitleId,
    ): HydratedHierarchy {
        val startedAt = System.nanoTime()
        var seasonCalls = 0
        var episodeCalls = 0
        val seasons = hierarchy.seasonNumbers(seriesId).also { seasonCalls++ }.filter { it > 0 }.sorted()
        val episodes = ArrayList<UpNextEpisode>()
        for (season in seasons) {
            hierarchy.episodes(seriesId, season).also { episodeCalls++ }.forEach { episode ->
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
        return HydratedHierarchy(
            episodes = episodes,
            callMs = (System.nanoTime() - startedAt) / 1_000_000,
            seasonCalls = seasonCalls,
            episodeCalls = episodeCalls,
        )
    }

    private fun parseRelease(raw: String?): Pair<LocalDate?, Instant?> =
        dev.infusion.core.domain.watch.EpisodeReleaseEvidence.parse(raw)

    private data class TrackerAnswer(
        val candidates: List<UpNextTrackerCandidate>,
        val note: String?,

        val resolved: Boolean,
    )

    private fun publishCachedAnswer(
        profileId: String,
        tracker: UpNextSource.Tracker,
        resolution: UpNextSourceResolution,
        evidence: List<UpNextSeriesEvidence>,
        startedAt: Long,
    ): List<UpNextTrackerCandidate> {
        val candidates = cachedCandidatesFor(profileId, tracker, resolution)
        publishCandidates(
            profileId = profileId,
            resolution = resolution,
            evidence = evidence,
            candidates = candidates,
            label = "cached",
            startedAt = startedAt,
        )
        return candidates
    }

    private fun cachedCandidatesFor(
        profileId: String,
        tracker: UpNextSource.Tracker,
        resolution: UpNextSourceResolution,
    ): List<UpNextTrackerCandidate> {
        val account = accountFor(resolution.effective)
        val cached = cacheStore?.let { store ->
            runCatching { store.read(profileId, tracker.trackerId, account) }.getOrNull()
        }
        return cached?.candidates.orEmpty()
    }

    private suspend fun readTrackerVisit(
        profileId: String,
        tracker: UpNextSource.Tracker,
        resolution: UpNextSourceResolution,
        cachedCandidates: List<UpNextTrackerCandidate>,
    ): TrackerAnswer {
        val account = accountFor(resolution.effective)

        val partition = partitionFor(profileId, resolution)
        heldAnswers[partition]?.let { held ->
            trace("provider answer reused partition=${sourceLabel(resolution)}")
            return held
        }

        trace("cross-visit reuse refused reason=${UpNextAnswerReuse.refusal}")
        val readMs = System.currentTimeMillis()
        val outcome = withContext(ioDispatcher) { readTracker(tracker.trackerId) }
        trace("read outcome=${outcome::class.simpleName} ms=${System.currentTimeMillis() - readMs}")
        val (candidates, note) = when (outcome) {
            is TrackerRead.Resolved -> {

                withContext(ioDispatcher) {
                    cacheStore?.let { store ->
                        runCatching {
                            store.write(
                                UpNextCachedAnswer(
                                    profileId = profileId,
                                    trackerId = tracker.trackerId,
                                    accountLabel = account,

                                    fetchedAt = now(),
                                    candidates = outcome.candidates,

                                    changeEvidence = null,
                                ),
                            )
                            store.clearOtherAccounts(profileId, tracker.trackerId, account)
                        }
                    }
                }
                outcome.candidates to null
            }
            is TrackerRead.Revoked -> {

                withContext(ioDispatcher) {
                    cacheStore?.let { store ->
                        runCatching { store.clearAccount(profileId, tracker.trackerId, account) }
                    }
                }
                emptyList<UpNextTrackerCandidate>() to outcome.reason
            }
            is TrackerRead.Unavailable -> {

                cachedCandidates to outcome.reason
            }
        }
        return TrackerAnswer(candidates, note, outcome is TrackerRead.Resolved).also { answer ->

            if (answer.resolved) heldAnswers[partition] = answer
        }
    }

    private fun partitionFor(profileId: String, resolution: UpNextSourceResolution): LoadKey =
        LoadKey(profileId, resolution.effective, accountFor(resolution.effective))

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

    private class LedgerRead(
        val series: Map<TitleId, LedgerSeries>,
        val flowMs: Long,
        val groupMs: Long,
        val rows: Int,
    )

    private suspend fun ledgerStates(): LedgerRead {
        val flowAt = System.nanoTime()
        val states = runCatching { watchStates.observeAll().first() }.getOrDefault(emptyList())
        val flowMs = (System.nanoTime() - flowAt) / 1_000_000
        val groupAt = System.nanoTime()
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
        val series = bySeries.mapValues { (seriesId, watched) ->
            LedgerSeries(watchStates = watched, activityAt = activity[seriesId])
        }
        return LedgerRead(
            series = series,
            flowMs = flowMs,
            groupMs = (System.nanoTime() - groupAt) / 1_000_000,
            rows = states.size,
        )
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

    private suspend fun localEvidenceFor(
        profileId: String,
        seriesId: TitleId,
        ledger: LedgerSeries,
        enqueuedAt: Long = System.nanoTime(),
    ): SeriesEvidence {

        val entryAt = System.nanoTime()
        val waitMs = (entryAt - enqueuedAt) / 1_000_000
        attribution.seriesDispatchMs += waitMs
        val built = buildLocalEvidence(profileId, seriesId, ledger)
        built.waitMs = waitMs
        built.insideMs = (System.nanoTime() - entryAt) / 1_000_000
        return built
    }

    private suspend fun buildLocalEvidence(
        profileId: String,
        seriesId: TitleId,
        ledger: LedgerSeries,
    ): SeriesEvidence {
        val getAt = System.nanoTime()
        val retained = runCatching { episodeEvidenceRetention.get(profileId, seriesId) }.getOrNull()
        attribution.retentionGetMs += (System.nanoTime() - getAt) / 1_000_000
        if (retained != null) {
            val sufficiencyAt = System.nanoTime()
            val answers = retainedEvidenceAnswers(seriesId, retained, ledger)
            attribution.sufficiencyMs += (System.nanoTime() - sufficiencyAt) / 1_000_000
            attribution.sufficiencyChecks++
            if (answers) {
                return SeriesEvidence(
                    evidence = UpNextSeriesEvidence(
                        seriesTitleId = seriesId,
                        seriesTitle = null,
                        episodes = retained,
                        watchStates = ledger.watchStates,
                        activityAt = ledger.activityAt,
                    ),
                    reused = true,
                )
            }
        }
        val hierarchy = this.hierarchy ?: return SeriesEvidence(evidence = null, reused = false)
        val buildAt = System.nanoTime()
        val hydrated = runCatching { episodesOf(hierarchy, seriesId) }.getOrNull()
        attribution.episodesBuildMs += (System.nanoTime() - buildAt) / 1_000_000
        if (hydrated == null) return SeriesEvidence(evidence = null, reused = false)
        if (hydrated.episodes.isEmpty()) return SeriesEvidence(evidence = null, reused = false, hydrated = hydrated)
        val putAt = System.nanoTime()
        runCatching { episodeEvidenceRetention.put(profileId, seriesId, hydrated.episodes) }
        attribution.retentionPutMs += (System.nanoTime() - putAt) / 1_000_000
        return SeriesEvidence(
            evidence = UpNextSeriesEvidence(
                seriesTitleId = seriesId,
                seriesTitle = null,
                episodes = hydrated.episodes,
                watchStates = ledger.watchStates,
                activityAt = ledger.activityAt,
            ),
            reused = false,
            hydrated = hydrated,
        )
    }

    private class SeriesEvidence(
        val evidence: UpNextSeriesEvidence?,
        val reused: Boolean,
        val hydrated: HydratedHierarchy? = null,
    ) {

        var waitMs: Long = 0

        var insideMs: Long = 0
    }

    private fun retainedEvidenceAnswers(
        seriesId: TitleId,
        retained: List<UpNextEpisode>,
        ledger: LedgerSeries,
    ): Boolean {
        if (retained.isEmpty()) return false
        val coordinates = retained.mapTo(HashSet()) { episode -> episode.seasonNumber to episode.episodeNumber }
        val covered = ledger.watchStates.keys.all { key ->
            val season = key.seasonNumber
            val episode = key.episodeNumber
            season == null || episode == null || (season to episode) in coordinates
        }
        if (!covered) return false
        val decision = UpNextProjection.decideSeries(
            evidence = UpNextSeriesEvidence(
                seriesTitleId = seriesId,
                seriesTitle = null,
                episodes = retained,
                watchStates = ledger.watchStates,
                activityAt = ledger.activityAt,
            ),
            candidate = null,
            today = today(),
            now = now(),
        )
        if (decision.card != null) return true
        return decision.reason == dev.infusion.core.domain.upnext.UpNextUnresolvedReason.NOT_YET_AIRED ||
            decision.reason == dev.infusion.core.domain.upnext.UpNextUnresolvedReason.UNFINISHED_LOCAL_EPISODE
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

                        return TrackerRead.Resolved(candidates, complete = pageMalformed == 0)
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

        return TrackerRead.Resolved(candidates, complete = false)
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
    ): Boolean = publishMutex.withLock {
        attribution.pubAttempts++
        if (cards.isEmpty()) return@withLock false
        val dropAt = System.nanoTime()
        val wouldDrop = !authoritative && wouldDropShownSeries(cards, profileId, resolution)
        attribution.pubWouldDropMs += (System.nanoTime() - dropAt) / 1_000_000
        if (wouldDrop) {

            attribution.pubWithheld++
            trace(
                "published $label withheld-shrink cards=${cards.size} " +
                    "ms=${System.currentTimeMillis() - startedAt}",
            )
            return@withLock false
        }
        currentCards = cards

        val seedAt = System.nanoTime()
        seedRetained(profileId, cards)
        attribution.pubSeedMs += (System.nanoTime() - seedAt) / 1_000_000
        val buildAt = System.nanoTime()
        val priorRow = (_state.value.row as? UpNextRowState.Loaded)?.cards
        val row = cards.map { card -> toRowCard(card, enrichmentFor(card)) }
        attribution.pubRowBuildMs += (System.nanoTime() - buildAt) / 1_000_000
        attribution.rowCardsBuilt += row.size
        val emitAt = System.nanoTime()
        if (row == priorRow) attribution.pubSameVisible++
        publish(UpNextRowState.Loaded(row), profileId, resolution)
        attribution.pubEmitMs += (System.nanoTime() - emitAt) / 1_000_000
        attribution.pubEmitted++
        if (Thread.currentThread().name == MAIN_THREAD_NAME) attribution.pubMainThread++
        trace(
            "published $label cards=${row.size} " +
                "artwork=${row.count { it.preview.artwork != null }} " +
                "ms=${System.currentTimeMillis() - startedAt}",
        )
        val wakeAt = System.nanoTime()
        startPresentation(profileId, resolution, startedAt)
        attribution.pubWakeMs += (System.nanoTime() - wakeAt) / 1_000_000
        true
    }

    private fun recordPublicationShape(
        previous: List<dev.infusion.core.domain.upnext.UpNextCard>?,
        cards: List<dev.infusion.core.domain.upnext.UpNextCard>,
    ) {
        val before = previous?.associateBy { it.key }.orEmpty()
        var added = 0
        var replaced = 0
        cards.forEach { card ->
            val held = before[card.key]
            when {
                held == null -> added++
                held != card -> replaced++
            }
        }
        attribution.pubAddedKeys += added
        attribution.pubReplacedKeys += replaced
        attribution.pubRemovedKeys += (before.size - (cards.size - added)).coerceAtLeast(0)
    }

    private fun traceLocalAttribution(publications: Int) {
        trace(
            "attr ledger rows=${attribution.ledgerRows} dispatch_ms=${attribution.ledgerDispatchMs} " +
                "flow_ms=${attribution.ledgerFlowMs} group_ms=${attribution.ledgerGroupMs} " +
                "evidence_ms=${attribution.ledgerEvidenceMs}",
        )
        trace(
            "attr series dispatch_ms=${attribution.seriesDispatchMs} " +
                "resume_ms=${attribution.hydrateResumeMs} " +
                "retention_get_ms=${attribution.retentionGetMs} " +
                "sufficiency_checks=${attribution.sufficiencyChecks} " +
                "sufficiency_ms=${attribution.sufficiencyMs} " +
                "hydrate_build_ms=${attribution.episodesBuildMs} " +
                "retention_put_ms=${attribution.retentionPutMs} " +
                "snapshot_builds=${attribution.snapshotBuilds} snapshot_ms=${attribution.snapshotMs} " +
                "decision_calls=${attribution.decisionCalls} " +
                "order_runs=${attribution.orderRuns} order_ms=${attribution.orderMs} " +
                "seed_lookups=${attribution.seedLookups} seed_hits=${attribution.seedHits} " +
                "seed_lookup_ms=${attribution.seedLookupMs}",
        )
        trace(
            "attr publish attempts=${attribution.pubAttempts} emitted=${attribution.pubEmitted} " +
                "withheld=${attribution.pubWithheld} same_visible=${attribution.pubSameVisible} " +
                "main_thread=${attribution.pubMainThread} added_keys=${attribution.pubAddedKeys} " +
                "removed_keys=${attribution.pubRemovedKeys} " +
                "replaced_keys=${attribution.pubReplacedKeys} " +
                "would_drop_ms=${attribution.pubWouldDropMs} seed_ms=${attribution.pubSeedMs} " +
                "row_build_ms=${attribution.pubRowBuildMs} row_cards=${attribution.rowCardsBuilt} " +
                "row_reused=${attribution.rowCardsReused} emit_ms=${attribution.pubEmitMs} " +
                "wake_ms=${attribution.pubWakeMs} publications=$publications",
        )
        trace(
            "attr present issued=${attribution.enrichIssued.get()} " +
                "ms=${attribution.enrichMs.get()} wakes=${attribution.presentWakes.get()}",
        )
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
                attribution.presentWakes.incrementAndGet()

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
                                val enrichAt = System.nanoTime()
                                val answer = runCatching {
                                    enrich(
                                        UpNextCardRequest(
                                            card.seriesTitleId,
                                            card.seasonNumber,
                                            card.episodeNumber,
                                        ),
                                    )
                                }.getOrDefault(UpNextCardEnrichment())
                                attribution.enrichMs.addAndGet((System.nanoTime() - enrichAt) / 1_000_000)
                                attribution.enrichIssued.incrementAndGet()
                                answer
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

    private val seedAsked: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun seedRetained(
        profileId: String,
        cards: List<dev.infusion.core.domain.upnext.UpNextCard>,
    ) {
        val retention = presentationRetention ?: return
        cards.forEach { card ->
            if (enriched.containsKey(card.key)) return@forEach
            if (!seedAsked.add(card.key)) return@forEach

            attribution.seedLookups++
            val lookupAt = System.nanoTime()
            val retained = retention.get(profileId, card.key)
            attribution.seedLookupMs += (System.nanoTime() - lookupAt) / 1_000_000
            if (retained != null) {
                attribution.seedHits++
                enriched[card.key] = retained
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

        data class Resolved(
            val candidates: List<UpNextTrackerCandidate>,
            val complete: Boolean = true,
        ) : TrackerRead

        data class Revoked(val reason: String) : TrackerRead

        data class Unavailable(val reason: String) : TrackerRead
    }

    private companion object {

        const val MAX_PAGES: Int = 10

        const val MAX_CONCURRENT_ENRICHMENTS: Int = 6

        const val LOCAL_ONLY_LABEL: String = "Local only"
        const val UNAVAILABLE_IDENTITY: String = "identity unavailable"
        const val UNAVAILABLE_TRANSPORT: String = "provider unavailable"

        const val MAIN_THREAD_NAME: String = "main"
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

interface UpNextEpisodeEvidenceRetention {

    fun get(profileId: String, seriesTitleId: TitleId): List<UpNextEpisode>?

    fun put(profileId: String, seriesTitleId: TitleId, episodes: List<UpNextEpisode>)
}

class InMemoryUpNextEpisodeEvidenceRetention(
    private val localeToken: () -> String = { "" },
    private val limit: Int = DEFAULT_LIMIT,
) : UpNextEpisodeEvidenceRetention {

    private val entries: MutableMap<String, List<UpNextEpisode>> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, List<UpNextEpisode>>(128, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<UpNextEpisode>>,
                ): Boolean = size > limit
            },
        )

    override fun get(profileId: String, seriesTitleId: TitleId): List<UpNextEpisode>? =
        entries["${localeToken()}\u0000$profileId\u0000${seriesTitleId.value}"]

    override fun put(profileId: String, seriesTitleId: TitleId, episodes: List<UpNextEpisode>) {
        if (episodes.isEmpty()) return
        entries["${localeToken()}\u0000$profileId\u0000${seriesTitleId.value}"] = episodes
    }

    companion object {

        const val DEFAULT_LIMIT: Int = 1024

        val Shared: InMemoryUpNextEpisodeEvidenceRetention by lazy {
            InMemoryUpNextEpisodeEvidenceRetention()
        }
    }
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
