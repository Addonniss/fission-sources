package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.HomeShelf
import dev.infusion.core.domain.format.DurationFormat
import dev.infusion.core.domain.metadata.CanonicalEpisodeRef
import dev.infusion.core.domain.metadata.CanonicalShowHierarchy
import dev.infusion.core.domain.watch.GetContinueWatchingUseCase
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.feature.vertical.model.HomeItemUi
import dev.infusion.feature.vertical.model.HomeShelfUi
import dev.infusion.feature.vertical.model.HomeUiState
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeStateHolder(
    private val catalog: CatalogPort,
    continueWatching: GetContinueWatchingUseCase,
    private val scope: CoroutineScope,

    refresh: (suspend () -> Unit)? = null,

    private val episodeHierarchy: CanonicalShowHierarchy? = null,

    private val resolveCwTitle: suspend (WatchState) -> CwCatalogInfo? = { state ->
        resolveCwTitleFromCatalog(catalog, state, episodeHierarchy)
    },
) {

    private val cwInfo = MutableStateFlow<Map<WatchKey, CwCatalogInfo>>(emptyMap())

    val state: StateFlow<HomeUiState> = combine(
        catalog.observeHome(),
        continueWatching.observe(),
        cwInfo,
    ) { shelves, watchStates, info ->

        watchStates.forEach { state ->
            if (state.watchKey !in cwInfo.value) {
                scope.launch {
                    val resolved = runCatching { resolveCwTitle(state) }.getOrNull()
                    if (resolved != null) {
                        cwInfo.value = cwInfo.value + (state.watchKey to resolved)
                    }
                }
            }
        }
        buildState(shelves, watchStates, info)
    }.stateIn(scope, SharingStarted.Eagerly, HomeUiState())

    init {
        val action = refresh
        if (action != null) {
            scope.launch { action() }
        }
    }

    private fun buildState(
        shelves: List<HomeShelf>,
        watchStates: List<WatchState>,
        cwInfo: Map<WatchKey, CwCatalogInfo>,
    ): HomeUiState {
        val uiShelves = mutableListOf<HomeShelfUi>()

        val cwStates = watchStates.sortedByDescending { it.lastMeaningfulAt }
        if (cwStates.isNotEmpty()) {
            uiShelves += HomeShelfUi(
                key = "shelf:continue-watching",
                title = "Continue Watching",
                sourceLabel = null,
                items = cwStates.mapNotNull { state ->
                    val title = state.watchKey.titleId ?: state.watchKey.seriesId ?: return@mapNotNull null
                    val info = cwInfo[state.watchKey]
                    val kind = if (state.watchKey.isEpisode) {
                        dev.infusion.core.model.TitleKind.EPISODE
                    } else {
                        dev.infusion.core.model.TitleKind.MOVIE
                    }
                    HomeItemUi(
                        key = "item:${title.value}",
                        titleId = title,

                        preview = dev.infusion.core.model.MediaPreview(
                            titleId = title,
                            kind = kind,
                            displayTitle = info?.displayTitle ?: "Unknown title",

                            year = info?.year,

                            airDate = info?.airDate,

                            subtitle = when {
                                state.watchKey.isEpisode -> {
                                    val s = state.watchKey.seasonNumber
                                    val e = state.watchKey.episodeNumber
                                    if (s != null && e != null) "S${s}E${e}"
                                    else DurationFormat.formatDuration(state.positionMs)
                                }
                                else -> DurationFormat.formatDuration(state.positionMs)
                            },
                            artwork = info?.artworkNativeItemId
                                ?.let { dev.infusion.core.model.MediaArtworkRef.catalog(it) },
                            progressFraction = state.completionFraction,
                        ),
                        seriesTitleId = state.watchKey.seriesId,
                        seasonNumber = state.watchKey.seasonNumber,
                        episodeNumber = state.watchKey.episodeNumber,
                        episodeTitleId = info?.episodeTitleId,
                    )
                },
            )
        }

        shelves.forEach { shelf ->
            uiShelves += HomeShelfUi(
                key = shelf.key,
                title = shelf.title,
                sourceLabel = shelf.sourceLabel,
                items = shelf.items.map { item ->
                    HomeItemUi(
                        key = item.key,
                        titleId = item.titleId,

                        preview = dev.infusion.core.model.MediaPreview(
                            titleId = item.titleId,
                            kind = item.kind,
                            displayTitle = item.displayTitle,
                            year = item.year,
                            subtitle = item.subtitle,
                            artwork = null,
                            progressFraction = null,
                        ),
                    )
                },
            )
        }

        val firstItem = uiShelves.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()?.key
        return HomeUiState(shelves = uiShelves, initialFocusKey = firstItem)
    }
}

