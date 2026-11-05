package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.format.DurationFormat
import dev.infusion.core.domain.watch.GetContinueWatchingUseCase
import dev.infusion.core.model.EpisodeCardIdentification
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.feature.vertical.jellyfin.JellyfinRowFetch
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeCwCard(

    val key: String,

    val preview: MediaPreview,

    val provenanceLabel: String?,

    val origin: HomeCwOrigin,
)

sealed interface HomeCwOrigin {

    data class Local(
        val state: WatchState,

        val titleId: TitleId,
        val kind: TitleKind,
        val seriesTitleId: TitleId? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val episodeTitleId: TitleId? = null,
    ) : HomeCwOrigin

    data class Jellyfin(val item: JellyfinTabRowItem) : HomeCwOrigin
}

sealed interface HomeContinueWatchingRow {

    data object Loading : HomeContinueWatchingRow

    data object Empty : HomeContinueWatchingRow

    data class Loaded(val cards: List<HomeCwCard>) : HomeContinueWatchingRow

    data class Failed(val message: String) : HomeContinueWatchingRow
}

data class HomeContinueWatchingUiState(
    val row: HomeContinueWatchingRow = HomeContinueWatchingRow.Loading,

    val segmentNote: String? = null,

    val initialFocusKey: String? = null,
)

private sealed interface JellyfinSegment {

    data object Disabled : JellyfinSegment

    data object Loading : JellyfinSegment

    data class Loaded(val items: List<JellyfinTabRowItem>) : JellyfinSegment

    data class Failed(val message: String) : JellyfinSegment
}

