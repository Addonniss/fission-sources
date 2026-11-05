package dev.infusion.feature.vertical.details

import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.watch.WatchStateRepository
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.feature.vertical.model.EpisodeRowUi
import dev.infusion.feature.vertical.model.SeasonEpisodesUiState
import dev.infusion.feature.vertical.model.WatchedDisplay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeasonEpisodesStateHolder(
    private val seriesTitleId: TitleId,
    private val seasonNumber: Int,
    private val seasonTitleId: TitleId,
    private val catalog: CatalogPort,
    watchStateRepository: WatchStateRepository,
    private val scope: CoroutineScope,

    private val resolveArtwork: (suspend () -> CatalogImage?)? = null,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val watchedStateDemand: WatchedStateDemand = WatchedStateDemand.None,

    private val serverSeasonResume: (suspend (seasonNumber: Int) -> Map<Pair<Int, Int>, ServerResumePosition>)? = null,
) {

    private val _state = MutableStateFlow(
        SeasonEpisodesUiState(
            seriesTitleId = seriesTitleId,
            seriesTitle = null,
            seasonNumber = seasonNumber,
            episodeCount = 0,
            episodes = emptyList(),
        ),
    )
    val state: StateFlow<SeasonEpisodesUiState> = _state

    @Volatile
    private var serverClaims: Map<Pair<Int, Int>, ServerResumePosition> = emptyMap()

    init {

        scope.launch { loadSeason() }

        scope.launch {
            combine(watchStateRepository.observeAll(), _state) { watchStates, _ ->
                watchStates
            }.collect { watchStates ->
                val byKey = watchStates.associateBy { it.watchKey }
                val claims = serverClaims
                _state.update { current ->
                    current.copy(
                        episodes = current.episodes.map { row ->
                            row.copy(
                                watchedStatus = displayOf(
                                    claims,
                                    row.seasonNumber,
                                    row.episodeNumber,
                                    byKey[WatchKey.episode(seriesTitleId, row.seasonNumber, row.episodeNumber)],
                                ),
                            )
                        },
                    )
                }
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

    private suspend fun loadSeason() {

        val claims = serverSeasonResume?.let { read ->
            runCatching { withContext(ioDispatcher) { read(seasonNumber) } }.getOrNull()
        }.orEmpty()
        serverClaims = claims
        val seasonNativeId = withContext(ioDispatcher) {
            catalog.nativeItemIdFor(seasonTitleId)
                ?: catalog.nativeItemIdFor(seriesTitleId)?.let { seriesNative ->

                    catalog.children(seriesNative, 0, 200)?.entries
                        ?.firstOrNull { it.seasonNumber == seasonNumber }
                        ?.nativeItemId
                        ?.takeIf { it.isNotBlank() }
                }
        }
        if (seasonNativeId == null) {

            _state.update { it.copy(initialFocusKey = "back") }
            return
        }
        val seriesTitle = withContext(ioDispatcher) {
            runCatching {
                catalog.observeTitleDetails(seriesTitleId).first()?.title?.displayTitle
            }.getOrNull()
        }
        val entries = withContext(ioDispatcher) {
            catalog.children(seasonNativeId, 0, 200)?.entries ?: emptyList()
        }
        val rows = entries.mapNotNull { episode ->
            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
            val episodeTitleId = episode.titleId ?: return@mapNotNull null
            EpisodeRowUi(
                key = "episode:$seasonNumber:$episodeNumber",
                titleId = episodeTitleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                displayTitle = episode.displayTitle,
                watchedStatus = displayOf(claims, seasonNumber, episodeNumber, local = null),
            )
        }

        _state.update { current ->
            current.copy(
                seriesTitle = seriesTitle,
                episodeCount = rows.size,
                episodes = rows,
                initialFocusKey = rows.firstOrNull()?.key ?: "back",
            )
        }

        if (rows.isNotEmpty()) {
            watchedStateDemand.demandAll(
                rows.map { WatchKey.episode(seriesTitleId, it.seasonNumber, it.episodeNumber) },
            )
        }
    }

    private fun watchedOf(state: dev.infusion.core.model.watch.WatchState?): WatchedDisplay = when (state?.status) {
        WatchStatus.IN_PROGRESS -> WatchedDisplay.InProgress(state.positionMs, state.completionFraction)
        WatchStatus.COMPLETED -> WatchedDisplay.Completed
        else -> WatchedDisplay.Unseen
    }

    private fun displayOf(
        claims: Map<Pair<Int, Int>, ServerResumePosition>,
        seasonNumber: Int,
        episodeNumber: Int,
        local: dev.infusion.core.model.watch.WatchState?,
    ): WatchedDisplay = effectiveWatched(
        serverResume = claims[seasonNumber to episodeNumber],
        local = watchedOf(local),
        localStatePresent = local != null,
        hintResumeMs = null,
        localObservedAt = local?.lastMeaningfulAt,
    )
}
