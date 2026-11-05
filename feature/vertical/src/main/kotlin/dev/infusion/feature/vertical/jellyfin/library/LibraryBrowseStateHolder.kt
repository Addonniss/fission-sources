package dev.infusion.feature.vertical.jellyfin.library

import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryBrowseStateHolder(
    private val port: LibraryBrowsePort,
    private val scope: CoroutineScope,
    private val serverId: String,
    private val userId: String,
    private val libraryNativeId: String,
    private val libraryName: String,
    private val collectionType: String?,
    initialView: LibraryView? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val maxInFlight = 2

    private data class RetainedLevel(
        val key: LibraryQueryKey,
        val generation: Long,
        val window: LibraryWindow,
        val phase: LibraryContentPhase,
        val footer: LibraryFooter?,
        val returnSnapshot: LibraryReturnSnapshot?,
        val requestedPages: Set<Int> = emptySet(),
        val inFlight: Set<Int> = emptySet(),
        val jobs: Map<Int, Job> = emptyMap(),
        val focusedItemId: String? = null,
        val focusedServerIndex: Int? = null,
        val firstVisibleServerIndex: Int? = null,
    )

    private sealed interface SectionsPhase {
        data object Loading : SectionsPhase
        data class Loaded(val sections: List<LibrarySuggestionSection>) : SectionsPhase
        data class Failed(val message: String) : SectionsPhase
    }

    private val support: LibrarySupport = LibraryCapabilities.support(collectionType)

    private val type: LibraryType? = (support as? LibrarySupport.Supported)?.type

    private var nextGeneration: Long = 0L
    private var activeLevel: LibraryLevel = LibraryLevel.Root
    private var levels: Map<LibraryLevel, RetainedLevel> = emptyMap()
    private var sections: SectionsPhase = SectionsPhase.Loading
    private var sectionsRequestedFor: LibraryView? = null
    private var filterOptions: LibraryFilterOptions? = null
    private var filterOptionsPhase: LibraryContentPhase = LibraryContentPhase.Loading
    private var filterOptionsGeneration: Long = 0L

    private var genreArtworkCandidates: Map<String, List<String>> = emptyMap()

    private val genreArtworkRequested = mutableSetOf<String>()

    private val genreArtworkQueue = mutableListOf<String>()

    private val genreArtworkLock = Any()

    private var genreArtworkJob: Job? = null

    private val _state = MutableStateFlow(
        initialState(
            initialView ?: (support as? LibrarySupport.Supported)?.views?.firstOrNull()
                ?: LibraryView.MOVIES,
        ),
    )
    val state: StateFlow<LibraryBrowseUiState> = _state.asStateFlow()

    init {
        val supportedViews = (support as? LibrarySupport.Supported)?.views.orEmpty()
        val view = initialView?.takeIf { supportedViews.contains(it) } ?: supportedViews.firstOrNull()
        if (view != null) {
            startQuery(view = view, level = LibraryLevel.Root, filters = LibraryFilterValues(), sort = null)
        } else {
            publish()
        }
    }

    private fun initialState(view: LibraryView): LibraryBrowseUiState {
        val selected = type?.let { mappedType -> resolveView(mappedType, view) } ?: view
        val sort = type?.let { LibraryCapabilities.defaultSort(it, selected, LibraryLevel.Root) }
            ?: LibrarySort(LibrarySortField.NAME, LibrarySortOrder.ASCENDING)
        val key = type?.let {
            LibraryCapabilities.key(
                serverId, userId, libraryNativeId, it, selected, LibraryLevel.Root,
                LibraryFilterValues(), sort,
            )
        } ?: LibraryCapabilities.key(
            serverId, userId, libraryNativeId, LibraryType.MOVIES, LibraryView.MOVIES,
        )
        return LibraryBrowseUiState(
            libraryName = libraryName,
            support = support,
            view = selected,
            level = LibraryLevel.Root,
            filters = LibraryFilterValues(),
            sort = sort,
            window = LibraryWindow.empty(key, 0L),
            phase = if (support is LibrarySupport.Unsupported) LibraryContentPhase.Loaded else LibraryContentPhase.Loading,
            footer = null,
            filterOptions = null,
            filterOptionsPhase = LibraryContentPhase.Loading,
            sections = LibraryContentPhase.Loading,
            sectionRows = emptyList(),
            windowRevision = 0L,
            returnSnapshot = null,
        )
    }

    private fun resolveView(mapped: LibraryType, view: LibraryView): LibraryView {
        val supported = LibraryCapabilities.views(mapped)
        return view.takeIf { supported.contains(it) } ?: supported.first()
    }

    fun selectView(view: LibraryView) {
        if (view == _state.value.view) {

            if (_state.value.phase is LibraryContentPhase.Failed) retryFirstPage()
            return
        }
        val supportedViews = (support as? LibrarySupport.Supported)?.views.orEmpty()
        if (!supportedViews.contains(view)) return
        startQuery(view = view, level = LibraryLevel.Root, filters = LibraryFilterValues(), sort = null)
    }

    fun selectSort(field: LibrarySortField) {
        val mapped = type ?: return
        val current = _state.value
        if (!LibraryCapabilities.sortFields(mapped, current.view, current.level).contains(field)) return
        val next = current.sort.select(field)
        startQuery(view = current.view, level = current.level, filters = current.filters, sort = next)
    }

    fun toggleStatus(status: LibraryStatusFilter) = updateFilters { it.withStatus(status) }

    fun toggleFeature(feature: LibraryFeature) = updateFilters { it.withFeature(feature) }

    fun selectSeriesStatus(status: LibrarySeriesStatus) = updateFilters { it.withSeriesStatus(status) }

    fun toggleEpisodeFlag(flag: LibraryEpisodeFlag) = updateFilters { it.withEpisodeFlag(flag) }

    fun selectGenreFilter(genre: String?) = updateFilters { it.copy(genre = genre) }

    fun selectOfficialRating(rating: String?) = updateFilters { it.copy(officialRating = rating) }

    fun selectTag(tag: String?) = updateFilters { it.copy(tag = tag) }

    fun selectVideoType(type: LibraryVideoType?) = updateFilters { it.copy(videoType = type) }

    fun toggleYear(year: Int) = updateFilters { it.withYear(year) }

    fun resetFilters() {
        val current = _state.value
        if (current.filters.isEmpty) return
        startQuery(view = current.view, level = current.level, filters = LibraryFilterValues(), sort = current.sort)
    }

    fun openLevel(level: LibraryLevel) {
        val current = _state.value
        startQuery(
            view = current.view,
            level = level,
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openGenre(genreId: String, genreName: String) {
        val current = _state.value
        if (current.view != LibraryView.GENRES) return
        startQuery(
            view = current.view,
            level = LibraryLevel.Genre(genreId, genreName),
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openBoxSet(boxSetId: String, boxSetName: String) {
        val current = _state.value
        if (current.view != LibraryView.COLLECTIONS) return
        startQuery(
            view = current.view,
            level = LibraryLevel.BoxSet(boxSetId, boxSetName),
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openNetwork(studioId: String, networkName: String) {
        val current = _state.value
        if (current.view != LibraryView.NETWORKS) return
        startQuery(
            view = current.view,
            level = LibraryLevel.Network(studioId, networkName),
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openArtist(artistId: String, artistName: String) {
        val current = _state.value
        if (current.view != LibraryView.ARTISTS && current.view != LibraryView.ALBUM_ARTISTS) return
        startQuery(
            view = current.view,
            level = LibraryLevel.Artist(artistId, artistName),
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openAlbum(
        albumId: String,
        albumName: String,
        artistId: String? = null,
        artistName: String? = null,
    ) {
        val current = _state.value
        val allowed = when (current.view) {
            LibraryView.ALBUMS -> true
            LibraryView.ARTISTS, LibraryView.ALBUM_ARTISTS -> current.level is LibraryLevel.Artist
            LibraryView.GENRES -> current.level is LibraryLevel.Genre
            else -> false
        }
        if (!allowed) return
        startQuery(
            view = current.view,
            level = LibraryLevel.Album(albumId, albumName, artistId, artistName),
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openPlaylist(playlistId: String, playlistName: String) {
        val current = _state.value
        if (current.view != LibraryView.PLAYLISTS) return
        startQuery(
            view = current.view,
            level = LibraryLevel.Playlist(playlistId, playlistName),
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun openMusicAlbum(albumId: String, albumName: String) {
        openMusicLevel(
            LibraryView.ALBUMS,
            LibraryLevel.Album(albumId, albumName),
        )
    }

    fun openMusicArtist(artistId: String, artistName: String) {
        openMusicLevel(
            LibraryView.ARTISTS,
            LibraryLevel.Artist(artistId, artistName),
        )
    }

    fun openMusicPlaylist(playlistId: String, playlistName: String) {
        openMusicLevel(
            LibraryView.PLAYLISTS,
            LibraryLevel.Playlist(playlistId, playlistName),
        )
    }

    private fun openMusicLevel(view: LibraryView, level: LibraryLevel) {
        if (type != LibraryType.MUSIC) return
        val supportedViews = (support as? LibrarySupport.Supported)?.views.orEmpty()
        if (!supportedViews.contains(view)) return
        startQuery(
            view = view,
            level = level,
            filters = LibraryFilterValues(),
            sort = null,
        )
    }

    fun backToParent(): Boolean {
        val current = _state.value
        val parent = levels[current.level]?.key?.parentLevel ?: return false
        val retained = levels[parent] ?: return false
        activeLevel = parent
        _state.value = _state.value.copy(
            level = parent,
            view = retained.key.view,
            filters = retained.key.filters,
            sort = sortOf(retained.key),
        )
        publish()
        return true
    }

    private fun sortOf(key: LibraryQueryKey): LibrarySort {
        val mapped = key.type
        val fields = LibraryCapabilities.sortFields(mapped, key.view, key.level)
        val field = fields.firstOrNull { candidate ->
            LibraryCapabilities.sortBy(candidate, mapped, key.view, key.level) == key.sortBy
        } ?: LibraryCapabilities.defaultSort(mapped, key.view, key.level).field
        return LibrarySort(field, key.sortOrder)
    }

    fun retryFirstPage() {
        val level = activeLevel
        val retained = levels[level] ?: return
        resetLevel(retained.key, generation = nextGenerationValue())
    }

    fun retryAppend() {
        val retained = levels[activeLevel] ?: return
        val footer = retained.footer as? LibraryFooter.Retry ?: return
        levels = levels + (activeLevel to retained.copy(footer = null))
        publish()
        requestPage(retained.startIndexOfFailedAppend(), forward = true)
    }

    fun onAnchors(
        firstVisibleServerIndex: Int?,
        focusedServerIndex: Int?,
        focusedItemId: String?,
    ) {
        val retained = levels[activeLevel] ?: return
        levels = levels + (
            activeLevel to retained.copy(
                firstVisibleServerIndex = firstVisibleServerIndex,
                focusedServerIndex = focusedServerIndex,
                focusedItemId = focusedItemId,
            )
            )
    }

    fun onForwardHorizon() {
        val retained = levels[activeLevel] ?: return
        if (retained.phase !is LibraryContentPhase.Loaded) return
        if (retained.window.hasMore == false) return
        requestPage(retained.window.nextForwardStart, forward = true)
    }

    fun ensureLoaded(index: Int) {
        val retained = levels[activeLevel] ?: return
        if (index < 0) return
        if (retained.window.isLoaded(index)) return
        val pageIndex = index / retained.window.pageSize
        if (retained.requestedPages.contains(pageIndex) && retained.footer !is LibraryFooter.Retry) return
        requestPage(pageIndex * retained.window.pageSize, forward = false)
    }

    fun captureReturn(
        focusedItemId: String?,
        focusedServerIndex: Int?,
        firstVisibleVisibleIndex: Int,
        firstVisibleServerIndex: Int?,
        firstVisibleScrollOffsetPx: Int,
    ) {
        val retained = levels[activeLevel] ?: return
        val snapshot = LibraryReturnSnapshot(
            key = retained.key,
            focusedItemId = focusedItemId,
            focusedServerIndex = focusedServerIndex,
            firstVisibleVisibleIndex = firstVisibleVisibleIndex,
            firstVisibleServerIndex = firstVisibleServerIndex,
            firstVisibleScrollOffsetPx = firstVisibleScrollOffsetPx,
            generation = retained.generation,
            windowId = retained.generation,
        )
        levels = levels + (activeLevel to retained.copy(returnSnapshot = snapshot))
        publish()
    }

    fun hasPendingReturn(): Boolean = levels[activeLevel]?.returnSnapshot != null

    fun consumeReturn(): LibraryReturnSnapshot? {
        val retained = levels[activeLevel] ?: return null
        val snapshot = retained.returnSnapshot ?: return null
        levels = levels + (activeLevel to retained.copy(returnSnapshot = null))
        publish()
        return snapshot
    }

    fun refresh() {
        synchronized(genreArtworkLock) {
            genreArtworkRequested.retainAll(genreArtworkCandidates.keys)
        }
        val current = _state.value
        startQuery(view = current.view, level = current.level, filters = current.filters, sort = current.sort)
    }

    private fun updateFilters(transform: (LibraryFilterValues) -> LibraryFilterValues) {
        val mapped = type ?: return
        val current = _state.value
        if (!LibraryCapabilities.hasFilters(mapped, current.view, current.level)) return
        val next = transform(current.filters)
        if (next == current.filters) return
        startQuery(view = current.view, level = current.level, filters = next, sort = current.sort)
    }

    private fun nextGenerationValue(): Long = ++nextGeneration

    private fun startQuery(
        view: LibraryView,
        level: LibraryLevel,
        filters: LibraryFilterValues,
        sort: LibrarySort?,
    ) {
        val mapped = type ?: return
        cancelAllJobs()
        activeLevel = level
        val effectiveSort = sort ?: LibraryCapabilities.defaultSort(mapped, view, level)
        val key = LibraryCapabilities.key(
            serverId, userId, libraryNativeId, mapped, view, level, filters, effectiveSort,
        )
        resetLevel(key, generation = nextGenerationValue(), view = view, filters = filters, sort = effectiveSort)
        if (LibraryCapabilities.hasFilters(mapped, view, level)) loadFilterOptions(key)
        if (key.presentation == LibraryPresentation.SECTIONS) loadSections(key)
        publish()
    }

    private fun resetLevel(
        key: LibraryQueryKey,
        generation: Long,
        view: LibraryView = key.view,
        filters: LibraryFilterValues = key.filters,
        sort: LibrarySort? = null,
    ) {
        val retained = RetainedLevel(
            key = key,
            generation = generation,
            window = LibraryWindow.empty(key, generation),
            phase = LibraryContentPhase.Loading,
            footer = null,
            returnSnapshot = null,
        )
        levels = levels + (key.level to retained)
        activeLevel = key.level
        _state.value = _state.value.copy(
            view = view,
            level = key.level,
            filters = filters,
            sort = sort ?: _state.value.sort,
        )
        requestPage(startIndex = 0, forward = true, enableTotalRecordCount = true)
    }

    private fun RetainedLevel.startIndexOfFailedAppend(): Int =
        window.contiguousLoadedEnd.takeIf { it > 0 } ?: 0

    private fun requestPage(
        startIndex: Int,
        forward: Boolean,
        enableTotalRecordCount: Boolean = false,
    ) {
        val level = activeLevel
        val retained = levels[level] ?: return
        val pageIndex = startIndex / retained.window.pageSize
        if (retained.inFlight.contains(pageIndex)) return
        if (retained.inFlight.size >= maxInFlight) return
        val generation = retained.generation
        val meta = LibraryRequestMeta(
            startIndex = pageIndex * retained.window.pageSize,
            limit = retained.window.pageSize,
            enableTotalRecordCount = enableTotalRecordCount,
        )
        val key = retained.key
        val pending = retained.copy(
            inFlight = retained.inFlight + pageIndex,
            requestedPages = retained.requestedPages + pageIndex,
            footer = if (forward && retained.phase is LibraryContentPhase.Loaded) {
                LibraryFooter.Loading
            } else {
                retained.footer
            },
        )
        levels = levels + (level to pending)
        publish()

        val job = scope.launch(ioDispatcher) {
            val result = try {
                port.page(key, meta)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                LibraryFetch.Failed(failure.message ?: "Could not load this page.")
            }
            val current = levels[level] ?: return@launch

            if (current.generation != generation) return@launch
            val settled = current.copy(
                inFlight = current.inFlight - pageIndex,
                jobs = current.jobs - pageIndex,
            )
            when (result) {
                is LibraryFetch.Ok -> applyPage(level, settled, result.page, result.total)
                is LibraryFetch.Failed -> applyFailure(level, settled, result.message, forward)
            }
        }
        val running = levels[level] ?: return
        levels = levels + (level to running.copy(jobs = running.jobs + (pageIndex to job)))
    }

    private fun applyPage(level: LibraryLevel, retained: RetainedLevel, page: LibraryPage, total: Int?) {
        val firstPageOfWindow = retained.window.pageIndexes.isEmpty()
        val window = retained.window.withPage(
            page = page,
            focusedServerIndex = retained.focusedServerIndex,
            firstVisibleServerIndex = retained.firstVisibleServerIndex,
            total = total ?: retained.window.total,
        )

        val phase = if (firstPageOfWindow && page.items.isEmpty()) {
            LibraryContentPhase.Empty
        } else {
            LibraryContentPhase.Loaded
        }
        levels = levels + (
            level to retained.copy(
                window = window,
                phase = phase,
                footer = if (retained.footer is LibraryFooter.Loading) null else retained.footer,
            )
            )
        publish()
        requestGenreArtwork(level, retained.key, page.items)
    }

    private fun applyFailure(level: LibraryLevel, retained: RetainedLevel, message: String, forward: Boolean) {
        val firstPage = retained.window.pageIndexes.isEmpty()
        val phase = if (firstPage) LibraryContentPhase.Failed(message) else retained.phase
        levels = levels + (
            level to retained.copy(
                phase = phase,

                footer = if (firstPage) null else LibraryFooter.Retry(message),
            )
            )
        publish()
    }

    private fun requestGenreArtwork(
        level: LibraryLevel,
        key: LibraryQueryKey,
        items: List<JellyfinTabRowItem>,
    ) {
        if (level !is LibraryLevel.Root) return
        if (key.view != LibraryView.GENRES) return
        val startWorker = synchronized(genreArtworkLock) {
            val pending = buildList {
                for (item in items) {
                    val genreId = item.nativeItemId
                    if (genreId.isBlank()) continue
                    if (genreArtworkCandidates.containsKey(genreId)) continue
                    if (genreArtworkRequested.contains(genreId)) continue
                    if (genreArtworkQueue.contains(genreId)) continue
                    add(genreId)
                }
            }.distinct()
            if (pending.isEmpty()) return
            genreArtworkQueue += pending
            genreArtworkJob?.isActive != true
        }
        if (!startWorker) return
        genreArtworkJob = scope.launch(ioDispatcher) {

            while (true) {
                val batch = synchronized(genreArtworkLock) {
                    val next = genreArtworkQueue.toList()
                    genreArtworkQueue.clear()
                    if (next.isNotEmpty()) genreArtworkRequested += next
                    next
                }
                if (batch.isEmpty()) break
                val result = try {
                    port.genreArtwork(key, batch)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    LibraryGenreArtworkFetch.Failed(failure.message ?: "Could not load genre artwork.")
                }

                if (result is LibraryGenreArtworkFetch.Ok && result.candidates.isNotEmpty()) {
                    synchronized(genreArtworkLock) {
                        genreArtworkCandidates = genreArtworkCandidates + result.candidates
                    }
                    publish()
                }
            }
        }
    }

    private fun loadFilterOptions(key: LibraryQueryKey) {
        val generation = nextGenerationValue()
        filterOptionsGeneration = generation
        filterOptionsPhase = LibraryContentPhase.Loading
        scope.launch(ioDispatcher) {
            val result = try {
                port.filterOptions(key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                LibraryFilterOptionsFetch.Failed(failure.message ?: "Could not load filters.")
            }
            if (filterOptionsGeneration != generation) return@launch
            when (result) {
                is LibraryFilterOptionsFetch.Ok -> {
                    filterOptions = result.options
                    filterOptionsPhase = LibraryContentPhase.Loaded
                }
                is LibraryFilterOptionsFetch.Failed -> {
                    filterOptions = null
                    filterOptionsPhase = LibraryContentPhase.Failed(result.message)
                }
            }
            publish()
        }
    }

    fun retryFilterOptions() {
        val retained = levels[activeLevel] ?: return
        loadFilterOptions(retained.key)
        publish()
    }

    private fun loadSections(key: LibraryQueryKey) {
        if (sectionsRequestedFor == key.view) return
        sectionsRequestedFor = key.view
        sections = SectionsPhase.Loading
        scope.launch(ioDispatcher) {
            val result = try {
                port.sections(key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                LibrarySuggestionsFetch.Failed(failure.message ?: "Could not load this view.")
            }

            if (sectionsRequestedFor != key.view) return@launch
            sections = when (result) {
                is LibrarySuggestionsFetch.Ok -> SectionsPhase.Loaded(result.sections)
                is LibrarySuggestionsFetch.Failed -> SectionsPhase.Failed(result.message)
            }
            publish()
        }
    }

    fun retrySections() {
        sectionsRequestedFor = null
        val retained = levels[activeLevel] ?: return
        loadSections(retained.key)
        publish()
    }

    private fun cancelAllJobs() {
        levels.values.forEach { level -> level.jobs.values.forEach { it.cancel() } }
        levels = levels.mapValues { (_, level) ->
            level.copy(jobs = mutableMapOf(), inFlight = mutableSetOf(), requestedPages = mutableSetOf())
        }
    }

    private fun publish() {
        val retained = levels[activeLevel]
        val current = _state.value
        val sectionsPhase: LibraryContentPhase = when (val s = sections) {
            SectionsPhase.Loading -> LibraryContentPhase.Loading
            is SectionsPhase.Failed -> LibraryContentPhase.Failed(s.message)
            is SectionsPhase.Loaded ->
                if (s.sections.all { it.items.isEmpty() }) LibraryContentPhase.Empty else LibraryContentPhase.Loaded
        }
        val rows = (sections as? SectionsPhase.Loaded)?.sections.orEmpty()
        _state.value = current.copy(
            window = retained?.window ?: current.window,
            phase = retained?.phase ?: current.phase,
            footer = retained?.footer,
            filterOptions = filterOptions,
            filterOptionsPhase = filterOptionsPhase,
            sections = sectionsPhase,
            sectionRows = rows,
            windowRevision = retained?.generation ?: current.windowRevision,
            returnSnapshot = retained?.returnSnapshot,
            genreArtworkCandidates = synchronized(genreArtworkLock) { genreArtworkCandidates },
        )
    }
}

data class LibraryBrowseUiState(
    val libraryName: String,
    val support: LibrarySupport,
    val view: LibraryView,
    val level: LibraryLevel,
    val filters: LibraryFilterValues,
    val sort: LibrarySort,
    val window: LibraryWindow,
    val phase: LibraryContentPhase,
    val footer: LibraryFooter?,
    val filterOptions: LibraryFilterOptions?,
    val filterOptionsPhase: LibraryContentPhase,

    val sections: LibraryContentPhase,

    val sectionRows: List<LibrarySuggestionSection>,

    val windowRevision: Long,
    val returnSnapshot: LibraryReturnSnapshot?,

    val genreArtworkCandidates: Map<String, List<String>> = emptyMap(),
) {

    val views: List<LibraryView> get() = (support as? LibrarySupport.Supported)?.views.orEmpty()

    val type: LibraryType? get() = (support as? LibrarySupport.Supported)?.type

    val presentation: LibraryPresentation
        get() = type?.let { LibraryCapabilities.presentation(it, view, level) } ?: LibraryPresentation.GRID

    val sortFields: List<LibrarySortField>
        get() = type?.let { LibraryCapabilities.sortFields(it, view, level) }.orEmpty()

    val filterGroups: List<LibraryFilterGroup>
        get() = type?.let { LibraryCapabilities.filterGroups(it, view, level) }.orEmpty()

    val hasFilters: Boolean get() = filterGroups.isNotEmpty()

    val hasSort: Boolean get() = sortFields.isNotEmpty()

    val activeFilterCount: Int get() = filters.activeGroupCount(filterGroups)
}

sealed interface LibraryContentPhase {
    data object Loading : LibraryContentPhase
    data object Loaded : LibraryContentPhase
    data object Empty : LibraryContentPhase
    data class Failed(val message: String) : LibraryContentPhase
}

data class LibraryReturnSnapshot(
    val key: LibraryQueryKey,
    val focusedItemId: String?,
    val focusedServerIndex: Int?,
    val firstVisibleVisibleIndex: Int,
    val firstVisibleServerIndex: Int?,
    val firstVisibleScrollOffsetPx: Int,
    val generation: Long,
    val windowId: Long,
)
