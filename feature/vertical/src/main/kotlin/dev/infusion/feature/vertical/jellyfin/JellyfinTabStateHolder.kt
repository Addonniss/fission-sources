package dev.infusion.feature.vertical.jellyfin

import dev.infusion.core.model.EpisodeCardIdentification
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class JellyfinRowKind {
    CONTINUE_WATCHING,
    NEXT_UP,
    RECENTLY_ADDED_MOVIES,
    RECENTLY_ADDED_SHOWS,
    RECENTLY_ADDED_MUSIC,
    MY_MEDIA,
}

data class JellyfinTabRowItem(

    val key: String,

    val nativeItemId: String,

    val titleId: TitleId?,
    val displayTitle: String,
    val kind: TitleKind?,
    val year: Int?,

    val isFolder: Boolean = false,

    val collectionType: String? = null,
    val childCount: Int? = null,

    val seriesTitleId: TitleId? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,

    val playedPercentage: Double? = null,
    val resumePositionTicks: Long? = null,

    val runtimeTicks: Long? = null,

    val seriesName: String? = null,

    val airDate: LocalDate? = null,

    val lastPlayedDate: String? = null,

    val canonicalTitleId: TitleId? = null,

    val canonicalSeriesTitleId: TitleId? = null,

    val albumName: String? = null,

    val albumArtistName: String? = null,

    val parentNativeId: String? = null,

    val nativeType: String? = null,

    val albumArtistNativeId: String? = null,

    val imageTypes: Set<String> = emptySet(),

    val playlistItemId: String? = null,

    val unavailable: Boolean = false,
) {

    val preview: MediaPreview
        get() = MediaPreview(

            titleId = canonicalOrDeterministicTitleId,
            kind = kind,
            displayTitle = displayTitle,
            year = year,
            subtitle = episodeSubtitle(),
            artwork = MediaArtworkRef.catalog(nativeItemId),
            progressFraction = playedPercentage?.div(100.0),

            airDate = airDate,

            watchKey = episodeWatchKeyOrNull(),

            episodeIdentification = episodeIdentificationOrNull(),

            episodeTitleId = canonicalOrDeterministicTitleId.takeIf { kind == TitleKind.EPISODE },
        )

    private fun episodeIdentificationOrNull(): EpisodeCardIdentification? {
        if (kind != TitleKind.EPISODE) return null
        val season = seasonNumber?.takeIf { it > 0 } ?: return null
        val episode = episodeNumber?.takeIf { it > 0 } ?: return null
        return EpisodeCardIdentification(
            seriesContext = seriesName,
            coordinate = "S${season}E${episode}",
            episodeTitle = displayTitle,
            runtimeMinutes = runtimeMinutesOrNull(),
            airDate = airDate,
        )
    }

    private fun runtimeMinutesOrNull(): Int? {
        val ticks = runtimeTicks ?: return null
        if (ticks <= 0L) return null
        val minutes = ((ticks + TICKS_PER_MINUTE / 2L) / TICKS_PER_MINUTE).toInt()
        return minutes.takeIf { it > 0 }
    }

    private fun episodeWatchKeyOrNull(): WatchKey? {
        if (kind != TitleKind.EPISODE) return null
        val series = canonicalOrDeterministicSeriesTitleId ?: return null
        val season = seasonNumber?.takeIf { it > 0 } ?: return null
        val episode = episodeNumber?.takeIf { it > 0 } ?: return null
        return WatchKey.episode(series, season, episode)
    }

    val canonicalOrDeterministicTitleId: TitleId?
        get() = canonicalTitleId ?: titleId

    val canonicalOrDeterministicSeriesTitleId: TitleId?
        get() = canonicalSeriesTitleId ?: seriesTitleId

    private fun episodeSubtitle(): String? = when {
        kind != TitleKind.EPISODE -> null
        seriesName != null -> "$seriesName · S${seasonNumber ?: "?"}E${episodeNumber ?: "?"}"
        else -> "S${seasonNumber ?: "?"}E${episodeNumber ?: "?"}"
    }
}

sealed interface JellyfinRowState {

    data object Loading : JellyfinRowState

    data class Loaded(val items: List<JellyfinTabRowItem>) : JellyfinRowState

    data class Failed(val message: String) : JellyfinRowState
}

data class JellyfinTabRow(
    val kind: JellyfinRowKind,
    val title: String,
    val state: JellyfinRowState,
)

data class JellyfinTabUiState(
    val rows: List<JellyfinTabRow> = emptyList(),

    val loading: Boolean = false,

    val initialFocusKey: String? = null,
)