class HomeContinueWatchingStateHolder(
    private val localContinueWatching: GetContinueWatchingUseCase,
    private val scope: CoroutineScope,
    private val jellyfin: (suspend () -> JellyfinRowFetch)? = null,
    private val resolveCwTitle: suspend (WatchState) -> CwCatalogInfo? = { null },
    private val provenanceFor: suspend (WatchState) -> String? = { null },
    private val artworkFor: suspend (WatchState) -> MediaArtworkRef? = { null },
    private val jellyfinSourceId: SourceId? = null,

    private val trace: (String) -> Unit = {},

    private val presentationWindows: HomeCwPresentationWindows? = null,

    private val presentationCache: dev.infusion.core.domain.home.HomePresentationStore? = null,

    private val presentationProfile: () -> String = { "" },

    private val localeToken: () -> String = { "" },

    private val nowMs: () -> Long = { System.currentTimeMillis() },

    private val playbackActive: StateFlow<Boolean> = MutableStateFlow(false),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _localStates = MutableStateFlow<List<WatchState>>(emptyList())
    private val _cwInfo = MutableStateFlow<Map<WatchKey, CwCatalogInfo>>(emptyMap())
    private val _provenance = MutableStateFlow<Map<WatchKey, String?>>(emptyMap())
    private val _artwork = MutableStateFlow<Map<WatchKey, MediaArtworkRef>>(emptyMap())
    private val _jellyfin = MutableStateFlow<JellyfinSegment>(JellyfinSegment.Disabled)
    private val _state = MutableStateFlow(HomeContinueWatchingUiState())
    val state: StateFlow<HomeContinueWatchingUiState> = _state.asStateFlow()

    private var localLoaded = false

    private var lastBoundaryTrace: String? = null

    private val presenting: MutableSet<WatchKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val surfacePresent = MutableStateFlow(true)

    @Volatile
    private var jellyfinRequested: Boolean = false

    @Volatile
    private var jellyfinPending: Boolean = false

    @Volatile
    private var jellyfinJob: Job? = null

    private fun browsingWorkAllowed(): Boolean = surfacePresent.value && !playbackActive.value

    init {

        scope.launch {
            combine(surfacePresent, playbackActive) { present, playing -> present && !playing }
                .distinctUntilChanged()
                .collectLatest { allowed ->
                    if (!allowed) {
                        jellyfinJob?.cancel()
                        return@collectLatest
                    }

                    if (jellyfinPending && jellyfinRequested) {
                        jellyfinPending = false
                        fetchJellyfin()
                    }
                    collectLedger()
                }
        }
    }

    private suspend fun collectLedger() = coroutineScope {
        val work = this
        localContinueWatching.observe().collect { states ->
            localLoaded = true
            _localStates.value = states

            publish()

            seedPresentation(states)
            presentNewKeys(states, work)
        }
    }

    fun onSurfacePresent() {
        surfacePresent.value = true
    }

    fun onSurfaceAbsent() {
        surfacePresent.value = false
    }

    fun reportPresentationWindow(shelfKey: String, materialized: Int) {
        presentationWindows?.report(shelfKey, materialized)
    }

    private suspend fun seedPresentation(states: List<WatchState>) {
        val cache = presentationCache ?: return
        val profile = presentationProfile()
        if (profile.isBlank()) return
        val pending = states
            .distinctBy { it.watchKey }
            .filter { state ->
                val key = state.watchKey
                key !in _cwInfo.value && key !in _artwork.value && !_provenance.value.containsKey(key)
            }
        if (pending.isEmpty()) return
        val now = nowMs()
        val records = withContext(ioDispatcher) {
            pending.mapNotNull { state ->
                val record = runCatching {
                    cache.readPresentation(profile, presentationIdentityKey(state.watchKey))
                }.getOrNull() ?: return@mapNotNull null
                if (!dev.infusion.core.domain.home.HomePresentationPolicy
                        .presentationIsFresh(record, now)
                ) {
                    return@mapNotNull null
                }
                state to record
            }
        }
        if (records.isEmpty()) return
        records.forEach { (state, record) ->
            val key = state.watchKey
            if (key !in _cwInfo.value && record.displayTitle.isNotBlank()) {
                _cwInfo.value = _cwInfo.value + (
                    key to CwCatalogInfo(
                        displayTitle = record.displayTitle,
                        year = record.year,
                        airDate = record.airDate,
                        episodeTitleId = record.episodeTitleId,
                        episodeTitle = record.episodeTitle,
                        runtimeMinutes = record.runtimeMinutes,
                    )
                    )
            }
            if (!_provenance.value.containsKey(key) && record.provenanceLabel != null) {
                _provenance.value = _provenance.value + (key to record.provenanceLabel)
            }
            if (key !in _artwork.value) {

                val artwork = durableArtworkFor(state, record.artwork)
                if (artwork != null) _artwork.value = _artwork.value + (key to artwork)
            }
        }
        publish()
    }

    private fun durableArtworkFor(
        state: WatchState,
        artwork: dev.infusion.core.model.MediaArtworkRef?,
    ): dev.infusion.core.model.MediaArtworkRef? {
        val reusable = dev.infusion.core.domain.home.HomePresentationPolicy.reusableArtwork(artwork)
            ?: return null
        if (!state.watchKey.isEpisode) return reusable

        if (reusable.policy == dev.infusion.core.model.ArtworkPolicy.BACKDROP) return null
        if (dev.infusion.core.model.ArtworkLocator.decode(reusable.locator)
                !is dev.infusion.core.model.ArtworkLocator.Target.Still
        ) {
            return reusable
        }

        val anchorSource = state.anchor?.nativeItemId?.sourceId ?: return null
        val server = jellyfinSourceId ?: return null
        return reusable.takeIf { anchorSource == server }
    }

    private suspend fun persistPresentation(state: WatchState) {
        val key = state.watchKey
        val cache = presentationCache ?: return
        val profile = presentationProfile()
        if (profile.isBlank()) return
        val info = _cwInfo.value[key]
        val provenance = _provenance.value[key]

        val artwork = durableArtworkFor(state, _artwork.value[key])
        if (info == null && provenance == null && artwork == null) return
        val record = dev.infusion.core.domain.home.HomeIdentityPresentation(
            identityKey = presentationIdentityKey(key),
            displayTitle = info?.displayTitle.orEmpty(),
            year = info?.year,
            airDate = info?.airDate,
            episodeTitleId = info?.episodeTitleId,
            episodeTitle = info?.episodeTitle,
            runtimeMinutes = info?.runtimeMinutes,
            provenanceLabel = provenance,
            artwork = artwork,
            storedAtEpochMs = nowMs(),
        )
        if (record.isEmpty()) return
        withContext(ioDispatcher) { runCatching { cache.writePresentation(profile, record) } }
    }

    private fun presentationIdentityKey(key: WatchKey): String =
        "${localCardKey(key)}#l=${localeToken()}"

    fun refresh(includeJellyfin: Boolean) {
        jellyfinRequested = includeJellyfin
        if (!includeJellyfin) {
            jellyfinPending = false
            jellyfinJob?.cancel()
            if (_jellyfin.value is JellyfinSegment.Disabled) return
            _jellyfin.value = JellyfinSegment.Disabled
            publish()
            return
        }
        if (jellyfin == null) {
            if (_jellyfin.value is JellyfinSegment.Disabled) return
            _jellyfin.value = JellyfinSegment.Disabled
            publish()
            return
        }
        if (!browsingWorkAllowed()) {
            jellyfinPending = true
            return
        }
        fetchJellyfin()
    }

    private fun fetchJellyfin() {
        val port = jellyfin ?: return
        if (jellyfinJob?.isActive == true) {

            jellyfinPending = true
            return
        }
        if (_jellyfin.value !is JellyfinSegment.Loaded) {
            _jellyfin.value = JellyfinSegment.Loading
            publish()
        }
        jellyfinJob = scope.launch {
            val next = try {
                when (val result = withContext(ioDispatcher) { port() }) {
                    is JellyfinRowFetch.Ok -> JellyfinSegment.Loaded(result.items)
                    is JellyfinRowFetch.Failed -> JellyfinSegment.Failed(result.message)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {

                JellyfinSegment.Failed("Could not load this row.")
            }
            if (!browsingWorkAllowed()) {

                jellyfinPending = true
                return@launch
            }
            _jellyfin.value = next
            publish()
            if (jellyfinPending) {
                jellyfinPending = false
                fetchJellyfin()
            }
        }
    }

    private fun presentNewKeys(states: List<WatchState>, work: CoroutineScope) {

        var movieIndex = 0
        var episodeIndex = 0
        states.forEachIndexed { unifiedIndex, state ->
            val key = state.watchKey
            val episodic = key.isEpisode
            val familyIndex = if (episodic) episodeIndex++ else movieIndex++
            val needsTitle = key !in _cwInfo.value
            val needsProvenance = !_provenance.value.containsKey(key)
            val needsArtwork = !_artwork.value.containsKey(key)
            if (!needsTitle && !needsProvenance && !needsArtwork) return@forEachIndexed
            if (!presenting.add(key)) return@forEachIndexed

            work.launch {
                try {

                    presentationWindows?.awaitPresented(unifiedIndex, familyIndex, episodic)
                    val facts = mutableListOf<Job>()
                    if (needsTitle) {
                        facts += launch {
                            val info = withContext(ioDispatcher) { resolveCwTitle(state) }
                            if (info != null) {
                                _cwInfo.value = _cwInfo.value + (key to info)
                                publish()
                            }
                        }
                    }
                    if (needsProvenance) {
                        facts += launch {
                            val label = withContext(ioDispatcher) { provenanceFor(state) }
                            _provenance.value = _provenance.value + (key to label)
                            publish()
                        }
                    }
                    if (needsArtwork) {
                        facts += launch {
                            val artwork = withContext(ioDispatcher) { artworkFor(state) }
                            if (artwork != null) {
                                _artwork.value = _artwork.value + (key to artwork)
                                publish()
                            }
                        }
                    }
                    facts.joinAll()

                    persistPresentation(state)
                } finally {
                    presenting.remove(key)
                }
            }
        }
    }

    private fun publish() {

        val serverNativeIds = (_jellyfin.value as? JellyfinSegment.Loaded)?.items
            ?.mapTo(HashSet()) { it.nativeItemId }
        val loadedItems = (_jellyfin.value as? JellyfinSegment.Loaded)?.items.orEmpty()

        val serverCanonicalKeys = HashSet<String>()
        for (item in loadedItems) jellyfinCanonicalKey(item)?.let(serverCanonicalKeys::add)
        val localCards = _localStates.value

            .sortedByDescending { it.lastMeaningfulAt ?: Instant.EPOCH }
            .filterNot { state ->
                shouldSuppressJellyfinShadow(state, serverNativeIds) ||
                    localCanonicalKey(state)?.let(serverCanonicalKeys::contains) == true
            }
            .mapNotNull { state ->
                val key = state.watchKey
                localCard(
                    state = state,
                    info = _cwInfo.value[key],
                    provenanceLabel = _provenance.value[key],
                    artwork = _artwork.value[key],
                )
            }

        val jellyfinCards = dedupeServerCards(loadedItems.mapNotNull(::jellyfinCard))

        val cards = (localCards + jellyfinCards).sortedWith(unifiedContinueWatchingOrder)

        val row = when {
            cards.isNotEmpty() -> HomeContinueWatchingRow.Loaded(cards)
            !localLoaded || _jellyfin.value is JellyfinSegment.Loading ->
                HomeContinueWatchingRow.Loading
            _jellyfin.value is JellyfinSegment.Failed -> {
                val message = (_jellyfin.value as JellyfinSegment.Failed).message
                HomeContinueWatchingRow.Failed(message)
            }
            else -> HomeContinueWatchingRow.Empty
        }

        val boundary =
            "published local=${localCards.size} jellyfin=${jellyfinCards.size} " +
                "jellyfin_items=${loadedItems.size} row=${cards.size} " +

                "artwork=${cards.count { it.preview.artwork != null }}"
        if (boundary != lastBoundaryTrace) {
            lastBoundaryTrace = boundary
            trace(boundary)
        }
        _state.value = HomeContinueWatchingUiState(
            row = row,
            segmentNote = if (cards.isNotEmpty() && _jellyfin.value is JellyfinSegment.Failed) {
                (_jellyfin.value as JellyfinSegment.Failed).message
            } else {
                null
            },
            initialFocusKey = (row as? HomeContinueWatchingRow.Loaded)?.cards?.firstOrNull()?.key,
        )
    }

    private fun localCardKey(key: WatchKey): String = when {
        key.isEpisode -> "item:${key.seriesId!!.value}:S${key.seasonNumber}E${key.episodeNumber}"
        key.editionId != null -> "item:${key.titleId!!.value}:${key.editionId!!.value}"
        else -> "item:${key.titleId!!.value}"
    }

    private fun shouldSuppressJellyfinShadow(
        state: WatchState,
        serverNativeIds: Set<String>?,
    ): Boolean {
        val jellyfinId = jellyfinSourceId ?: return false
        val serverIds = serverNativeIds ?: return false
        val anchor = state.anchor ?: return false
        return anchor.nativeItemId.sourceId == jellyfinId &&
            anchor.nativeItemId.nativeId in serverIds
    }

    private fun localCanonicalKey(state: WatchState): String? {
        val key = state.watchKey
        return if (key.isEpisode) {
            val series = key.seriesId ?: return null
            val season = key.seasonNumber ?: return null
            val episode = key.episodeNumber ?: return null
            episodeCanonicalKey(series, season, episode)
        } else {
            val title = key.titleId ?: return null
            movieCanonicalKey(title)
        }
    }

    private fun jellyfinCanonicalKey(item: JellyfinTabRowItem): String? {
        val episodic = item.kind == TitleKind.EPISODE ||
            item.seriesTitleId != null ||
            item.seasonNumber != null ||
            item.episodeNumber != null
        return if (episodic) {
            val series = item.canonicalSeriesTitleId ?: return null
            val season = item.seasonNumber ?: return null
            val episode = item.episodeNumber ?: return null
            episodeCanonicalKey(series, season, episode)
        } else {
            val title = item.canonicalTitleId ?: return null
            movieCanonicalKey(title)
        }
    }

    private fun movieCanonicalKey(titleId: TitleId): String = "movie:${titleId.value}"

    private fun episodeCanonicalKey(seriesId: TitleId, season: Int, episode: Int): String =
        "episode:${seriesId.value}:$season:$episode"

    private fun dedupeServerCards(cards: List<HomeCwCard>): List<HomeCwCard> {
        if (cards.size < 2) return cards
        val seen = HashSet<String>()
        return cards.sortedWith(unifiedContinueWatchingOrder).filter { card ->
            val key = (card.origin as? HomeCwOrigin.Jellyfin)?.let { jellyfinCanonicalKey(it.item) }
            key == null || seen.add(key)
        }
    }

    private fun localCard(
        state: WatchState,
        info: CwCatalogInfo?,
        provenanceLabel: String?,
        artwork: MediaArtworkRef?,
    ): HomeCwCard {
        val key = state.watchKey
        val canonicalTitle = key.titleId ?: key.seriesId!!
        val kind = if (key.isEpisode) TitleKind.EPISODE else TitleKind.MOVIE

        val subtitle = when {
            key.isEpisode -> {
                val season = key.seasonNumber
                val episode = key.episodeNumber
                if (season != null && episode != null) "S${season}E${episode}"
                else DurationFormat.formatDuration(state.positionMs)
            }
            else -> DurationFormat.formatDuration(state.positionMs)
        }

        val episodeIdentification = if (kind == TitleKind.EPISODE) {
            key.seasonNumber?.let { season ->
                key.episodeNumber?.let { episode ->
                    EpisodeCardIdentification(
                        seriesContext = info?.displayTitle,
                        coordinate = "S${season}E${episode}",
                        episodeTitle = info?.episodeTitle,
                        runtimeMinutes = info?.runtimeMinutes,
                        airDate = info?.airDate,
                    )
                }
            }
        } else {
            null
        }
        return HomeCwCard(
            key = localCardKey(key),

            preview = MediaPreview(
                titleId = canonicalTitle,
                kind = kind,
                displayTitle = info?.displayTitle ?: "Unknown title",

                watchKey = key,

                year = info?.year,

                airDate = info?.airDate,
                subtitle = subtitle,
                artwork = artwork,
                progressFraction = state.completionFraction,

                episodeIdentification = episodeIdentification,

                episodeTitleId = info?.episodeTitleId,
            ),
            provenanceLabel = provenanceLabel,
            origin = HomeCwOrigin.Local(
                state = state,
                titleId = canonicalTitle,
                kind = kind,
                seriesTitleId = key.seriesId,
                seasonNumber = key.seasonNumber,
                episodeNumber = key.episodeNumber,
                episodeTitleId = info?.episodeTitleId,
            ),
        )
    }

    private fun jellyfinCard(item: JellyfinTabRowItem): HomeCwCard = HomeCwCard(
        key = item.key,

        preview = item.preview,
        provenanceLabel = "Jellyfin",
        origin = HomeCwOrigin.Jellyfin(item),
    )
}

private val unifiedContinueWatchingOrder: Comparator<HomeCwCard> =
    compareByDescending<HomeCwCard> { it.realActivityAt() ?: Instant.EPOCH }
        .thenBy { it.key }

private fun HomeCwCard.realActivityAt(): Instant? = when (val origin = origin) {
    is HomeCwOrigin.Local -> origin.state.lastMeaningfulAt
    is HomeCwOrigin.Jellyfin -> parseJellyfinLastPlayedDate(origin.item.lastPlayedDate)
}

private fun parseJellyfinLastPlayedDate(raw: String?): Instant? {
    if (raw == null) return null
    return try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
}

const val HomeCwMixedTitle = "Continue Watching"

const val HomeCwMoviesTitle = "Continue Watching Movies"

const val HomeCwShowsTitle = "Continue Watching Shows"

const val HomeCwShelfKey = "shelf:continue-watching"

const val HomeCwMoviesShelfKey = "shelf:continue-watching:movies"
const val HomeCwShowsShelfKey = "shelf:continue-watching:shows"

data class HomeCwRow(val key: String, val title: String, val cards: List<HomeCwCard>)

fun homeContinueWatchingRows(cards: List<HomeCwCard>, separate: Boolean): List<HomeCwRow> {
    if (!separate) {
        return if (cards.isEmpty()) {
            emptyList()
        } else {
            listOf(HomeCwRow(HomeCwShelfKey, HomeCwMixedTitle, cards))
        }
    }
    val movies = cards.filterNot { it.isEpisodicContinuation() }
    val shows = cards.filter { it.isEpisodicContinuation() }
    return listOfNotNull(
        movies.takeIf { it.isNotEmpty() }
            ?.let { HomeCwRow(HomeCwMoviesShelfKey, HomeCwMoviesTitle, it) },
        shows.takeIf { it.isNotEmpty() }
            ?.let { HomeCwRow(HomeCwShowsShelfKey, HomeCwShowsTitle, it) },
    )
}

fun HomeCwCard.isEpisodicContinuation(): Boolean = when (val origin = origin) {
    is HomeCwOrigin.Local ->
        origin.kind == TitleKind.EPISODE || origin.seriesTitleId != null
    is HomeCwOrigin.Jellyfin ->
        origin.item.kind == TitleKind.EPISODE ||
            origin.item.seriesTitleId != null ||
            origin.item.seasonNumber != null ||
            origin.item.episodeNumber != null
}
