package dev.infusion.feature.vertical.watchlist

import dev.infusion.core.domain.watch.GetRecentlyWatchedUseCase
import dev.infusion.core.domain.watch.RecentlyWatchedDemand
import dev.infusion.core.domain.watch.RecentlyWatchedEntry
import dev.infusion.core.domain.watch.RecentlyWatchedKind
import dev.infusion.core.domain.watch.RecentlyWatchedRows
import dev.infusion.core.domain.watch.RecentlyWatchedSnapshot
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecentlyWatchedCard(

    val key: String,

    val titleId: TitleId,

    val kind: TitleKind,

    val preview: MediaPreview,

    val entry: RecentlyWatchedEntry,
)

data class RecentlyWatchedFacts(
    val displayTitle: String,
    val year: Int? = null,
    val artwork: MediaArtworkRef? = null,
)

data class RecentlyWatchedUiState(
    val movies: List<RecentlyWatchedCard> = emptyList(),
    val shows: List<RecentlyWatchedCard> = emptyList(),
    val loaded: Boolean = false,
    val moviesHasMore: Boolean = false,
    val showsHasMore: Boolean = false,
)

class RecentlyWatchedStateHolder(
    private val recentlyWatched: GetRecentlyWatchedUseCase,
    private val scope: CoroutineScope,
    private val resolve: suspend (RecentlyWatchedEntry) -> RecentlyWatchedFacts? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playbackActive: StateFlow<Boolean> = MutableStateFlow(false),
) {

    private val _state = MutableStateFlow(RecentlyWatchedUiState())
    val state: StateFlow<RecentlyWatchedUiState> = _state.asStateFlow()

    @Volatile
    private var snapshot: RecentlyWatchedSnapshot = RecentlyWatchedSnapshot()

    private val facts = ConcurrentHashMap<String, RecentlyWatchedFacts>()

    private val attempted = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var presentedMovies: Int = 0

    @Volatile
    private var presentedShows: Int = 0

    @Volatile
    private var sweep: Job? = null

    private val demands = MutableSharedFlow<RecentlyWatchedDemand>(
        extraBufferCapacity = DEMAND_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val surfacePresent = MutableStateFlow(true)

    init {
        scope.launch {
            recentlyWatched.observe(demands, surfacePresent, playbackActive).collect { published ->
                publish(published)
                sweep()
            }
        }

        scope.launch {
            playbackActive.filter { active -> active }.collect { sweep?.cancel() }
        }
    }

    fun loadMore(kind: RecentlyWatchedKind) {
        demands.tryEmit(RecentlyWatchedDemand.More(kind))
    }

    fun refresh() {
        demands.tryEmit(RecentlyWatchedDemand.Refresh)
    }

    fun onSurfacePresent() {
        surfacePresent.value = true
    }

    fun onSurfaceAbsent() {
        surfacePresent.value = false
    }

    fun reportPresented(movies: Int, shows: Int) {
        val grown = movies > presentedMovies || shows > presentedShows
        presentedMovies = maxOf(presentedMovies, movies)
        presentedShows = maxOf(presentedShows, shows)
        if (!grown) return
        sweep()
    }

    private fun publish(published: RecentlyWatchedSnapshot) {
        snapshot = published
        _state.value = RecentlyWatchedUiState(
            movies = published.rows.movies.map(::card),
            shows = published.rows.shows.map(::card),
            loaded = published.loaded,
            moviesHasMore = published.moviesHasMore,
            showsHasMore = published.showsHasMore,
        )
    }

    private fun republish() = publish(snapshot)

    private fun card(entry: RecentlyWatchedEntry): RecentlyWatchedCard {
        val key = cardKey(entry)
        val kind = if (entry.kind == RecentlyWatchedKind.MOVIE) TitleKind.MOVIE else TitleKind.SERIES
        val known = facts[key]
        return RecentlyWatchedCard(
            key = key,
            titleId = entry.titleId,
            kind = kind,
            entry = entry,
            preview = MediaPreview(
                titleId = entry.titleId,
                kind = kind,

                displayTitle = known?.displayTitle?.takeIf { it.isNotBlank() } ?: UNKNOWN_TITLE,
                year = known?.year,
                subtitle = null,
                artwork = known?.artwork,

                progressFraction = null,

                watchKey = if (entry.kind == RecentlyWatchedKind.MOVIE) {
                    WatchKey.movie(entry.titleId)
                } else {
                    null
                },
            ),
        )
    }

    private fun sweep() {

        if (playbackActive.value || !surfacePresent.value) return
        if (sweep?.isActive == true) return
        val pending = pendingTargets()
        if (pending.isEmpty()) return
        sweep = scope.launch(ioDispatcher) {
            try {
                val queue = pending.toMutableList()
                while (queue.isNotEmpty()) {

                    if (playbackActive.value) return@launch
                    val batch = queue.take(RESOLVE_BATCH).also { taken -> queue.subList(0, taken.size).clear() }
                    val entries = materializedByKey()
                    for (key in batch) {

                        val entry = entries[key] ?: run { attempted += key; continue }

                        val resolved = try {
                            resolve(entry)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            attempted += key
                            continue
                        }
                        attempted += key
                        if (resolved == null) continue
                        facts[key] = resolved
                        republish()
                    }
                }
            } finally {
                sweep = null

                if (isActive && pendingTargets().isNotEmpty()) sweep()
            }
        }
    }

    private fun pendingTargets(): List<String> =
        materializedByKey().keys.filter { it !in attempted }

    private fun materializedByKey(): Map<String, RecentlyWatchedEntry> =
        snapshot.rows.movies.take(presentedMovies)
            .plus(snapshot.rows.shows.take(presentedShows))
            .associateBy(::cardKey)

    companion object {

        const val RESOLVE_BATCH: Int = 4

        const val DEMAND_BUFFER: Int = 8

        const val UNKNOWN_TITLE: String = "Unknown title"

        fun cardKey(entry: RecentlyWatchedEntry): String = "$CARD_KEY_PREFIX${entry.entryKey}"

        fun cardKeyPrefix(kind: RecentlyWatchedKind): String = "$CARD_KEY_PREFIX${kind.name}:"

        const val CARD_KEY_PREFIX: String = "recent:"
    }
}
