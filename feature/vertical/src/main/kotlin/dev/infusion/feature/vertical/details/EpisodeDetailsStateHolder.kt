package dev.infusion.feature.vertical.details

import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.watch.WatchStateRepository
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.feature.vertical.model.EpisodeDetailsUiState
import dev.infusion.feature.vertical.model.PrimaryAction
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

class EpisodeDetailsStateHolder(
    private val seriesTitleId: TitleId,
    private val seasonNumber: Int,
    private val episodeNumber: Int,
    private val episodeTitleId: TitleId,
    catalog: CatalogPort,
    watchStateRepository: WatchStateRepository,
    private val scope: CoroutineScope,

    private val resumeHintMs: Long? = null,

    private val serverResume: (suspend () -> ServerResumePosition?)? = null,
    private val selectedEditionLabel: () -> String? = { null },

    private val resolveArtwork: (suspend () -> CatalogImage?)? = null,

    private val resolveParentShowBackdrop: (suspend () -> String?)? = null,

    private val resolveParentShowLogo: (suspend () -> String?)? = null,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val watchedStateDemand: WatchedStateDemand = WatchedStateDemand.None,
) {

    private val watchKey: WatchKey = WatchKey.episode(seriesTitleId, seasonNumber, episodeNumber)

    private val hintResumeMs: Long? = resumeHintMs?.takeIf { it > 0L }

    private val serverResumeState = MutableStateFlow<ServerResumePosition?>(null)

    private val _state = MutableStateFlow(
        EpisodeDetailsUiState(
            seriesTitleId = seriesTitleId,
            titleId = episodeTitleId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,

            watched = hintResumeMs?.let { WatchedDisplay.InProgress(it) } ?: WatchedDisplay.Unseen,
            resumePositionMs = hintResumeMs,

            primaryAction = detailsPrimaryAction(
                resumable = hintResumeMs != null,
                identityEstablished = true,
            ),
            initialFocusKey = "primary",
        ),
    )
    val state: StateFlow<EpisodeDetailsUiState> = _state

    init {

        watchedStateDemand.demand(watchKey)

        scope.launch {
            catalog.observeTitleDetails(episodeTitleId).collect { details ->
                if (details != null) {
                    _state.update { current ->
                        current.copy(
                            displayTitle = details.title.displayTitle,
                            synopsis = details.title.synopsis,
                            hasAvailability = details.availability.isNotEmpty(),

                            sourceLabel = details.availability.firstOrNull()?.let { "from ${it.kind.name.lowercase()}" },
                            selectedEditionLabel = selectedEditionLabel(),

                            primaryAction = detailsPrimaryAction(
                                resumable = current.watched is WatchedDisplay.InProgress &&
                                    current.resumePositionMs != null,
                                identityEstablished = true,
                            ),
                            unplayableReason = null,
                        )
                    }
                }
            }
        }

        scope.launch {
            val seriesTitle = runCatching {
                catalog.observeTitleDetails(seriesTitleId).first()?.title?.displayTitle
            }.getOrNull()
            _state.update { it.copy(seriesTitle = seriesTitle) }
        }

        val serverResumeSeam = serverResume
        if (serverResumeSeam != null) {
            scope.launch {
                val resolved = try {
                    withContext(ioDispatcher) { serverResumeSeam() }
                } catch (_: Exception) {
                    null
                }
                serverResumeState.value = resolved
            }
        }

        scope.launch {
            combine(
                watchStateRepository.observeAll(),
                serverResumeState,
            ) { watchStates, serverPosition -> watchStates to serverPosition }
                .collect { (watchStates, serverPosition) ->
                    val state = watchStates.firstOrNull { it.watchKey == watchKey }
                    val localWatched: WatchedDisplay = when (state?.status) {
                        WatchStatus.IN_PROGRESS -> WatchedDisplay.InProgress(state.positionMs, state.completionFraction)
                        WatchStatus.COMPLETED -> WatchedDisplay.Completed
                        else -> WatchedDisplay.Unseen
                    }

                    val displayWatched: WatchedDisplay = effectiveWatched(
                        serverResume = serverPosition,
                        local = localWatched,
                        localStatePresent = state != null,
                        hintResumeMs = hintResumeMs,
                        localObservedAt = state?.lastMeaningfulAt,
                    )
                    val resumePositionMs = (displayWatched as? WatchedDisplay.InProgress)?.positionMs?.takeIf { it > 0 }
                    _state.update { current ->
                        current.copy(
                            watched = displayWatched,
                            resumePositionMs = resumePositionMs,

                            primaryAction = detailsPrimaryAction(
                                resumable = displayWatched is WatchedDisplay.InProgress && resumePositionMs != null,
                                identityEstablished = true,
                            ),
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

        scope.launch {
            val still = withContext(ioDispatcher) {
                EpisodeDetailsArtwork.foregroundRef(catalog.nativeItemIdFor(episodeTitleId))
            }
            _state.update { it.copy(episodeStillRef = still) }
        }

        val backdrop = resolveParentShowBackdrop
        if (backdrop != null) {
            scope.launch {
                val locator = withContext(ioDispatcher) { backdrop() }
                _state.update { it.copy(parentShowBackdropLocator = locator) }
            }
        }

        val logo = resolveParentShowLogo
        if (logo != null) {
            scope.launch {
                val locator = withContext(ioDispatcher) { logo() }
                _state.update { it.copy(parentShowLogoLocator = locator) }
            }
        }
    }
}
