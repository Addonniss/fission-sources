package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.anchor.CanonicalDestinationAnchor
import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.collections.CollectionEntityKind
import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.domain.metadata.MetadataIdentityHint
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.domain.metadata.MetadataResult
import dev.infusion.core.domain.metadata.MetadataSection
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingReviewQuery
import dev.infusion.core.domain.tracking.TrackingReviewReadPort
import dev.infusion.core.domain.watch.WatchStateRepository
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.CollectionDetails
import dev.infusion.core.model.metadata.Company
import dev.infusion.core.model.metadata.EpisodeDetails
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.MediaArtwork
import dev.infusion.core.model.metadata.MediaCard
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaReview
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.MovieDetails
import dev.infusion.core.model.metadata.PersonRef
import dev.infusion.core.model.metadata.ReviewPage
import dev.infusion.core.model.metadata.SeasonDetails
import dev.infusion.core.model.metadata.SeasonSummary
import dev.infusion.core.model.metadata.ShowDetails
import dev.infusion.core.model.metadata.VideoKind
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.feature.vertical.browse.SourceCardIdentityAnchor
import dev.infusion.feature.vertical.details.WatchedStateDemand
import dev.infusion.feature.vertical.details.effectiveWatched
import dev.infusion.feature.vertical.model.WatchedDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CanonicalDetailsStateHolder(
    private val titleId: TitleId,
    private val mediaType: MediaType,
    private val repository: MetadataRepository,
    private val resolver: MediaIdentityResolver,
    private val scope: CoroutineScope,
    private val locale: () -> MetadataLocale = { MetadataLocale.Default },

    private val watchStateRepository: WatchStateRepository? = null,

    private val serverSeasonResume: (suspend (seasonNumber: Int) -> Map<Pair<Int, Int>, ServerResumePosition>)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val artworkProvider: String = "tmdb",

    private val identityHint: MetadataIdentityHint? = null,

    private val loadSecondarySections: Boolean = true,

    private val eagerSecondarySections: Boolean = false,

    private val destinationAnchor: CanonicalDestinationAnchor? = null,

    private val rootTitleStore: CanonicalRootTitleStore? = null,
    private val identityStore: MediaIdentityStore? = null,

    private val watchedStateDemand: WatchedStateDemand = WatchedStateDemand.None,

    private val ratingsProjection: (List<dev.infusion.core.model.metadata.MediaRating>) ->
        List<dev.infusion.core.model.metadata.MediaRating> = { it },

    private val episodeRatingsProjection: (List<dev.infusion.core.model.metadata.MediaRating>) ->
        List<dev.infusion.core.model.metadata.MediaRating> = { it },

    private val trackerReviewPorts:
        (() -> List<dev.infusion.core.domain.tracking.TrackingReviewReadPort>)? = null,

    private val trackerReviewChanges: kotlinx.coroutines.flow.Flow<Unit>? = null,
) {

    private val _state = MutableStateFlow(CanonicalDetailsUiState(titleId = titleId, mediaType = mediaType))

    val state: StateFlow<CanonicalDetailsUiState> = _state

    private val reviewsLock = Any()
    private val metadataReviewItems = mutableListOf<MediaReview>()
    private var metadataReviewHasMore: Boolean = false
    private var metadataReviewPage: Int = 0
    private var metadataReviewTotalPages: Int = 0
    private val trackerReviewItems = LinkedHashMap<String, List<MediaReview>>()
    private val trackerReviewHasMore = LinkedHashMap<String, Boolean>()
    private val trackerReviewPage = LinkedHashMap<String, Int>()
    private val trackerReviewTotalPages = LinkedHashMap<String, Int>()

    @Volatile
    private var trackerReviewSourceIds: List<String> = emptyList()

    @Volatile
    private var reviewsGeneration: Int = 0

    private val seasonPayloads = mutableMapOf<Int, SeasonDetails>()

    private val seasonsInFlight = mutableSetOf<Int>()

    private val seasonFailures = mutableMapOf<Int, MetadataFailureReason>()

    private var seasonSelectionIsUserOwned = false

    private var pendingContinuationSeason: Int? = null

    private val episodeFocusMemory = mutableMapOf<Int, String>()

    private val watchOverlay = MutableStateFlow<Map<Pair<Int, Int>, WatchState>>(emptyMap())

    @Volatile
    private var serverEpisodeResume: Map<Pair<Int, Int>, ServerResumePosition> = emptyMap()
    private val serverSeasonReadsInFlight: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var watchOverlayJob: Job? = null

    private val pagesInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val identityAnchor = SourceCardIdentityAnchor(
        rootTitleStore = rootTitleStore,
        identityStore = identityStore,
    )

    private val cardIdentityAnchorWired: Boolean
        get() = rootTitleStore != null && identityStore != null

    init {
        scope.launch {
            val identity = resolveIdentity()
            if (identity != null) {
                _state.update { current -> current.copy(identity = identity) }

                if (eagerSecondarySections) requestSecondarySections()
            } else {

                _state.update { current -> current.copy(primarySettled = true) }
            }
            observeMetadata(identity)
        }
    }

    private fun requestSecondarySections() {
        if (!loadSecondarySections) return
        if (_state.value.recommendations is SectionState.Absent && !recommendationsRequested) {
            recommendationsRequested = true
            loadRecommendations()
        }
        if (_state.value.similar is SectionState.Absent && !similarRequested) {
            similarRequested = true
            loadSimilar()
        }

        if (_state.value.reviews is SectionState.Absent && !reviewsRequested) {
            reviewsRequested = true
            loadReviews()
        }
    }

    private suspend fun resolveIdentity(): dev.infusion.core.model.metadata.MediaIdentity? = try {
        val direct = resolver.resolve(titleId, mediaType, locale())
        val hint = identityHint

        val aliasless = direct == null || direct.externalIds.isEmpty
        if (!aliasless || hint == null) {
            direct
        } else {
            resolver.resolveHint(hint, locale())
                .takeIf { it.hasCanonicalMetadataAnchor }

                ?.copy(titleId = titleId)
                ?: direct
        }
    } catch (_: Exception) {
        null
    }

    private var destinationIdentityPublished = false

    private fun publishDestinationIdentity(details: MediaDetails) {
        val anchor = destinationAnchor ?: return
        if (destinationIdentityPublished) return
        destinationIdentityPublished = true
        val destination = dev.infusion.core.domain.anchor.CanonicalDestinationIdentity(
            titleId = titleId,
            mediaType = mediaType,
            identity = details.identity.copy(titleId = titleId),
            displayTitle = details.title.takeIf { it.isNotBlank() },
            year = details.year,
        )

        scope.launch {
            runCatching { anchor.anchor(destination) }
        }
    }

    private suspend fun observeMetadata(identity: dev.infusion.core.model.metadata.MediaIdentity?) {
        val requestIdentity = identity ?: return
        repository.observeDetails(requestIdentity, locale()).collect { snapshot ->
            val hero = snapshot.details?.let { toHero(it) }

            _state.update { current ->
                if (hero == null) {
                    current.copy(
                        refreshing = snapshot.refreshing,
                        metadataFailure = snapshot.failure,
                    )
                } else {
                    current.mergeDetails(snapshot.details!!, hero, snapshot.refreshing, snapshot.failure)
                }
            }

            if (snapshot.details != null || snapshot.failure != null) {
                _state.update { current -> current.copy(primarySettled = true) }
            }

            if (snapshot.details != null) {
                val details = snapshot.details!!

                publishDestinationIdentity(details)

                applyPendingContinuationSeason()

                requestSecondarySections()

            }
        }
    }

    private var recommendationsRequested = false
    private var similarRequested = false

    private var reviewsRequested = false

    init {
        val portsOf = trackerReviewPorts
        val changes = trackerReviewChanges
        if (portsOf != null && changes != null) {
            scope.launch {
                changes.collect {
                    val current = runCatching { portsOf().map { it.providerId } }.getOrDefault(emptyList())
                    if (current != trackerReviewSourceIds && reviewsRequested) {
                        reviewsGeneration++

                        pagesInFlight.remove(pageKey(MetadataSection.REVIEWS, 1))
                        loadReviews(page = 1, resetSources = true)
                    }
                }
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        seasonSelectionIsUserOwned = true
        pendingContinuationSeason = null
        applySeasonSelection(seasonNumber)
    }

    fun openContinuationSeason(seasonNumber: Int) {
        if (seasonSelectionIsUserOwned) return
        pendingContinuationSeason = seasonNumber
        applyPendingContinuationSeason()
    }

    private fun applyPendingContinuationSeason() {
        val seasonNumber = pendingContinuationSeason ?: return
        val seasons = _state.value.seasons

        if (seasons.isEmpty()) return

        pendingContinuationSeason = null
        if (seasons.none { it.seasonNumber == seasonNumber }) return
        if (_state.value.selectedSeasonNumber == seasonNumber) return
        applySeasonSelection(seasonNumber)
    }

    private fun applySeasonSelection(seasonNumber: Int) {
        if (!_state.value.seasons.any { it.seasonNumber == seasonNumber }) return
        val cached = seasonPayloads.containsKey(seasonNumber)
        val failed = seasonFailures[seasonNumber]
        _state.update { current ->
            current.copy(
                selectedSeasonNumber = seasonNumber,

                episodes = when {
                    cached -> projectSeason(seasonNumber)
                    failed != null -> SectionState.Failed(failed)
                    else -> SectionState.Absent
                },
                episodeFocusKey = null,
            )
        }
        if (cached || failed != null) {
            _state.update { current -> current.copy(episodeFocusKey = episodeFocusKeyFor(seasonNumber)) }

            ensureServerSeasonResume(seasonNumber)
            return
        }

        ensureServerSeasonResume(seasonNumber)
        if (!seasonsInFlight.add(seasonNumber)) return
        val identity = _state.value.identity ?: run {
            seasonsInFlight.remove(seasonNumber)
            return
        }
        ensureWatchOverlay()
        _state.update { current ->
            current.copy(
                episodes = SectionState.Loading,
                loadingSections = current.loadingSections + MetadataSection.SEASONS,
            )
        }
        scope.launch {
            val result = repository.seasonDetails(identity, seasonNumber, locale())
            when (result) {
                is MetadataResult.Success -> seasonPayloads[seasonNumber] = result.value
                is MetadataResult.Failure -> seasonFailures[seasonNumber] = result.reason
            }
            seasonsInFlight.remove(seasonNumber)
            _state.update { current ->

                if (current.selectedSeasonNumber != seasonNumber) {
                    current
                } else {
                    current.copy(
                        episodes = projectSeason(seasonNumber),
                        episodeFocusKey = episodeFocusKeyFor(seasonNumber),
                        loadingSections = current.loadingSections - MetadataSection.SEASONS,
                    )
                }
            }
        }
    }

    fun onEpisodeFocused(seasonNumber: Int, episodeFocusKey: String) {
        episodeFocusMemory[seasonNumber] = episodeFocusKey

        _state.update { current ->
            if (current.selectedSeasonNumber != seasonNumber) {
                current
            } else {
                current.copy(episodeFocusKey = episodeFocusKeyFor(seasonNumber))
            }
        }
    }

    private fun episodeFocusKeyFor(seasonNumber: Int): String? {
        val remembered = episodeFocusMemory[seasonNumber] ?: return null
        val rows = projectSeason(seasonNumber).valueOrNull() ?: return null
        return remembered.takeIf { key -> rows.any { it.focusKey == key } }
    }

    private fun projectSeason(seasonNumber: Int): SectionState<List<EpisodeCardUi>> {
        seasonFailures[seasonNumber]?.let { return SectionState.Failed(it) }
        val payload = seasonPayloads[seasonNumber] ?: return SectionState.Absent
        val cards = payload.episodes.map { toEpisodeCard(it, watchOverlay.value) }
        if (cards.isNotEmpty()) {

            watchedStateDemand.demandAll(
                cards.mapNotNull { WatchKey.episodeOrNull(titleId, it.seasonNumber, it.episodeNumber) },
            )
        }
        return if (cards.isEmpty()) SectionState.Absent else SectionState.Ready(cards)
    }

    private fun ensureWatchOverlay() {
        if (watchOverlayJob != null) return
        val watchRepository = watchStateRepository ?: return
        watchOverlayJob = scope.launch {
            watchRepository.observeAll().collect { states ->
                watchOverlay.value = withContext(ioDispatcher) {
                    states.filter { it.watchKey.seriesId == titleId }
                        .mapNotNull { state ->
                            val season = state.watchKey.seasonNumber ?: return@mapNotNull null
                            val episode = state.watchKey.episodeNumber ?: return@mapNotNull null
                            (season to episode) to state
                        }
                        .toMap()
                }
                val selected = _state.value.selectedSeasonNumber ?: return@collect
                if (!seasonPayloads.containsKey(selected)) return@collect
                _state.update { current ->
                    if (current.selectedSeasonNumber != selected) {
                        current
                    } else {
                        current.copy(episodes = projectSeason(selected))
                    }
                }
            }
        }
    }

    fun loadCollection() {
        val id = _state.value.collectionId ?: return
        if (_state.value.collection is SectionState.Loading) return
        _state.update { current ->
            current.copy(
                collection = SectionState.Loading,
                loadingSections = current.loadingSections + MetadataSection.COLLECTION,
            )
        }
        scope.launch {
            val result = repository.collectionDetails(id, locale())
            _state.update { current ->
                current.copy(
                    collection = when (result) {
                        is MetadataResult.Success -> toCollection(result.value)
                        is MetadataResult.Failure -> SectionState.Failed(result.reason)
                    },
                    loadingSections = current.loadingSections - MetadataSection.COLLECTION,
                )
            }
        }
    }

    fun loadRecommendations(page: Int = 1) {
        val identity = _state.value.identity ?: return
        if (!pagesInFlight.add(pageKey(MetadataSection.RECOMMENDATIONS, page))) return
        if (page == 1) {
            _state.update { current ->
                current.copy(
                    recommendations = SectionState.Loading,

                    recommendationsPage = CardPageUi.Empty,
                    recommendationsPagingFailure = null,
                    loadingSections = current.loadingSections + MetadataSection.RECOMMENDATIONS,
                )
            }
        }
        scope.launch {
            val result = repository.recommendations(identity, locale(), page)
            pagesInFlight.remove(pageKey(MetadataSection.RECOMMENDATIONS, page))
            val existing = _state.value.recommendations.valueOrNull().orEmpty()
            val next = when (result) {
                is MetadataResult.Success -> {

                    val merged = (if (page == 1) emptyList() else existing) +
                        anchorCards(result.value.items)
                    merged.distinctBy { it.identityKey ?: it.key }
                }
                is MetadataResult.Failure -> if (page == 1) null else existing
            }
            _state.update { current ->
                current.copy(
                    recommendations = when {
                        next == null -> SectionState.Failed(
                            (result as? MetadataResult.Failure)?.reason ?: MetadataFailureReason.UNAVAILABLE,
                        )
                        next.isEmpty() && page == 1 -> SectionState.Absent
                        else -> SectionState.Ready(next)
                    },
                    recommendationsPage = when (result) {
                        is MetadataResult.Success -> cardPageUi(result.value)

                        is MetadataResult.Failure -> current.recommendationsPage
                    },
                    recommendationsPagingFailure = when {
                        page == 1 -> null
                        result is MetadataResult.Failure -> result.reason
                        else -> null
                    },
                    loadingSections = current.loadingSections - MetadataSection.RECOMMENDATIONS,
                )
            }
        }
    }

    fun loadMoreRecommendations() {
        val page = _state.value.recommendationsPage
        if (!page.hasMore) return
        loadRecommendations(page.page + 1)
    }

    fun loadSimilar(page: Int = 1) {
        val identity = _state.value.identity ?: return
        if (!pagesInFlight.add(pageKey(MetadataSection.SIMILAR, page))) return
        if (page == 1) {
            _state.update { current ->
                current.copy(
                    similar = SectionState.Loading,
                    similarPage = CardPageUi.Empty,
                    similarPagingFailure = null,
                    loadingSections = current.loadingSections + MetadataSection.SIMILAR,
                )
            }
        }
        scope.launch {
            val repoStart = System.currentTimeMillis()
            val result = repository.similar(identity, locale(), page)
            val repoEnd = System.currentTimeMillis()
            pagesInFlight.remove(pageKey(MetadataSection.SIMILAR, page))
            val existing = _state.value.similar.valueOrNull().orEmpty()
            val next = when (result) {
                is MetadataResult.Success -> {

                    val merged = (if (page == 1) emptyList() else existing) +
                        anchorCards(result.value.items)
                    merged.distinctBy { it.identityKey ?: it.key }
                }
                is MetadataResult.Failure -> if (page == 1) null else existing
            }
            _state.update { current ->
                current.copy(
                    similar = when {
                        next == null -> SectionState.Failed(
                            (result as? MetadataResult.Failure)?.reason ?: MetadataFailureReason.UNAVAILABLE,
                        )
                        next.isEmpty() && page == 1 -> SectionState.Absent
                        else -> SectionState.Ready(next)
                    },
                    similarPage = when (result) {
                        is MetadataResult.Success -> cardPageUi(result.value)
                        is MetadataResult.Failure -> current.similarPage
                    },
                    similarPagingFailure = when {
                        page == 1 -> null
                        result is MetadataResult.Failure -> result.reason
                        else -> null
                    },
                    loadingSections = current.loadingSections - MetadataSection.SIMILAR,
                )
            }
        }
    }

    fun loadMoreSimilar() {
        val page = _state.value.similarPage
        if (!page.hasMore) return
        loadSimilar(page.page + 1)
    }

    fun loadReviews(page: Int = 1) {
        loadReviews(page = page, resetSources = page == 1)
    }

    private fun loadReviews(page: Int, resetSources: Boolean, moreBudget: Int = 0) {
        val identity = _state.value.identity ?: return
        if (!pagesInFlight.add(pageKey(MetadataSection.REVIEWS, page))) return

        val askMetadata = page == 1 || metadataReviewHasMore
        val connectedPorts = connectedReviewPorts()
        val ports = connectedPorts.filter { port ->
            page == 1 || trackerReviewHasMore[port.providerId] == true
        }
        trackerReviewSourceIds = connectedPorts.map { it.providerId }
        if (resetSources) {
            synchronized(reviewsLock) {
                metadataReviewItems.clear()
                metadataReviewHasMore = false
                metadataReviewPage = 0
                metadataReviewTotalPages = 0
                trackerReviewItems.clear()
                trackerReviewHasMore.clear()
                trackerReviewPage.clear()
                trackerReviewTotalPages.clear()
            }
            _state.update { current ->
                current.copy(
                    reviews = SectionState.Loading,

                    reviewsPage = CardPageUi.Empty,
                    reviewsPagingFailure = null,
                    loadingSections = current.loadingSections + MetadataSection.REVIEWS,
                )
            }
        }
        val generation = reviewsGeneration
        scope.launch {
            val result = if (askMetadata) repository.reviews(identity, locale(), page) else null

            val trackerResults = if (ports.isEmpty()) {
                emptyList()
            } else {
                withContext(ioDispatcher) {
                    ports.map { port -> port to readTrackerReviews(port, identity, page) }
                }
            }
            pagesInFlight.remove(pageKey(MetadataSection.REVIEWS, page))
            if (generation != reviewsGeneration) return@launch
            val added = publishReviews(page, result, trackerResults)

            if (moreBudget > 0 && added > 0 && added < ReviewsMoreBatchTarget) {
                if (_state.value.reviewsHasMore && generation == reviewsGeneration) {
                    loadReviews(
                        page = page + 1,
                        resetSources = false,
                        moreBudget = moreBudget - 1,
                    )
                }
            }
        }
    }

    fun loadMoreReviews() {
        val page = _state.value.reviewsPage
        if (!page.hasMore) return

        loadReviews(
            page = page.page + 1,
            resetSources = false,
            moreBudget = (ReviewsMorePagesPerPress - 1).coerceAtLeast(0),
        )
    }

    private fun connectedReviewPorts(): List<TrackingReviewReadPort> =
        runCatching { trackerReviewPorts?.invoke().orEmpty() }.getOrDefault(emptyList())

    private suspend fun readTrackerReviews(
        port: TrackingReviewReadPort,
        identity: MediaIdentity,
        page: Int,
    ): TrackingInboundOutcome<ReviewPage> {
        val query = reviewQuery(identity) ?: return TrackingInboundOutcome.Rejected(NO_REVIEW_IDENTITY)
        return try {
            port.readReviews(query, page, TrackingReviewReadPort.DEFAULT_LIMIT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            TrackingInboundOutcome.Unavailable(REVIEW_SOURCE_FAILED)
        }
    }

    private fun reviewQuery(identity: MediaIdentity): TrackingReviewQuery? {
        val externalIds = TrackingExternalIds(
            tmdbId = identity.externalIds.tmdbId,
            imdbId = identity.externalIds.imdbId,
            tvdbId = identity.externalIds.tvdbId,
        )
        if (externalIds.isEmpty) return null
        val kind = when (mediaType) {
            MediaType.MOVIE -> TrackingMediaKind.MOVIE
            MediaType.SHOW -> TrackingMediaKind.SHOW
        }
        return TrackingReviewQuery(externalIds = externalIds, kind = kind)
    }

    private fun publishReviews(
        page: Int,
        result: MetadataResult<ReviewPage>?,
        trackerResults: List<Pair<TrackingReviewReadPort, TrackingInboundOutcome<ReviewPage>>>,
    ): Int {

        val previousKeys = ((_state.value.reviews as? SectionState.Ready)?.value ?: emptyList())
            .mapTo(HashSet()) { it.key }
        val snapshot: ReviewsSnapshot = synchronized(reviewsLock) {
            when (result) {
                null -> Unit
                is MetadataResult.Success -> {
                    if (page == 1) metadataReviewItems.clear()
                    metadataReviewItems += result.value.items
                    val distinct = metadataReviewItems.distinctBy { it.reviewId }
                    metadataReviewItems.clear()
                    metadataReviewItems += distinct
                    metadataReviewHasMore = result.value.hasMore
                    metadataReviewPage = result.value.page
                    metadataReviewTotalPages = result.value.totalPages
                }
                is MetadataResult.Failure -> if (page == 1) {
                    metadataReviewItems.clear()
                    metadataReviewHasMore = false
                    metadataReviewPage = 0
                    metadataReviewTotalPages = 0
                }
            }
            var sourceFailure: MetadataFailureReason? = null
            for ((port, outcome) in trackerResults) {
                when (outcome) {
                    is TrackingInboundOutcome.Ok -> {
                        val previous = if (page == 1) emptyList() else trackerReviewItems[port.providerId].orEmpty()
                        trackerReviewItems[port.providerId] =
                            (previous + outcome.value.items).distinctBy { it.reviewId }
                        trackerReviewHasMore[port.providerId] = outcome.value.hasMore
                        trackerReviewPage[port.providerId] = outcome.value.page
                        trackerReviewTotalPages[port.providerId] = outcome.value.totalPages
                    }

                    is TrackingInboundOutcome.Unauthorized -> sourceFailure = sourceFailure ?: MetadataFailureReason.UNAUTHORIZED
                    is TrackingInboundOutcome.Unavailable -> sourceFailure = sourceFailure ?: MetadataFailureReason.UNAVAILABLE
                    is TrackingInboundOutcome.Rejected -> sourceFailure = sourceFailure ?: MetadataFailureReason.UNSUPPORTED
                }
            }
            val ui = ArrayList<ReviewUi>(metadataReviewItems.size + trackerReviewItems.size)
            metadataReviewItems.mapTo(ui) { toReviewUi(it) }
            val labels = trackerResults.associate { it.first.providerId to it.first.displayName }
            for ((sourceId, items) in trackerReviewItems) {
                items.mapTo(ui) { toTrackerReviewUi(sourceId, labels[sourceId], it) }
            }
            ReviewsSnapshot(
                reviews = ui.distinctBy { it.key },

                page = maxOf(
                    metadataReviewPage,
                    trackerReviewPage.values.maxOrNull() ?: 0,
                ).takeIf { it > 0 } ?: page,
                totalPages = maxOf(
                    metadataReviewTotalPages,
                    trackerReviewTotalPages.values.maxOrNull() ?: 0,
                ),
                hasMore = metadataReviewHasMore || trackerReviewHasMore.values.any { it },
                sourceFailure = sourceFailure,
            )
        }
        val metadataFailure = (result as? MetadataResult.Failure)
            ?.reason
            ?.takeUnless { it == MetadataFailureReason.NOT_FOUND }
        val failure = metadataFailure ?: snapshot.sourceFailure
        _state.update { current ->
            current.copy(
                reviews = when {
                    snapshot.reviews.isNotEmpty() -> SectionState.Ready(snapshot.reviews)
                    failure != null -> SectionState.Failed(failure)
                    else -> SectionState.Absent
                },
                reviewsPage = CardPageUi(
                    page = snapshot.page,
                    totalPages = maxOf(snapshot.totalPages, snapshot.page),
                    hasMore = snapshot.hasMore,
                ),
                reviewsPagingFailure = when {
                    page == 1 -> null
                    else -> failure
                },
                loadingSections = current.loadingSections - MetadataSection.REVIEWS,
            )
        }
        return snapshot.reviews.count { it.key !in previousKeys }
    }

    private data class ReviewsSnapshot(
        val reviews: List<ReviewUi>,
        val page: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val sourceFailure: MetadataFailureReason?,
    )

    private fun pageKey(section: MetadataSection, page: Int): String = "${section.name}:$page"

    fun retry(section: MetadataSection) {
        when (section) {
            MetadataSection.RECOMMENDATIONS -> {
                recommendationsRequested = true

                if (_state.value.recommendationsPagingFailure != null) {
                    loadMoreRecommendations()
                } else {
                    loadRecommendations()
                }
            }
            MetadataSection.SIMILAR -> {
                similarRequested = true
                if (_state.value.similarPagingFailure != null) {
                    loadMoreSimilar()
                } else {
                    loadSimilar()
                }
            }
            MetadataSection.REVIEWS -> {
                reviewsRequested = true

                if (_state.value.reviewsPagingFailure != null) {

                    val page = _state.value.reviewsPage
                    if (page.hasMore) loadReviews(page = page.page + 1, resetSources = false)
                } else {
                    loadReviews()
                }
            }
            MetadataSection.COLLECTION -> loadCollection()
            MetadataSection.SEASONS -> _state.value.selectedSeasonNumber?.let { season ->

                seasonFailures.remove(season)
                seasonPayloads.remove(season)
                selectSeason(season)
            }

            MetadataSection.HERO,
            MetadataSection.RATINGS,
            MetadataSection.TRAILER,
            MetadataSection.CAST,
            MetadataSection.PERSON,
            -> Unit
        }
    }

    private fun CanonicalDetailsUiState.mergeDetails(
        details: MediaDetails,
        hero: CanonicalHeroUi,
        refreshing: Boolean,
        failure: MetadataFailureReason?,
    ): CanonicalDetailsUiState {
        val show = details as? ShowDetails
        val seasonChips = show?.seasons?.map(::toSeasonChip).orEmpty()
        val defaultSeason = show?.defaultSeasonNumber
        return copy(
            hero = hero,
            trailer = primaryTrailer(details),
            directors = details.directors.map(::toPersonCard),
            creators = details.creators.map(::toPersonCard),
            studios = details.studios.map { it.name },
            networks = details.networks.map { it.name },

            studioCards = details.studios.map { toCompanyLogoUi(it, CollectionEntityKind.COMPANY) },
            networkCards = details.networks.map { toCompanyLogoUi(it, CollectionEntityKind.NETWORK) },
            cast = details.cast.map { toPersonCard(it.name, it.character, it.id, it.profile) },
            collectionName = (details as? MovieDetails)?.collection?.name,
            collectionId = (details as? MovieDetails)?.collection?.id,
            seasons = seasonChips,

            defaultSeasonNumber = defaultSeason,
            initialSeasonFocusKey = (seasonChips.firstOrNull { it.seasonNumber == defaultSeason }
                ?: seasonChips.firstOrNull())?.key,
            refreshing = refreshing,
            metadataFailure = failure,
        )
    }

    private fun toHero(details: MediaDetails): CanonicalHeroUi = CanonicalHeroUi(
        title = details.title,
        originalTitle = details.originalTitle,
        year = details.year,
        runtimeMinutes = details.runtimeMinutes,
        certification = details.certification,
        genres = details.genres.map { it.name },
        ratings = ratingsProjection(details.ratings).map { rating ->
            RatingUi(
                label = rating.label,

                text = dev.infusion.core.model.metadata.ratingScoreText(rating.ratedMetric, rating.value),
                fraction = rating.fraction,
                metricId = rating.metric,
            )
        },
        overview = details.overview,
        mediaType = details.mediaType,
        backdropLocator = locator(details.artwork.backdrop, ArtworkPolicy.BACKDROP),
        posterLocator = locator(details.artwork.poster, ArtworkPolicy.POSTER),
        logoLocator = locator(details.artwork.logo, ArtworkPolicy.LOGO),
        tagline = details.tagline,
        seasonCount = (details as? ShowDetails)?.numberOfSeasons,

        status = details.status,
    )

    private fun primaryTrailer(details: MediaDetails): TrailerUi? {
        val video = details.primaryTrailer ?: return null

        if (!video.site.equals("youtube", ignoreCase = true)) return null
        return TrailerUi(
            site = "youtube",
            key = video.key,
            title = video.title,
            kindLabel = when (video.kind) {
                VideoKind.TRAILER -> "Trailer"
                VideoKind.TEASER -> "Teaser"
                VideoKind.CLIP -> "Clip"
                VideoKind.FEATURETTE -> "Featurette"
                VideoKind.BEHIND_THE_SCENES -> "Behind the scenes"
                VideoKind.OTHER -> "Video"
            },
        )
    }

    private fun toCompanyLogoUi(company: Company, kind: CollectionEntityKind): CompanyLogoUi = CompanyLogoUi(
        name = company.name,
        logoLocator = locator(company.logo, ArtworkPolicy.LOGO),

        id = company.id,
        kind = kind,
    )

    private fun toPersonCard(ref: PersonRef): PersonCardUi = toPersonCard(
        name = ref.name,
        role = null,
        id = ref.id,
        image = ref.profile,
    )

    private fun toPersonCard(
        name: String,
        role: String?,
        id: dev.infusion.core.model.metadata.PersonId?,
        image: ImageRef?,
    ): PersonCardUi = PersonCardUi(
        key = "person:${id?.authority ?: "unknown"}:${id?.value ?: name}",
        name = name,
        role = role,
        personId = id,
        imageLocator = locator(image, ArtworkPolicy.POSTER),
    )

    private fun toSeasonChip(season: SeasonSummary): SeasonChipUi = SeasonChipUi(
        key = "season:${season.seasonNumber}",
        seasonNumber = season.seasonNumber,
        label = season.displayLabel,
        episodeCount = season.episodeCount,
        isSpecial = season.seasonNumber == 0,
    )

    private fun toCardUi(card: MediaCard): MediaCardUi = mediaCardUi(card)

    private suspend fun toAnchoredCardUi(card: MediaCard): MediaCardUi {
        val canonicalTitleId = anchorCardIdentity(card)
        val ui = toCardUi(card)

        return canonicalTitleId?.let { resolved -> ui.copy(canonicalTitleId = resolved) } ?: ui
    }

    private suspend fun anchorCards(cards: List<MediaCard>): List<MediaCardUi> =
        if (cards.size <= 1) {
            cards.map { toAnchoredCardUi(it) }
        } else {
            coroutineScope {
                cards.map { card -> async { toAnchoredCardUi(card) } }.awaitAll()
            }
        }

    private suspend fun anchorCardIdentity(card: MediaCard): dev.infusion.core.model.TitleId? {
        if (!cardIdentityAnchorWired) return null
        val identity = card.identity
        if (identity.externalIds.isEmpty) return null
        val canonicalTitleId = runCatching { resolver.titleIdFor(identity) }.getOrNull() ?: return null
        identityAnchor.anchor(
            identity = identity,
            titleId = canonicalTitleId,
            kind = card.preview.kind,
            displayTitle = card.title,
            year = card.year,
        )
        return canonicalTitleId
    }

    private fun toReviewUi(review: MediaReview): ReviewUi = ReviewUi(
        reviewId = review.reviewId,
        author = review.author,
        content = review.content,
        authorRating = review.authorRating?.let(::formatRating),
        avatarLocator = locator(review.authorAvatar, ArtworkPolicy.POSTER),

        dateLabel = review.createdAt?.atZone(java.time.ZoneOffset.UTC)?.toLocalDate()?.toString(),
        providerLabel = review.providerLabel,
    )

    private fun toTrackerReviewUi(sourceId: String, sourceLabel: String?, review: MediaReview): ReviewUi =
        toReviewUi(review).copy(
            sourceId = sourceId,
            providerLabel = review.providerLabel?.takeIf { it.isNotBlank() } ?: sourceLabel,
        )

    private fun toCollection(collection: CollectionDetails): SectionState<CollectionUi> {
        if (collection.members.isEmpty()) return SectionState.Absent
        return SectionState.Ready(
            CollectionUi(
                id = collection.id,
                name = collection.name,
                members = collection.members.map(::toCardUi),
                currentTitleKey = _state.value.identity?.cacheKey(),
            ),
        )
    }

    private fun toEpisodeCard(
        episode: EpisodeDetails,
        overlay: Map<Pair<Int, Int>, WatchState>,
    ): EpisodeCardUi {
        val coordinate = episode.seasonNumber to episode.episodeNumber
        val watch = overlay[coordinate]
        val localWatched = watchDisplayOf(watch)

        val display = effectiveWatched(
            serverResume = serverEpisodeResume[coordinate],
            local = localWatched,
            localStatePresent = watch != null,
            hintResumeMs = null,
            localObservedAt = watch?.lastMeaningfulAt,
        )

        val episodeRatingFacts = episodeRatingItems(listOfNotNull(episode.rating) + episode.ratings)
        return EpisodeCardUi(
            key = "episode:${episode.coordinate}",
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            title = episode.displayTitle,
            overview = episode.overview,
            airDate = episode.airDate,
            runtimeMinutes = episode.runtimeMinutes,
            stillLocator = locator(episode.still, ArtworkPolicy.STILL),
            ratings = episodeRatingFacts,
            watchLabel = watchLabel(display),

            watchedFraction = when {
                display !is WatchedDisplay.InProgress -> null
                watch != null && watch.status == WatchStatus.IN_PROGRESS && display.positionMs == watch.positionMs ->
                    watch.completionFraction.takeIf { it > 0.0 }
                else -> episode.runtimeMinutes
                    ?.takeIf { it > 0 }
                    ?.let { minutes -> display.positionMs.toDouble() / (minutes * 60_000.0) }
                    ?.takeIf { it > 0.0 }
                    ?.coerceAtMost(1.0)
            },
        )
    }

    private fun watchDisplayOf(state: WatchState?): WatchedDisplay = when (state?.status) {
        WatchStatus.IN_PROGRESS -> WatchedDisplay.InProgress(state.positionMs, state.completionFraction)
        WatchStatus.COMPLETED -> WatchedDisplay.Completed
        else -> WatchedDisplay.Unseen
    }

    private fun ensureServerSeasonResume(seasonNumber: Int) {
        val read = serverSeasonResume ?: return
        if (!serverSeasonReadsInFlight.add(seasonNumber)) return
        scope.launch {
            val claims = runCatching { withContext(ioDispatcher) { read(seasonNumber) } }.getOrNull()
            serverSeasonReadsInFlight.remove(seasonNumber)
            if (claims != null) {
                serverEpisodeResume = serverEpisodeResume.filterKeys { it.first != seasonNumber } + claims
            }
            _state.update { current ->
                if (current.selectedSeasonNumber != seasonNumber) {
                    current
                } else {
                    current.copy(episodes = projectSeason(seasonNumber))
                }
            }
        }
    }

    private fun episodeRatingItems(
        ratings: List<dev.infusion.core.model.metadata.MediaRating>,
    ): List<RatingUi> =
        episodeRatingsProjection(ratings).map { rating ->
            RatingUi(
                label = rating.label,
                text = dev.infusion.core.model.metadata.ratingScoreText(rating.ratedMetric, rating.value),
                fraction = rating.fraction,
                metricId = rating.metric,
            )
        }

    private fun watchLabel(state: WatchState?): String? = when (state?.status) {
        WatchStatus.IN_PROGRESS -> "In progress"
        WatchStatus.COMPLETED -> "Watched"
        else -> null
    }

    private fun watchLabel(display: WatchedDisplay): String? = when (display) {
        is WatchedDisplay.InProgress -> "In progress"
        WatchedDisplay.Completed -> "Watched"
        WatchedDisplay.Unseen -> null
    }

    private fun locator(ref: ImageRef?, policy: ArtworkPolicy): String? =
        metadataArtworkLocator(ref, policy, artworkProvider)

    private fun formatRating(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
}

private const val NO_REVIEW_IDENTITY: String = "no_proven_identity_for_tracker_reviews"
private const val REVIEW_SOURCE_FAILED: String = "tracker_review_source_failed"

private const val ReviewsMoreBatchTarget: Int = 12
private const val ReviewsMorePagesPerPress: Int = 3
