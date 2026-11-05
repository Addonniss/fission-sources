package dev.infusion.feature.vertical.details

import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.domain.preview.DetailsBootstrapMetrics
import dev.infusion.core.domain.preview.DetailsBootstrapRecord
import dev.infusion.core.domain.preview.TitlePreviewHandoff
import dev.infusion.core.domain.preview.TitlePreviewHint
import dev.infusion.core.domain.watch.WatchStateRepository
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.feature.vertical.model.DetailsArtwork
import dev.infusion.feature.vertical.model.DetailsPhase
import dev.infusion.feature.vertical.model.DetailsUiState
import dev.infusion.feature.vertical.model.WatchedDisplay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailsStateHolder(
    private val titleId: TitleId,

    private val watchKey: WatchKey? = null,

    private val resumeHintMs: Long? = null,

    private val serverResume: (suspend () -> ServerResumePosition?)? = null,
    catalog: CatalogPort,
    watchStateRepository: WatchStateRepository,
    scope: CoroutineScope,

    refresh: (suspend () -> Unit)? = null,

    private val selectedEditionLabel: () -> String? = { null },

    private val resolveArtwork: (suspend () -> CatalogImage?)? = null,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val handoff: TitlePreviewHandoff? = null,

    private val metrics: DetailsBootstrapMetrics = DetailsBootstrapMetrics.None,

    private val watchedStateDemand: WatchedStateDemand = WatchedStateDemand.None,
) {

    private val effectiveWatchKey: WatchKey = watchKey ?: WatchKey.movie(titleId)

    private val sourceLabels: Map<dev.infusion.core.model.SourceId, String> =
        catalog.sources().associate { it.sourceId to it.displayName }

    private val hint: TitlePreviewHint? = handoff?.take(titleId)?.takeIf { candidate ->
        candidate.preview.titleId == null || candidate.preview.titleId == titleId
    }

    private val hintNanos: Long? = hint?.queuedAtNanos

    private val resumeHint = MutableStateFlow(resumeHintMs?.takeIf { it > 0L })

    private val serverResumeState = MutableStateFlow<ServerResumePosition?>(null)

    @Volatile private var serverResumeGeneration = 0L

    private val holderScope: CoroutineScope = scope

    fun refreshServerResume() {
        val seam = serverResume ?: return
        val generation = ++serverResumeGeneration

        serverResumeState.value = null
        holderScope.launch {
            val resolved = try {
                withContext(ioDispatcher) { seam() }
            } catch (_: Exception) {
                null
            }

            if (generation != serverResumeGeneration) return@launch
            serverResumeState.value = resolved
        }
    }

    @Volatile private var facetsReported = false
    @Volatile private var lastDetails: TitleDetails? = null
    @Volatile private var lastWatchState: WatchState? = null

    fun publishResumeHint(resumeHintMs: Long?) {
        val hint = resumeHintMs?.takeIf { it > 0L }
        resumeHint.value = hint
        _state.value = if (facetsReported) {
            buildState(lastDetails, lastWatchState, hint, serverResumeState.value)
        } else {

            hintState()
        }
    }

    private var hintLatencyMs: Long? = null

    private var hydratedReported = false

    private val artwork = MutableStateFlow(DetailsArtwork(ref = hint?.preview?.artwork))

    private val _state = MutableStateFlow(hintState())

    val state: StateFlow<DetailsUiState> = _state

    val artworkState: StateFlow<DetailsArtwork> = artwork

    init {

        watchedStateDemand.demand(effectiveWatchKey)

        reportHintFrame()
        val action = refresh
        if (action != null) {
            scope.launch { withContext(ioDispatcher) { action() } }
        }
        scope.launch {
            combine(
                catalog.observeTitleDetails(titleId),

                watchStateRepository.observe(effectiveWatchKey),

                resumeHint,

                serverResumeState,
            ) { details, watchState, _, serverState -> Triple(details, watchState, serverState) }
                .collect { (details, watchState, serverState) ->

                    lastDetails = details
                    lastWatchState = watchState
                    facetsReported = true
                    val published = buildState(details, watchState, resumeHint.value, serverState)
                    _state.value = published
                    reportHydrated(published)
                }
        }
        val artworkResolver = resolveArtwork
        if (artworkResolver != null) {
            scope.launch {
                val resolved = withContext(ioDispatcher) { artworkResolver() }
                if (resolved != null) {

                    artwork.value = DetailsArtwork(bytes = resolved.bytes, available = true)
                }

            }
        }
    }

    private fun hintState(): DetailsUiState = DetailsUiState(
        titleId = titleId,
        title = hint?.preview?.displayTitle,
        kindLabel = hint?.preview?.kind?.name?.lowercase(),
        year = hint?.preview?.year,
        subtitle = hint?.preview?.subtitle,
        watched = resumeHint.value?.let { WatchedDisplay.InProgress(it) } ?: WatchedDisplay.Unseen,
        resumePositionMs = resumeHint.value,
        phase = DetailsPhase.Loading,
        primaryAction = detailsPrimaryAction(
            resumable = resumeHint.value != null,
            identityEstablished = hint != null,
        ),
        initialFocusKey = if (hint == null) "back" else "primary",
    )

    private fun buildState(
        details: TitleDetails?,
        watchState: WatchState?,
        hintResumeMs: Long?,
        serverResume: ServerResumePosition?,
    ): DetailsUiState {
        if (details == null) {

            val identityKnown = hint != null

            val localWatched: WatchedDisplay = when (watchState?.status) {
                WatchStatus.IN_PROGRESS -> WatchedDisplay.InProgress(watchState.positionMs, watchState.completionFraction)
                WatchStatus.COMPLETED -> WatchedDisplay.Completed
                else -> WatchedDisplay.Unseen
            }
            val watched: WatchedDisplay = effectiveWatched(
                serverResume = serverResume,
                local = localWatched,
                localStatePresent = watchState != null,
                hintResumeMs = hintResumeMs,
                localObservedAt = watchState?.lastMeaningfulAt,
            )
            val resumePositionMs = (watched as? WatchedDisplay.InProgress)?.positionMs?.takeIf { it > 0 }
            return DetailsUiState(
                titleId = titleId,

                title = hint?.preview?.displayTitle,
                kindLabel = hint?.preview?.kind?.name?.lowercase(),
                year = hint?.preview?.year,
                subtitle = hint?.preview?.subtitle,
                watched = watched,
                resumePositionMs = resumePositionMs,

                primaryAction = detailsPrimaryAction(
                    resumable = watched is WatchedDisplay.InProgress && resumePositionMs != null,
                    identityEstablished = identityKnown,
                ),
                unplayableReason = if (identityKnown) {
                    null
                } else {
                    "Title is not available in the demo catalog."
                },

                phase = if (identityKnown) null else DetailsPhase.NoSource,
                initialFocusKey = if (identityKnown) "primary" else "back",
            )
        }
        val hasAvailability = details.availability.isNotEmpty()
        val edition = details.editions.firstOrNull()
        val localWatched: WatchedDisplay = when (watchState?.status) {
            WatchStatus.IN_PROGRESS -> WatchedDisplay.InProgress(watchState.positionMs, watchState.completionFraction)
            WatchStatus.COMPLETED -> WatchedDisplay.Completed
            else -> WatchedDisplay.Unseen
        }

        val watched: WatchedDisplay = effectiveWatched(
            serverResume = serverResume,
            local = localWatched,
            localStatePresent = watchState != null,
            hintResumeMs = hintResumeMs,
            localObservedAt = watchState?.lastMeaningfulAt,
        )
        val resumePositionMs = (watched as? WatchedDisplay.InProgress)?.positionMs?.takeIf { it > 0 }

        val primaryAction = detailsPrimaryAction(
            resumable = watched is WatchedDisplay.InProgress && resumePositionMs != null,
            identityEstablished = true,
        )
        return DetailsUiState(
            titleId = titleId,
            title = details.title.displayTitle,
            kindLabel = details.title.kind.name.lowercase(),
            year = details.title.year,

            subtitle = null,
            editionLabel = edition?.label,
            sourceLabel = details.availability.firstOrNull()?.let { "from ${it.kind.name.lowercase()}" },
            synopsis = details.title.synopsis,
            synopsisSourceLabel = details.title.synopsisProvenance?.sourceId?.let { sourceLabels[it] },

            genres = details.title.genres,
            rating = details.title.rating,
            resumePositionMs = resumePositionMs,
            watched = watched,
            primaryAction = primaryAction,

            unplayableReason = null,
            selectedEditionLabel = selectedEditionLabel(),
            hasAvailability = hasAvailability,
            phase = null,
            initialFocusKey = "primary",
        )
    }

    private fun reportHintFrame() {
        val start = hintNanos ?: return
        val ms = elapsedMs(start)
        hintLatencyMs = ms
        metrics.measured(
            DetailsBootstrapRecord(
                titleId = titleId.value.toString(),
                hintMs = ms,
                fullMs = null,
            ),
        )
    }

    private fun reportHydrated(published: DetailsUiState) {
        if (hydratedReported) return
        val start = hintNanos ?: return
        if (published.title == null || published.phase != null) return
        hydratedReported = true
        val fullMs = elapsedMs(start)
        metrics.measured(
            DetailsBootstrapRecord(
                titleId = titleId.value.toString(),
                hintMs = hintLatencyMs ?: fullMs,
                fullMs = fullMs,
            ),
        )
    }

    private fun elapsedMs(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
}
