package dev.infusion.feature.vertical.details

import dev.infusion.core.domain.catalog.CatalogEntry
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.watch.EpisodeCatalogEntry
import dev.infusion.core.domain.watch.NextUnwatchedDerivation
import dev.infusion.core.domain.watch.WatchStateRepository
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.model.EpisodeIdentityUi
import dev.infusion.feature.vertical.model.EpisodeRowUi
import dev.infusion.feature.vertical.model.SeasonEpisodesPhase
import dev.infusion.feature.vertical.model.SeriesDetailsUiState
import dev.infusion.feature.vertical.model.SeriesHierarchyPhase
import dev.infusion.feature.vertical.model.SeriesSeasonUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesDetailsStateHolder(
    private val titleId: TitleId,
    private val catalog: CatalogPort,
    watchStateRepository: WatchStateRepository,
    private val scope: CoroutineScope,
    refresh: (suspend () -> Unit)? = null,

    private val resolveArtwork: (suspend () -> CatalogImage?)? = null,

    private val hierarchyFailureProvider: (suspend () -> SeriesHierarchyPhase.Failed?)? = null,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val watchedStateDemand: WatchedStateDemand = WatchedStateDemand.None,

    private val additionalHierarchies: List<CatalogPort> = emptyList(),
) {

    private val _state = MutableStateFlow(SeriesDetailsUiState(titleId = titleId))
    val state: StateFlow<SeriesDetailsUiState> = _state

    private var detailsArrived = false
    private var hierarchyAttempts = 0

    private var seasonSelectionIsUserOwned = false

    fun retry() {

        hierarchyAttempts = 0
        scope.launch { loadHierarchy() }
    }

    init {
        val action = refresh
        if (action != null) {
            scope.launch { withContext(ioDispatcher) { action() } }
        }

        scope.launch {
            catalog.observeTitleDetails(titleId).collect { details ->
                if (details != null) {
                    _state.update { current ->
                        current.copy(
                            title = details.title.displayTitle,
                            kindLabel = details.title.kind.name.lowercase(),
                            year = details.title.year,
                            sourceLabel = details.availability.firstOrNull()?.let { "from ${it.kind.name.lowercase()}" },
                            synopsis = details.title.synopsis,

                            genres = details.title.genres,
                            rating = details.title.rating,
                        )
                    }

                    detailsArrived = true
                    reattemptHierarchyIfUnresolved()
                }
            }
        }

        scope.launch { loadHierarchy() }

        scope.launch {
            combine(watchStateRepository.observeAll(), _state) { watchStates, state ->
                watchStates to state.episodesByKey
            }.collect { (watchStates, episodesByKey) ->
                val nextUnwatched = NextUnwatchedDerivation.deriveNextUnwatched(
                    episodesByKey.values.toList(),
                    watchStates.associateBy { it.watchKey },
                )

                val byKey = watchStates.associateBy { it.watchKey }
                val nextUnwatchedUi = nextUnwatched?.let {
                    EpisodeIdentityUi(
                        seriesTitleId = it.seriesTitleId,
                        seasonNumber = it.seasonNumber,
                        episodeNumber = it.episodeNumber,
                        episodeTitleId = it.episodeTitleId,
                    )
                }
                _state.update { current ->
                    current.copy(
                        nextUnwatched = nextUnwatchedUi,
                        episodes = current.episodes.map { row ->
                            row.copy(
                                watchedStatus = watchedOf(byKey[WatchKey.episode(titleId, row.seasonNumber, row.episodeNumber)]),
                            )
                        },
                    )
                }

                autoOpenContinuationSeason(nextUnwatchedUi)
            }
        }

        val artwork = resolveArtwork
        if (artwork != null) {
            scope.launch {
                val img = withContext(ioDispatcher) { artwork() }
                _state.update { it.copy(artworkBytes = img?.bytes) }
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        seasonSelectionIsUserOwned = true
        applySeasonSelection(seasonNumber)
    }

    private fun applySeasonSelection(seasonNumber: Int) {
        scope.launch {
            _state.update { current ->
                current.copy(
                    selectedSeasonNumber = seasonNumber,

                    episodes = emptyList(),
                    episodesPhase = SeasonEpisodesPhase.Loading,
                )
            }
            loadEpisodes(seasonNumber)
        }
    }

    private fun autoOpenContinuationSeason(continuation: EpisodeIdentityUi?) {
        if (continuation == null) return
        if (seasonSelectionIsUserOwned) return
        val current = _state.value
        val seasonNumber = continuation.seasonNumber
        if (current.seasons.none { it.seasonNumber == seasonNumber }) return
        if (current.selectedSeasonNumber == seasonNumber) return

        _state.update { state -> state.copy(initialFocusKey = "season:$seasonNumber") }
        applySeasonSelection(seasonNumber)
    }

    private fun reattemptHierarchyIfUnresolved() {
        if (!detailsArrived) return
        if (hierarchyAttempts >= MAX_HIERARCHY_ATTEMPTS) return
        if (_state.value.hierarchy !is SeriesHierarchyPhase.Empty) return
        scope.launch { loadHierarchy() }
    }

    private suspend fun loadHierarchy() {

        _state.update { it.copy(hierarchy = SeriesHierarchyPhase.Loading) }
        hierarchyAttempts += 1
        val failureProvider = hierarchyFailureProvider
        val seriesNativeId = withContext(ioDispatcher) { catalog.nativeItemIdFor(titleId) }
        if (seriesNativeId == null) {

            val failure = failureProvider?.let { withContext(ioDispatcher) { it() } }
            if (failure != null) {
                _state.update { current ->
                    current.copy(
                        hierarchy = failure,
                        seasons = emptyList(),
                        episodesByKey = emptyMap(),
                        initialFocusKey = "retry",
                    )
                }
                return
            }

            _state.update {
                it.copy(
                    hierarchy = SeriesHierarchyPhase.Empty,
                    seasons = emptyList(),
                    episodesByKey = emptyMap(),
                    initialFocusKey = "back",
                )
            }
            reattemptHierarchyIfUnresolved()
            return
        }
        val childrenPage = withContext(ioDispatcher) { catalog.children(seriesNativeId, 0, 200) }
        if (childrenPage == null) {

            val failure = failureProvider?.let { withContext(ioDispatcher) { it() } }
            if (failure != null) {
                _state.update { current ->
                    current.copy(
                        hierarchy = failure,
                        seasons = emptyList(),
                        episodesByKey = emptyMap(),
                        initialFocusKey = "retry",
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    hierarchy = SeriesHierarchyPhase.Empty,
                    seasons = emptyList(),
                    episodesByKey = emptyMap(),
                    initialFocusKey = "back",
                )
            }
            reattemptHierarchyIfUnresolved()
            return
        }
        val seasons = childrenPage.entries
        val seasonRows = seasons.mapNotNull { season ->
            val seasonNumber = season.seasonNumber ?: return@mapNotNull null
            val seasonNative = season.nativeItemId.takeIf { it.isNotBlank() }
            val episodeCount = season.childCount ?: seasonNative?.let {
                withContext(ioDispatcher) { catalog.children(it, 0, 1)?.totalCount }
            }
            SeriesSeasonUi(
                key = "season:$seasonNumber",
                seasonNumber = seasonNumber,
                title = season.displayTitle,
                episodeCount = episodeCount,
                seasonTitleId = season.titleId,
                nativeItemId = seasonNative,
            )
        }

        val baseEpisodes = episodesFor(catalog, seasons)
        val episodesByKey: Map<WatchKey, EpisodeCatalogEntry> = LinkedHashMap(baseEpisodes).apply {
            for (extra in additionalHierarchies) {
                val extraNative = withContext(ioDispatcher) { extra.nativeItemIdFor(titleId) }
                    ?.takeIf { it.isNotBlank() } ?: continue
                val extraSeasons = withContext(ioDispatcher) {
                    extra.children(extraNative, 0, 200)?.entries ?: emptyList()
                }
                if (extraSeasons.isEmpty()) continue
                for ((key, entry) in episodesFor(extra, extraSeasons)) putIfAbsent(key, entry)
            }
        }.toMap()

        val firstSeason = seasonRows.firstOrNull()
        _state.update { current ->
            current.copy(

                hierarchy = if (seasonRows.isEmpty()) SeriesHierarchyPhase.Empty else SeriesHierarchyPhase.Loaded,
                seasons = seasonRows,

                selectedSeasonNumber = firstSeason?.seasonNumber,
                episodesByKey = episodesByKey,
                initialFocusKey = firstSeason?.key ?: "back",
            )
        }

        if (seasonRows.isEmpty()) reattemptHierarchyIfUnresolved()

        firstSeason?.seasonNumber?.let { seasonNumber ->
            loadEpisodes(seasonNumber)
        }

    }

    private suspend fun episodesFor(
        source: CatalogPort,
        seasons: List<CatalogEntry>,
    ): Map<WatchKey, EpisodeCatalogEntry> {
        val byKey = LinkedHashMap<WatchKey, EpisodeCatalogEntry>()
        for (season in seasons) {
            val seasonNative = season.nativeItemId.takeIf { it.isNotBlank() } ?: continue
            val seasonNumber = season.seasonNumber ?: continue
            val episodes = withContext(ioDispatcher) {
                source.children(seasonNative, 0, 200)?.entries ?: emptyList()
            }
            for (episode in episodes) {
                val episodeNumber = episode.episodeNumber ?: continue
                val episodeTitleId = episode.titleId ?: continue
                byKey.putIfAbsent(
                    WatchKey.episode(titleId, seasonNumber, episodeNumber),
                    EpisodeCatalogEntry(
                        seriesTitleId = titleId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitleId = episodeTitleId,
                        displayTitle = episode.displayTitle,
                    ),
                )
            }
        }
        return byKey
    }

    private suspend fun loadEpisodes(seasonNumber: Int) {
        val seasonRow = _state.value.seasons.firstOrNull { it.seasonNumber == seasonNumber }
        val seasonNative = seasonRow?.nativeItemId?.takeIf { it.isNotBlank() }
        if (seasonNative == null) {
            publishEpisodes(seasonNumber, emptyList(), SeasonEpisodesPhase.Empty)
            return
        }
        val page = withContext(ioDispatcher) { catalog.children(seasonNative, 0, 200) }
        if (page == null) {
            val failure = hierarchyFailureProvider?.let { withContext(ioDispatcher) { it() } }
            publishEpisodes(
                seasonNumber = seasonNumber,
                episodes = emptyList(),
                phase = if (failure != null) {
                    SeasonEpisodesPhase.Failed(failure.message)
                } else {
                    SeasonEpisodesPhase.Empty
                },
            )
            return
        }
        val projected = page.entries.mapNotNull { episode ->
            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
            val episodeTitleId = episode.titleId ?: return@mapNotNull null
            EpisodeRowUi(
                key = "episode:$seasonNumber:$episodeNumber",
                titleId = episodeTitleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                displayTitle = episode.displayTitle,
                watchedStatus = dev.infusion.feature.vertical.model.WatchedDisplay.Unseen,
                nativeItemId = episode.nativeItemId.takeIf { it.isNotBlank() },
            )
        }
        publishEpisodes(
            seasonNumber = seasonNumber,
            episodes = projected,
            phase = if (projected.isEmpty()) SeasonEpisodesPhase.Empty else SeasonEpisodesPhase.Loaded,
        )
    }

    private fun publishEpisodes(
        seasonNumber: Int,
        episodes: List<EpisodeRowUi>,
        phase: SeasonEpisodesPhase,
    ) {
        _state.update { current ->
            if (current.selectedSeasonNumber != seasonNumber) {
                current
            } else {
                current.copy(episodes = episodes, episodesPhase = phase)
            }
        }

        if (_state.value.selectedSeasonNumber == seasonNumber && episodes.isNotEmpty()) {
            watchedStateDemand.demandAll(
                episodes.map { WatchKey.episode(titleId, it.seasonNumber, it.episodeNumber) },
            )
        }
    }

    private fun watchedOf(state: dev.infusion.core.model.watch.WatchState?): dev.infusion.feature.vertical.model.WatchedDisplay =
        when (state?.status) {
            dev.infusion.core.model.watch.WatchStatus.IN_PROGRESS ->
                dev.infusion.feature.vertical.model.WatchedDisplay.InProgress(state.positionMs, state.completionFraction)
            dev.infusion.core.model.watch.WatchStatus.COMPLETED ->
                dev.infusion.feature.vertical.model.WatchedDisplay.Completed
            else -> dev.infusion.feature.vertical.model.WatchedDisplay.Unseen
        }

    companion object {

        const val MAX_HIERARCHY_ATTEMPTS = 2
    }
}