data class CwCatalogInfo(

    val displayTitle: String,

    val year: Int? = null,

    val airDate: LocalDate? = null,

    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,

    val episodeTitleId: TitleId? = null,

    val episodeTitle: String? = null,

    val runtimeMinutes: Int? = null,

    val artworkNativeItemId: String? = null,
)

suspend fun resolveCwTitleFromCatalog(
    catalog: CatalogPort,
    state: WatchState,
    episodeHierarchy: CanonicalShowHierarchy? = null,
): CwCatalogInfo? {
    val key = state.watchKey
    val movieId = key.titleId
    if (movieId != null) {
        val details = catalog.observeTitleDetails(movieId).firstOrNull()
            ?: return CwCatalogInfo(displayTitle = "Unknown title")
        val native = catalog.nativeItemIdFor(movieId)
        return CwCatalogInfo(
            displayTitle = details.title.displayTitle,

            year = details.title.year,
            artworkNativeItemId = native,
        )
    }
    val seriesId = key.seriesId ?: return null
    val seasonNumber = key.seasonNumber ?: return null
    val episodeNumber = key.episodeNumber ?: return null

    val seriesDetails = catalog.observeTitleDetails(seriesId).firstOrNull()
    val seriesName = seriesDetails?.title?.displayTitle

    val seriesNative = catalog.nativeItemIdFor(seriesId)
    val episodeTitleId: TitleId? = if (seriesNative != null) {
        val seasons = catalog.children(seriesNative, 0, 200)?.entries.orEmpty()
        val season = seasons.firstOrNull { it.seasonNumber == seasonNumber }
        val seasonNative = season?.nativeItemId?.takeIf { it.isNotBlank() }
        val episodes = seasonNative?.let { catalog.children(it, 0, 200)?.entries.orEmpty() }.orEmpty()
        episodes.firstOrNull { it.episodeNumber == episodeNumber }?.titleId
    } else {
        null
    }

    val native = episodeTitleId?.let { catalog.nativeItemIdFor(it) }

    val episodeRef = episodeRef(episodeHierarchy, seriesId, seasonNumber, episodeNumber)
    return CwCatalogInfo(
        displayTitle = seriesName ?: "Unknown title",

        airDate = episodeRef?.released?.let(::parseIsoDate),
        episodeTitle = episodeRef?.title?.takeIf { it.isNotBlank() },
        runtimeMinutes = episodeRef?.runtimeMinutes,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitleId = episodeTitleId,
        artworkNativeItemId = native,
    )
}

private suspend fun episodeRef(
    episodeHierarchy: CanonicalShowHierarchy?,
    seriesTitleId: TitleId,
    seasonNumber: Int,
    episodeNumber: Int,
): CanonicalEpisodeRef? {
    val hierarchy = episodeHierarchy ?: return null
    return runCatching { hierarchy.episodes(seriesTitleId, seasonNumber) }
        .getOrNull()
        .orEmpty()
        .firstOrNull { it.episodeNumber == episodeNumber }
}

private fun parseIsoDate(released: String): LocalDate? =
    released.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