sealed interface JellyfinRowFetch {
    data class Ok(val items: List<JellyfinTabRowItem>) : JellyfinRowFetch

    data class Failed(val message: String) : JellyfinRowFetch
}

interface JellyfinTabPort {
    suspend fun continueWatching(): JellyfinRowFetch
    suspend fun nextUp(): JellyfinRowFetch
    suspend fun recentlyAddedMovies(): JellyfinRowFetch
    suspend fun recentlyAddedShows(): JellyfinRowFetch
    suspend fun recentlyAddedMusic(): JellyfinRowFetch
    suspend fun myMedia(): JellyfinRowFetch
}

private const val TICKS_PER_MINUTE = 600_000_000L

private val ROW_ORDER: List<JellyfinRowKind> = listOf(
    JellyfinRowKind.MY_MEDIA,
    JellyfinRowKind.CONTINUE_WATCHING,
    JellyfinRowKind.NEXT_UP,
    JellyfinRowKind.RECENTLY_ADDED_MOVIES,
    JellyfinRowKind.RECENTLY_ADDED_SHOWS,
    JellyfinRowKind.RECENTLY_ADDED_MUSIC,
)

private fun titleFor(kind: JellyfinRowKind): String = when (kind) {
    JellyfinRowKind.CONTINUE_WATCHING -> "Continue Watching"
    JellyfinRowKind.NEXT_UP -> "Next Up"
    JellyfinRowKind.RECENTLY_ADDED_MOVIES -> "Recently Added Movies"
    JellyfinRowKind.RECENTLY_ADDED_SHOWS -> "Recently Added Shows"
    JellyfinRowKind.RECENTLY_ADDED_MUSIC -> "Recently Added Music"
    JellyfinRowKind.MY_MEDIA -> "My Media"
}

class JellyfinTabStateHolder(
    private val port: JellyfinTabPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(JellyfinTabUiState())
    val state: StateFlow<JellyfinTabUiState> = _state.asStateFlow()

    private val rowStates = mutableMapOf<JellyfinRowKind, JellyfinRowState>()

    var hasSettled: Boolean = false
        private set

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)

        loadRow(JellyfinRowKind.CONTINUE_WATCHING) { port.continueWatching() }
        loadRow(JellyfinRowKind.NEXT_UP) { port.nextUp() }
        loadRow(JellyfinRowKind.RECENTLY_ADDED_MOVIES) { port.recentlyAddedMovies() }
        loadRow(JellyfinRowKind.RECENTLY_ADDED_SHOWS) { port.recentlyAddedShows() }
        loadRow(JellyfinRowKind.RECENTLY_ADDED_MUSIC) { port.recentlyAddedMusic() }
        loadRow(JellyfinRowKind.MY_MEDIA) { port.myMedia() }
    }

    private fun loadRow(kind: JellyfinRowKind, fetch: suspend () -> JellyfinRowFetch) {
        scope.launch(ioDispatcher) {
            val rowState = when (val result = runCatching { fetch() }.getOrNull()) {
                null -> JellyfinRowState.Failed("Could not load this row.")
                is JellyfinRowFetch.Failed -> JellyfinRowState.Failed(result.message)
                is JellyfinRowFetch.Ok -> JellyfinRowState.Loaded(result.items)
            }
            rowStates[kind] = rowState
            publish()
        }
    }

    private fun publish() {

        if (rowStates.size >= ROW_ORDER.size) hasSettled = true
        val rows = ROW_ORDER.mapNotNull { kind ->
            when (val rowState = rowStates[kind] ?: JellyfinRowState.Loading) {

                is JellyfinRowState.Loaded ->
                    if (rowState.items.isEmpty()) null else JellyfinTabRow(kind, titleFor(kind), rowState)
                is JellyfinRowState.Failed -> JellyfinTabRow(kind, titleFor(kind), rowState)
                JellyfinRowState.Loading -> JellyfinTabRow(kind, titleFor(kind), JellyfinRowState.Loading)
            }
        }

        var firstKey: String? = null
        for (kind in ROW_ORDER) {
            when (val rowState = rowStates[kind] ?: JellyfinRowState.Loading) {
                JellyfinRowState.Loading -> break
                is JellyfinRowState.Failed -> continue
                is JellyfinRowState.Loaded -> {
                    firstKey = rowState.items.firstOrNull()?.key
                    if (firstKey != null) break
                }
            }
        }
        _state.value = JellyfinTabUiState(
            rows = rows,
            loading = rows.any { it.state is JellyfinRowState.Loading },
            initialFocusKey = firstKey,
        )
    }
}
